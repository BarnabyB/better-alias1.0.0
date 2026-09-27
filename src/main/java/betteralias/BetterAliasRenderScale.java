package betteralias;

import betteralias.BetterAliasConfig.AntiAliasMode;
import betteralias.BetterAliasConfig.SsaaScale;
import betteralias.BetterAliasConfig.Upscaler;
import betteralias.mixin.RenderTargetAccessor;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

/**
 * Renders the world at a different resolution from the window: above it for SSAA (supersampling), below it for
 * FSR 1 / NIS upscaling.
 *
 * <p>For the duration of {@code GameRenderer.renderLevel} the main render target's colour and depth textures (and its
 * width/height) are swapped for ones of the other size (see GameRendererMixin). Everything that draws the world gets
 * its target from that same RenderTarget object - the level renderer and its frame graph, the sky renderer (which
 * keeps a reference from startup), Sodium's terrain passes, the hand and screen effects - so it all renders at that
 * resolution without further changes. Transparency targets (Fabulous graphics) are sized from the main target, so
 * they follow automatically. When renderLevel returns, the real textures are put back and the image is scaled into
 * them:
 * <ul>
 *   <li>SSAA: ssaa_downsample.fsh averages each window pixel's footprint (area-weighted box filter, linear light).</li>
 *   <li>Upscaling: the post-processing anti-aliasing (FXAA / SMAA 1x / CMAA2) runs first, on the low-resolution
 *       image, as FSR and NIS expect anti-aliased input; then fsr_easu.fsh or nis_scaler.fsh scales it up. FSR 1's
 *       sharpening pass is RCAS, which runs later at full resolution with the other finished-image filters.</li>
 * </ul>
 * The GUI is drawn afterwards at the window's resolution, so text stays pixel-sharp.
 *
 * <p>Minecraft's line shader sizes lines from the window size, so block outlines keep their on-screen width. Never runs
 * while an Iris shader pack is active (the pack draws the world through its own pipeline and targets).
 */
public final class BetterAliasRenderScale {
    private static final Logger LOGGER = LoggerFactory.getLogger("better-alias");
    private static final SystemToast.SystemToastId REDUCED_TOAST = new SystemToast.SystemToastId(10000L);

    private static RenderPipeline downsamplePipeline;
    private static RenderPipeline easuPipeline;
    private static RenderPipeline nisPipeline;
    private static boolean pipelinesFailed;
    private static MappableRingBuffer configBuffer;

    /** The colour + depth textures the world is drawn into (larger for SSAA, smaller for upscaling). */
    private static RenderTarget worldTarget;

    /** After running out of video memory, SSAA doesn't try this scale or above again until the setting changes. */
    private static SsaaScale failedScale;
    private static SsaaScale lastRequestedScale;
    private static boolean gaveUp;

    // --- this frame ---
    private static boolean upscalingThisFrame;
    private static boolean postEffectApplied;
    private static int lastWorldWidth;
    private static int lastWorldHeight;

    // --- the main target's own textures while the other ones are swapped in ---
    private static RenderTarget swappedTarget;
    private static GpuTexture savedColor;
    private static GpuTextureView savedColorView;
    private static GpuTexture savedDepth;
    private static GpuTextureView savedDepthView;
    private static int savedWidth;
    private static int savedHeight;

    private BetterAliasRenderScale() {
    }

    private static boolean ssaaSelected() {
        return BetterAliasConfig.getMode() == AntiAliasMode.SSAA;
    }

    private static boolean upscalingSelected() {
        return BetterAliasConfig.upscalingApplies(BetterAliasConfig.getMode(), BetterAliasConfig.getUpscaler());
    }

    /** Runs when SSAA or upscaling is selected and no Iris shader pack is active. */
    public static boolean isEnabled() {
        if (BetterAliasConfig.isShaderActive() || pipelinesFailed) {
            return false;
        }
        return (ssaaSelected() && !gaveUp) || upscalingSelected();
    }

    /**
     * Size to render at for a window of {@code width} x {@code height}, scaled by {@code scale} per axis. Above 1 it never
     * goes past the GPU's largest texture size (the scale is reduced for both axes then, keeping the aspect ratio);
     * below 1 it is at least one pixel.
     */
    static int[] renderSize(int width, int height, float scale, int maxTextureSize) {
        if (scale < 1.0f) {
            return new int[] {Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
        }
        float limited = Math.min(scale, Math.min((float) maxTextureSize / width, (float) maxTextureSize / height));
        limited = Math.max(limited, 1.0f);
        int renderWidth = Math.min(maxTextureSize, Math.max(width, Math.round(width * limited)));
        int renderHeight = Math.min(maxTextureSize, Math.max(height, Math.round(height * limited)));
        return new int[] {renderWidth, renderHeight};
    }

    /** Resolution the world was last drawn at (for the performance readout); the window size when not scaled. */
    public static int[] lastWorldSize() {
        return new int[] {lastWorldWidth, lastWorldHeight};
    }

    /**
     * True once per frame if the post-processing anti-aliasing already ran on the low-resolution image (upscaling), so
     * BetterAliasRenderer must not run it again.
     */
    public static boolean consumePostEffectApplied() {
        boolean applied = postEffectApplied;
        postEffectApplied = false;
        return applied;
    }

    /** Start of GameRenderer.renderLevel: point the main target at the world-resolution textures. */
    public static void beginLevel(RenderTarget mainTarget) {
        if (swappedTarget != null) {
            // renderLevel didn't finish last time (an exception); put the real textures back first
            restore();
        }
        postEffectApplied = false;
        upscalingThisFrame = false;
        lastWorldWidth = mainTarget.width;
        lastWorldHeight = mainTarget.height;
        SsaaScale requested = BetterAliasConfig.getSsaaScale();
        if (requested != lastRequestedScale) {
            // A new setting gets a fresh try, even if a larger one ran out of memory before
            lastRequestedScale = requested;
            failedScale = null;
            gaveUp = false;
        }
        if (!isEnabled() || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return;
        }

        GpuDevice device = RenderSystem.getDevice();
        if (!ensurePipelines(device)) {
            return;
        }
        if (ssaaSelected()) {
            if (!ensureSupersampledTarget(device, mainTarget.width, mainTarget.height, requested)) {
                return;
            }
        } else {
            int[] size = renderSize(mainTarget.width, mainTarget.height, BetterAliasConfig.getRenderScale().factor, Integer.MAX_VALUE);
            if (!ensureWorldTarget(size[0], size[1])) {
                return;
            }
            upscalingThisFrame = true;
        }

        RenderTargetAccessor main = (RenderTargetAccessor) mainTarget;
        RenderTargetAccessor world = (RenderTargetAccessor) worldTarget;
        savedColor = main.betterAlias$getColorTexture();
        savedColorView = main.betterAlias$getColorTextureView();
        savedDepth = main.betterAlias$getDepthTexture();
        savedDepthView = main.betterAlias$getDepthTextureView();
        savedWidth = mainTarget.width;
        savedHeight = mainTarget.height;

        main.betterAlias$setColorTexture(world.betterAlias$getColorTexture());
        main.betterAlias$setColorTextureView(world.betterAlias$getColorTextureView());
        main.betterAlias$setDepthTexture(world.betterAlias$getDepthTexture());
        main.betterAlias$setDepthTextureView(world.betterAlias$getDepthTextureView());
        mainTarget.width = worldTarget.width;
        mainTarget.height = worldTarget.height;
        swappedTarget = mainTarget;
        lastWorldWidth = worldTarget.width;
        lastWorldHeight = worldTarget.height;
    }

    /**
     * End of GameRenderer.renderLevel: put the main target's own textures back and scale the world image into them.
     * Also called from the main post-processing hook as a safety net (does nothing if nothing is swapped).
     */
    @SuppressWarnings("deprecation") // PostChain.process: see BetterAliasRenderer
    public static void endLevel(Minecraft minecraft, GraphicsResourceAllocator allocator) {
        RenderTarget mainTarget = swappedTarget;
        if (mainTarget == null) {
            return;
        }
        ProfilerFiller profiler = Profiler.get();
        profiler.push("better_alias_render_scale");
        BetterAliasGpuTimer.begin();

        if (upscalingThisFrame) {
            // Anti-alias the low-resolution image before upscaling it, as FSR and NIS expect
            Identifier postEffect = BetterAliasConfig.activePostEffect();
            if (postEffect != null) {
                PostChain chain = minecraft.getShaderManager().getPostChain(postEffect, LevelTargetBundle.MAIN_TARGETS);
                if (chain != null) {
                    chain.process(mainTarget, allocator);
                }
                postEffectApplied = true;
            }
        }
        restore();

        Upscaler upscaler = BetterAliasConfig.getUpscaler();
        RenderPipeline pipeline = !upscalingThisFrame ? downsamplePipeline : upscaler == Upscaler.NIS ? nisPipeline : easuPipeline;
        try (GpuBufferSlice.MappedView view = configBuffer.currentBuffer().map(false, true)) {
            Std140Builder config = Std140Builder.intoBuffer(view.data());
            float scaleX = (float) worldTarget.width / mainTarget.width;
            float scaleY = (float) worldTarget.height / mainTarget.height;
            if (!upscalingThisFrame) {
                config.putVec4(scaleX, scaleY, 0.0f, 0.0f);
            } else if (upscaler == Upscaler.NIS) {
                float[] sharpness = nisSharpness(BetterAliasConfig.getNisSharpness() / 100.0f);
                config.putVec4(sharpness[0], sharpness[1], sharpness[2], sharpness[3]).putVec4(scaleX, scaleY, 0.0f, 0.0f);
            } else {
                float[] con0 = easuCon0(worldTarget.width, worldTarget.height, mainTarget.width, mainTarget.height);
                config.putVec4(con0[0], con0[1], con0[2], con0[3]);
            }
        }
        String uniformName = !upscalingThisFrame ? "SsaaConfig" : upscaler == Upscaler.NIS ? "NisConfig" : "EasuConfig";
        FilterMode filter = upscalingThisFrame && upscaler == Upscaler.NIS ? FilterMode.LINEAR : FilterMode.NEAREST;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "better-alias render scale", mainTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(uniformName, configBuffer.currentBuffer());
            pass.bindTexture("InSampler", worldTarget.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(filter));
            pass.draw(3, 1, 0, 0);
        }
        configBuffer.rotate();

        BetterAliasGpuTimer.end();
        profiler.pop();
    }

    /** FsrEasuCon's con0 (the other constants of the reference are only needed for its textureGather taps). */
    static float[] easuCon0(float inputWidth, float inputHeight, float outputWidth, float outputHeight) {
        return new float[] {
                inputWidth / outputWidth,
                inputHeight / outputHeight,
                0.5f * inputWidth / outputWidth - 0.5f,
                0.5f * inputHeight / outputHeight - 0.5f};
    }

    /**
     * NVScalerUpdateConfig's sharpness-dependent constants (SDR) for a slider value 0..1:
     * {kSharpStrengthMin, kSharpStrengthScale, kSharpLimitMin, kSharpLimitScale}.
     */
    static float[] nisSharpness(float sharpness) {
        sharpness = Math.max(0.0f, Math.min(1.0f, sharpness));
        float slider = sharpness - 0.5f;
        float maxScale = slider >= 0.0f ? 1.25f : 1.75f;
        float minScale = slider >= 0.0f ? 1.25f : 1.0f;
        float limitScale = slider >= 0.0f ? 1.25f : 1.0f;
        float strengthMin = Math.max(0.0f, 0.4f + slider * minScale * 1.2f);
        float strengthMax = 1.6f + slider * maxScale * 1.8f;
        float limitMin = Math.max(0.1f, 0.14f + slider * limitScale * 0.32f);
        float limitMax = 0.5f + slider * limitScale * 0.6f;
        return new float[] {strengthMin, strengthMax - strengthMin, limitMin, limitMax - limitMin};
    }

    private static void restore() {
        RenderTargetAccessor main = (RenderTargetAccessor) swappedTarget;
        main.betterAlias$setColorTexture(savedColor);
        main.betterAlias$setColorTextureView(savedColorView);
        main.betterAlias$setDepthTexture(savedDepth);
        main.betterAlias$setDepthTextureView(savedDepthView);
        swappedTarget.width = savedWidth;
        swappedTarget.height = savedHeight;
        swappedTarget = null;
        savedColor = null;
        savedColorView = null;
        savedDepth = null;
        savedDepthView = null;
    }

    /** Frees the world-resolution textures while neither SSAA nor upscaling is selected. */
    public static void releaseIfUnused() {
        if (worldTarget != null && swappedTarget == null && !ssaaSelected() && !upscalingSelected()) {
            worldTarget.destroyBuffers();
            worldTarget = null;
        }
    }

    /** (Re)creates the world target at this size. Returns false if the GPU ran out of memory. */
    private static boolean ensureWorldTarget(int width, int height) {
        if (worldTarget != null && worldTarget.width == width && worldTarget.height == height) {
            return true;
        }
        try {
            if (worldTarget == null) {
                worldTarget = new RenderTarget("better-alias world", true, GpuFormat.RGBA8_UNORM) {
                };
            } else {
                worldTarget.destroyBuffers();
            }
            worldTarget.createBuffers(width, height);
            return true;
        } catch (GpuOutOfMemoryException | IllegalArgumentException e) {
            worldTarget.destroyBuffers(); // free whatever part was allocated
            worldTarget = null;
            LOGGER.warn("Could not allocate the {}x{} world target", width, height, e);
            return false;
        }
    }

    /**
     * SSAA's large target. If the GPU runs out of memory, steps down through the smaller scales (with a one-time toast);
     * returns false if even the smallest doesn't fit.
     */
    private static boolean ensureSupersampledTarget(GpuDevice device, int width, int height, SsaaScale requested) {
        int maxTextureSize = device.getDeviceInfo().limits().maxTextureSize();
        SsaaScale[] scales = SsaaScale.values();
        for (int i = requested.ordinal(); i >= 0; i--) {
            SsaaScale scale = scales[i];
            if (failedScale != null && scale.ordinal() >= failedScale.ordinal()) {
                continue;
            }
            int[] size = renderSize(width, height, scale.factor, maxTextureSize);
            if (size[0] <= width && size[1] <= height) {
                return false; // nothing to gain (window already at the texture size limit)
            }
            boolean existed = worldTarget != null && worldTarget.width == size[0] && worldTarget.height == size[1];
            if (ensureWorldTarget(size[0], size[1])) {
                if (!existed && scale != requested) {
                    showReducedToast(requested, scale);
                }
                return true;
            }
            failedScale = scale;
            LOGGER.warn("Not enough video memory for SSAA at {}; trying a smaller scale", scale);
        }
        gaveUp = true;
        LOGGER.error("Not enough video memory for SSAA at any scale; SSAA is off until the setting is changed");
        showReducedToast(requested, null);
        return false;
    }

    private static void showReducedToast(SsaaScale requested, SsaaScale used) {
        Minecraft minecraft = Minecraft.getInstance();
        Component message = used == null
                ? Component.translatable("better-alias.toast.ssaa_memory.none", requested.displayName())
                : Component.translatable("better-alias.toast.ssaa_memory.reduced", requested.displayName(), used.displayName());
        SystemToast.addOrUpdate(minecraft.gui.toastManager(), REDUCED_TOAST, Component.translatable("better-alias.toast.ssaa_memory.title"), message);
    }

    private static RenderPipeline pipeline(String shader, String uniform, BindGroupLayout.Builder layout) {
        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("better-alias", "pipeline/" + shader))
                .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("better-alias", "post/" + shader))
                .withBindGroupLayout(layout.withSampler("InSampler").withUniform(uniform, UniformType.UNIFORM_BUFFER).build())
                .build();
    }

    private static boolean ensurePipelines(GpuDevice device) {
        if (downsamplePipeline == null) {
            downsamplePipeline = pipeline("ssaa_downsample", "SsaaConfig", BindGroupLayout.builder());
            easuPipeline = pipeline("fsr_easu", "EasuConfig", BindGroupLayout.builder());
            nisPipeline = pipeline("nis_scaler", "NisConfig", BindGroupLayout.builder());
            // Large enough for the biggest block (NisConfig: 2 vec4)
            configBuffer = new MappableRingBuffer(() -> "better-alias RenderScaleConfig", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 32);
        }
        // Cached by the device; after a resource reload (F3+T) this recompiles from the reloaded shader files.
        if (!device.precompilePipeline(downsamplePipeline).isValid() || !device.precompilePipeline(easuPipeline).isValid()
                || !device.precompilePipeline(nisPipeline).isValid()) {
            pipelinesFailed = true;
            LOGGER.error("Failed to compile the SSAA/upscaling shaders; SSAA and upscaling are disabled until the game restarts. Check the log above for the shader error.");
            return false;
        }
        return true;
    }

    /** Current window size and the GPU's texture limit, or null if there is no window/device yet. */
    private static int[] windowAndLimit() {
        try {
            var window = Minecraft.getInstance().getWindow();
            return new int[] {window.getWidth(), window.getHeight(), RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Tooltip for an SSAA scale, shared by both settings screens, with the resolution it renders at for this window. */
    public static MutableComponent ssaaScaleTooltip(SsaaScale scale) {
        MutableComponent tooltip = Component.translatable("better-alias.options.ssaa_scale.tooltip." + scale.name().toLowerCase(Locale.ROOT));
        int[] info = windowAndLimit();
        if (info != null) {
            int[] size = renderSize(info[0], info[1], scale.factor, info[2]);
            tooltip.append("\n\n").append(Component.translatable("better-alias.options.render_resolution", info[0], info[1], size[0], size[1]));
            if (renderSize(info[0], info[1], scale.factor, Integer.MAX_VALUE)[0] > size[0]) {
                tooltip.append("\n").append(Component.translatable("better-alias.options.ssaa_scale.limited").withStyle(ChatFormatting.YELLOW));
            }
        }
        return tooltip.append("\n\n").append(Component.translatable("better-alias.options.ssaa_scale.only_ssaa").withStyle(ChatFormatting.GRAY));
    }

    /** Tooltip for an upscaling render scale, with the resolution it renders at for this window. */
    public static MutableComponent renderScaleTooltip(BetterAliasConfig.RenderScale scale) {
        MutableComponent tooltip = Component.translatable("better-alias.options.render_scale.tooltip");
        int[] info = windowAndLimit();
        if (info != null) {
            int[] size = renderSize(info[0], info[1], scale.factor, info[2]);
            tooltip.append("\n\n").append(Component.translatable("better-alias.options.render_resolution", info[0], info[1], size[0], size[1]));
        }
        return tooltip.append("\n\n").append(Component.translatable("better-alias.options.upscaling.only").withStyle(ChatFormatting.GRAY));
    }
}
