package betteralias;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * AMD FidelityFX RCAS sharpening (from FSR 1), applied to the finished image after whichever anti-aliasing ran.
 *
 * <p>Called from {@link BetterAliasRenderer} once per frame, after the post-processing anti-aliasing and before the GUI,
 * so it sharpens the world, hand and entity outlines but never the HUD. The amount is a runtime setting, so this drives
 * blaze3d directly rather than through a post-chain JSON (whose uniforms are fixed): the frame is copied to a scratch
 * texture and rcas.fsh writes the sharpened result back into the main target.
 */
public final class BetterAliasRcas {
    private static final Logger LOGGER = LoggerFactory.getLogger("better-alias");
    private static final int CONFIG_SIZE = 16;

    private static RenderPipeline pipeline;
    private static boolean pipelineFailed;
    private static MappableRingBuffer configBuffer;

    /** Copy of the frame RCAS reads from (a pass can't read the texture it writes). */
    private static GpuTexture inputTexture;
    private static GpuTextureView inputView;

    private BetterAliasRcas() {
    }

    /**
     * Whether RCAS runs this frame: turned on, not TAA (which has its own CAS sharpening), and like the post-processing
     * anti-aliasing it pauses while an Iris shader pack is active unless "Keep With Shader Packs" is on. Not used with NIS
     * upscaling either, which has its own sharpening.
     */
    public static boolean isActive() {
        return BetterAliasConfig.getRcasSharpness() > 0
                && BetterAliasConfig.rcasAppliesTo(BetterAliasConfig.getMode(), BetterAliasConfig.getUpscaler())
                && !pipelineFailed
                && (!BetterAliasConfig.isShaderActive() || BetterAliasConfig.isKeepWithShaderPacks());
    }

    /**
     * FsrRcasCon's sharpness scale (con.x) for a slider value: 100% = 1.0, FSR's maximum (0 stops); 50% = 0.5 (1 stop).
     * The effect scales linearly with it, so the slider feels even.
     */
    static float sharpness(int percent) {
        return Math.max(0, Math.min(100, percent)) / 100.0f;
    }

    /** Sharpens the main target in place. Does nothing unless {@link #isActive()}. */
    public static void apply(RenderTarget mainTarget) {
        if (!isActive() || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        if (!ensurePipeline(device)) {
            return;
        }

        ProfilerFiller profiler = Profiler.get();
        profiler.push("better_alias_rcas");
        int width = mainTarget.width;
        int height = mainTarget.height;
        ensureInput(device, width, height);

        CommandEncoder encoder = device.createCommandEncoder();
        encoder.copyTextureToTexture(mainTarget.getColorTexture(), inputTexture, 0, 0, 0, 0, 0, width, height);
        try (GpuBufferSlice.MappedView view = configBuffer.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(sharpness(BetterAliasConfig.getRcasSharpness()), 0.0f, 0.0f, 0.0f);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "better-alias RCAS", mainTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("RcasConfig", configBuffer.currentBuffer());
            pass.bindTexture("InSampler", inputView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        configBuffer.rotate();
        profiler.pop();
    }

    /** Frees the scratch texture while RCAS is off. */
    public static void releaseIfUnused() {
        if (inputTexture != null && !isActive()) {
            closeInput();
        }
    }

    private static void ensureInput(GpuDevice device, int width, int height) {
        if (inputTexture != null && inputTexture.getWidth(0) == width && inputTexture.getHeight(0) == height) {
            return;
        }
        closeInput();
        inputTexture = device.createTexture(() -> "better-alias RCAS input",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        inputView = device.createTextureView(inputTexture);
    }

    private static void closeInput() {
        if (inputView != null) {
            inputView.close();
            inputView = null;
        }
        if (inputTexture != null) {
            inputTexture.close();
            inputTexture = null;
        }
    }

    private static boolean ensurePipeline(GpuDevice device) {
        if (pipeline == null) {
            pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("better-alias", "pipeline/rcas"))
                    .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("better-alias", "post/rcas"))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withSampler("InSampler")
                            .withUniform("RcasConfig", UniformType.UNIFORM_BUFFER)
                            .build())
                    .build();
            configBuffer = new MappableRingBuffer(() -> "better-alias RcasConfig", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, CONFIG_SIZE);
        }
        // Cached by the device; after a resource reload (F3+T) this recompiles from the reloaded shader file.
        if (!device.precompilePipeline(pipeline).isValid()) {
            pipelineFailed = true;
            LOGGER.error("Failed to compile the RCAS shader; RCAS sharpening is disabled until the game restarts. Check the log above for the shader error.");
            return false;
        }
        return true;
    }
}
