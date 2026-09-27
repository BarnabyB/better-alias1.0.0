package betteralias;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import betteralias.BetterAliasConfig.AntiAliasMode;
import betteralias.BetterAliasConfig.SsaaScale;
import betteralias.mixin.RenderTargetAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.MappableRingBuffer;
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
 * SSAA (supersampling): the world is drawn at a higher resolution and averaged down to the window.
 *
 * <p>For the duration of {@code GameRenderer.renderLevel} the main render target's colour and depth textures (and its
 * width/height) are swapped for larger ones (see GameRendererMixin). Everything that draws the world gets its target
 * from that same RenderTarget object - the level renderer and its frame graph, the sky renderer (which keeps a
 * reference from startup), Sodium's terrain passes, the hand and screen effects - so it all renders at the higher
 * resolution without further changes. Transparency targets (Fabulous graphics) are sized from the main target, so they
 * follow automatically. When renderLevel returns, the real textures are put back and ssaa_downsample.fsh averages the
 * large image into the main target. The GUI is then drawn at the normal resolution, so text stays pixel-sharp.
 *
 * <p>Minecraft's line shader sizes lines from the window size, so block outlines keep their usual on-screen width.
 * Never runs while an Iris shader pack is active (the pack draws the world through its own pipeline and targets).
 */
public final class BetterAliasSsaa {
    private static final Logger LOGGER = LoggerFactory.getLogger("better-alias");
    private static final SystemToast.SystemToastId REDUCED_TOAST = new SystemToast.SystemToastId(10000L);
    private static final int CONFIG_SIZE = 16;

    private static RenderPipeline downsamplePipeline;
    private static boolean pipelineFailed;
    private static MappableRingBuffer configBuffer;

    /** The large colour + depth textures the world is drawn into. */
    private static RenderTarget largeTarget;

    /** After running out of video memory, SSAA doesn't try this scale or above again until the setting changes. */
    private static SsaaScale failedScale;
    private static SsaaScale lastRequestedScale;
    private static boolean gaveUp;

    // --- the main target's own textures while the large ones are swapped in ---
    private static RenderTarget swappedTarget;
    private static GpuTexture savedColor;
    private static GpuTextureView savedColorView;
    private static GpuTexture savedDepth;
    private static GpuTextureView savedDepthView;
    private static int savedWidth;
    private static int savedHeight;

    private BetterAliasSsaa() {
    }

    /** Runs when SSAA is the selected mode and no Iris shader pack is active. */
    public static boolean isEnabled() {
        return BetterAliasConfig.getMode() == AntiAliasMode.SSAA && !BetterAliasConfig.isShaderActive() && !pipelineFailed && !gaveUp;
    }

    /**
     * Size to render at for a window of {@code width} x {@code height}: scaled by {@code scale} per axis, but never past
     * the GPU's largest texture size (the scale is reduced for both axes then, keeping the aspect ratio).
     */
    static int[] renderSize(int width, int height, float scale, int maxTextureSize) {
        float limited = Math.min(scale, Math.min((float) maxTextureSize / width, (float) maxTextureSize / height));
        limited = Math.max(limited, 1.0f);
        int renderWidth = Math.min(maxTextureSize, Math.max(width, Math.round(width * limited)));
        int renderHeight = Math.min(maxTextureSize, Math.max(height, Math.round(height * limited)));
        return new int[] {renderWidth, renderHeight};
    }

    /** Start of GameRenderer.renderLevel: point the main target at the large textures. */
    public static void beginLevel(RenderTarget mainTarget) {
        if (swappedTarget != null) {
            // renderLevel didn't finish last time (an exception); put the real textures back first
            restore();
        }
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
        if (!ensurePipeline(device) || !ensureLargeTarget(device, mainTarget.width, mainTarget.height, requested)) {
            return;
        }

        RenderTargetAccessor main = (RenderTargetAccessor) mainTarget;
        RenderTargetAccessor large = (RenderTargetAccessor) largeTarget;
        savedColor = main.betterAlias$getColorTexture();
        savedColorView = main.betterAlias$getColorTextureView();
        savedDepth = main.betterAlias$getDepthTexture();
        savedDepthView = main.betterAlias$getDepthTextureView();
        savedWidth = mainTarget.width;
        savedHeight = mainTarget.height;

        main.betterAlias$setColorTexture(large.betterAlias$getColorTexture());
        main.betterAlias$setColorTextureView(large.betterAlias$getColorTextureView());
        main.betterAlias$setDepthTexture(large.betterAlias$getDepthTexture());
        main.betterAlias$setDepthTextureView(large.betterAlias$getDepthTextureView());
        mainTarget.width = largeTarget.width;
        mainTarget.height = largeTarget.height;
        swappedTarget = mainTarget;
    }

    /**
     * End of GameRenderer.renderLevel: put the main target's own textures back and average the large image into it.
     * Also called from the main post-processing hook as a safety net (does nothing if nothing is swapped).
     */
    public static void endLevel() {
        RenderTarget mainTarget = swappedTarget;
        if (mainTarget == null) {
            return;
        }
        restore();

        ProfilerFiller profiler = Profiler.get();
        profiler.push("better_alias_ssaa");
        try (GpuBufferSlice.MappedView view = configBuffer.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(
                    (float) largeTarget.width / mainTarget.width, (float) largeTarget.height / mainTarget.height, 0.0f, 0.0f);
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "better-alias SSAA downsample", mainTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(downsamplePipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("SsaaConfig", configBuffer.currentBuffer());
            pass.bindTexture("InSampler", largeTarget.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        configBuffer.rotate();
        profiler.pop();
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

    /** Frees the large textures while SSAA isn't selected. */
    public static void releaseIfUnused() {
        if (largeTarget != null && swappedTarget == null && BetterAliasConfig.getMode() != AntiAliasMode.SSAA) {
            largeTarget.destroyBuffers();
            largeTarget = null;
        }
    }

    /**
     * Makes sure the large target exists at the right size. If the GPU runs out of memory, steps down through the
     * smaller scales (with a one-time toast); returns false if even the smallest doesn't fit.
     */
    private static boolean ensureLargeTarget(GpuDevice device, int width, int height, SsaaScale requested) {
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
            if (largeTarget != null && largeTarget.width == size[0] && largeTarget.height == size[1]) {
                return true;
            }
            try {
                if (largeTarget == null) {
                    largeTarget = new RenderTarget("better-alias SSAA", true, GpuFormat.RGBA8_UNORM) {
                    };
                } else {
                    largeTarget.destroyBuffers();
                }
                largeTarget.createBuffers(size[0], size[1]);
                if (scale != requested) {
                    showReducedToast(requested, scale);
                }
                return true;
            } catch (GpuOutOfMemoryException | IllegalArgumentException e) {
                largeTarget.destroyBuffers(); // free whatever part was allocated
                failedScale = scale;
                LOGGER.warn("Not enough video memory for SSAA at {} ({}x{}); trying a smaller scale", scale, size[0], size[1], e);
            }
        }
        largeTarget = null;
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

    private static boolean ensurePipeline(GpuDevice device) {
        if (downsamplePipeline == null) {
            downsamplePipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("better-alias", "pipeline/ssaa_downsample"))
                    .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("better-alias", "post/ssaa_downsample"))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withSampler("InSampler")
                            .withUniform("SsaaConfig", UniformType.UNIFORM_BUFFER)
                            .build())
                    .build();
            configBuffer = new MappableRingBuffer(() -> "better-alias SsaaConfig", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, CONFIG_SIZE);
        }
        // Cached by the device; after a resource reload (F3+T) this recompiles from the reloaded shader file.
        if (!device.precompilePipeline(downsamplePipeline).isValid()) {
            pipelineFailed = true;
            LOGGER.error("Failed to compile the SSAA downsample shader; SSAA is disabled until the game restarts. Check the log above for the shader error.");
            return false;
        }
        return true;
    }

    /**
     * Tooltip for an SSAA scale, shared by both settings screens: what it costs, and the resolution it would render at
     * for the current window.
     */
    public static MutableComponent scaleTooltip(SsaaScale scale) {
        MutableComponent tooltip = Component.translatable("better-alias.options.ssaa_scale.tooltip." + scale.name().toLowerCase(Locale.ROOT));
        try {
            var window = Minecraft.getInstance().getWindow();
            int width = window.getWidth();
            int height = window.getHeight();
            int maxTextureSize = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
            int[] size = renderSize(width, height, scale.factor, maxTextureSize);
            tooltip.append("\n\n").append(Component.translatable("better-alias.options.ssaa_scale.resolution", width, height, size[0], size[1]));
            if (renderSize(width, height, scale.factor, Integer.MAX_VALUE)[0] > size[0]) {
                tooltip.append("\n").append(Component.translatable("better-alias.options.ssaa_scale.limited").withStyle(ChatFormatting.YELLOW));
            }
        } catch (RuntimeException e) {
            // no window/device yet: just leave the resolution line out
        }
        return tooltip.append("\n\n").append(Component.translatable("better-alias.options.ssaa_scale.only_ssaa").withStyle(ChatFormatting.GRAY));
    }
}
