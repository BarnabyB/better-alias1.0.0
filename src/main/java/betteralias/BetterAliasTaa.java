package betteralias;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Temporal anti-aliasing with AMD CAS sharpening.
 *
 * <p>Unlike the other modes this can't be a JSON post chain: it needs a sub-pixel camera jitter every frame, a history
 * image kept between frames, and per-frame camera matrices. So it drives blaze3d directly (render pipelines, textures,
 * render passes, uniform buffers), which still works on both the OpenGL and Vulkan backends.
 *
 * <p>Per frame (see GameRendererMixin):
 * <ol>
 *   <li>{@link #jitterLevelProjection}: at the start of GameRenderer.renderLevel, the level projection is offset by a
 *       sub-pixel amount from a Halton(2,3) sequence. It's the same matrix Sodium captures for terrain, so everything in
 *       the world (terrain, entities, sky, clouds, particles) is jittered consistently.</li>
 *   <li>{@link #resolve}: after the level is drawn but before the held item/hand: taa_resolve.fsh blends this frame into
 *       the history (RGBA16F, linear colour) using depth reprojection, then cas.fsh sharpens it back into the main
 *       target. The hand and screen overlays are drawn afterwards, so they stay crisp and never ghost.</li>
 * </ol>
 *
 * <p>Never runs while an Iris shader pack is active (even with "Keep With Shader Packs"): packs jitter the camera for
 * their own TAA, and two jitter sources would fight.
 */
public final class BetterAliasTaa {
    private static final Logger LOGGER = LoggerFactory.getLogger("better-alias");

    /** Weight of the new frame in the history blend (~10 frames of accumulation). */
    private static final float CURRENT_FRAME_WEIGHT = 0.1f;
    /** Jitter sequence length (Halton 2,3 points 1..8). */
    private static final int JITTER_PHASES = 8;
    /** A camera jump further than this (teleport, respawn) starts a fresh history. Also used by SMAA T2x/4x. */
    static final double MAX_CAMERA_JUMP = 32.0;
    /** A gap in world rendering longer than this (menus, loading another world) starts a fresh history. */
    static final long MAX_FRAME_GAP_NANOS = 250_000_000L;

    private static final int TAA_CONFIG_SIZE = 3 * 64 + 2 * 16; // 3 mat4 + 2 vec4 (std140)
    private static final int CAS_CONFIG_SIZE = 16;

    private static RenderPipeline resolvePipeline;
    private static RenderPipeline casPipeline;
    private static boolean pipelinesFailed;
    private static boolean warnedFallback;

    private static final GpuTexture[] historyTextures = new GpuTexture[2];
    private static final GpuTextureView[] historyViews = new GpuTextureView[2];
    private static int historyWidth;
    private static int historyHeight;
    private static int historyIndex;

    private static MappableRingBuffer taaConfigBuffer;
    private static MappableRingBuffer casConfigBuffer;

    // --- per-frame state ---
    private static long frameIndex;
    private static long lastResolvedFrame = Long.MIN_VALUE;
    private static long lastResolvedNanos;
    private static boolean activeThisFrame;
    private static boolean resolvedThisFrame;
    private static float jitterPixelsX;
    private static float jitterPixelsY;
    private static float jitterNdcX;
    private static float jitterNdcY;
    /** The level projection of this frame (jittered). renderLevel keeps mutating it (view bobbing, nausea) after we see it. */
    private static Matrix4f levelProjection;

    // --- previous frame ---
    private static final Matrix4f previousViewProjection = new Matrix4f();
    private static Vec3 previousCameraPos;

    private BetterAliasTaa() {
    }

    /** TAA runs when it's the selected mode and no Iris shader pack is active. */
    public static boolean isEnabled() {
        return BetterAliasConfig.getMode() == BetterAliasConfig.AntiAliasMode.TAA && !BetterAliasConfig.isShaderActive() && !pipelinesFailed;
    }

    /**
     * Called with the level projection matrix renderLevel is about to use (a fresh copy of the camera's projection).
     * Offsets it by this frame's sub-pixel jitter in place and remembers it for the resolve pass.
     */
    public static Matrix4f jitterLevelProjection(Matrix4f projection, int width, int height) {
        frameIndex++;
        resolvedThisFrame = false;
        activeThisFrame = isEnabled() && width > 0 && height > 0;
        levelProjection = projection;
        if (!activeThisFrame) {
            return projection;
        }

        int phase = (int) (frameIndex % JITTER_PHASES) + 1;
        jitterPixelsX = halton(phase, 2) - 0.5f;
        jitterPixelsY = halton(phase, 3) - 0.5f;
        jitterNdcX = 2.0f * jitterPixelsX / width;
        jitterNdcY = 2.0f * jitterPixelsY / height;

        // projection' = T(jitter) * projection: shifts every clip-space position by the jitter after the perspective
        // divide. renderLevel's later bobbing/nausea transforms multiply on the right, so the shift stays exact.
        return projection.set(new Matrix4f().translation(jitterNdcX, jitterNdcY, 0.0f).mul(projection));
    }

    /**
     * Resolves this frame into the history and sharpens it back into the main target. Called before the hand is drawn;
     * also called as a fallback from the main post-processing hook if that point was skipped (e.g. another mod
     * redirected it), in which case the hand is included.
     */
    public static void resolve(RenderTarget mainTarget, CameraRenderState camera, boolean fallback) {
        if (!activeThisFrame || resolvedThisFrame || levelProjection == null) {
            return;
        }
        resolvedThisFrame = true;
        if (fallback && !warnedFallback) {
            warnedFallback = true;
            LOGGER.warn("TAA could not run before the hand is drawn (another mod may have changed GameRenderer.renderLevel); "
                    + "running it after instead. The hand will be included and camera-movement reprojection is less accurate.");
        }

        GpuDevice device = RenderSystem.getDevice();
        if (!ensurePipelines(device)) {
            return;
        }

        ProfilerFiller profiler = Profiler.get();
        profiler.push("better_alias_taa");
        BetterAliasGpuTimer.begin();

        int width = mainTarget.width;
        int height = mainTarget.height;
        boolean resized = ensureHistory(device, width, height);

        Vec3 cameraPos = camera.pos;
        long now = System.nanoTime();
        boolean historyValid = !resized
                && lastResolvedFrame == frameIndex - 1
                && now - lastResolvedNanos < MAX_FRAME_GAP_NANOS
                && previousCameraPos != null
                && previousCameraPos.distanceToSqr(cameraPos) < MAX_CAMERA_JUMP * MAX_CAMERA_JUMP;
        float deltaX = historyValid ? (float) (cameraPos.x - previousCameraPos.x) : 0.0f;
        float deltaY = historyValid ? (float) (cameraPos.y - previousCameraPos.y) : 0.0f;
        float deltaZ = historyValid ? (float) (cameraPos.z - previousCameraPos.z) : 0.0f;
        boolean zZeroToOne = device.getDeviceInfo().isZZeroToOne();

        Matrix4f viewProjection = new Matrix4f();
        try (GpuBufferSlice.MappedView view = taaConfigBuffer.currentBuffer().map(false, true)) {
            putTaaConfig(Std140Builder.intoBuffer(view.data()), levelProjection, camera.viewRotationMatrix,
                    historyValid, deltaX, deltaY, deltaZ, zZeroToOne, viewProjection);
        }
        try (GpuBufferSlice.MappedView view = casConfigBuffer.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(casPeak(BetterAliasConfig.getTaaSharpness()), 0.0f, 0.0f, 0.0f);
        }

        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        int read = historyIndex;
        int write = 1 - historyIndex;
        CommandEncoder encoder = device.createCommandEncoder();

        // 1. temporal resolve: main colour + depth + history -> new history
        try (RenderPass pass = encoder.createRenderPass(() -> "better-alias TAA resolve", historyViews[write], Optional.empty())) {
            pass.setPipeline(resolvePipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("TaaConfig", taaConfigBuffer.currentBuffer());
            pass.bindTexture("CurrentSampler", mainTarget.getColorTextureView(), nearest);
            pass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), nearest);
            pass.bindTexture("HistorySampler", historyViews[read], linear);
            pass.draw(3, 1, 0, 0);
        }

        // 2. CAS: new history -> main colour
        try (RenderPass pass = encoder.createRenderPass(() -> "better-alias CAS", mainTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(casPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("CasConfig", casConfigBuffer.currentBuffer());
            pass.bindTexture("InSampler", historyViews[write], nearest);
            pass.draw(3, 1, 0, 0);
        }

        taaConfigBuffer.rotate();
        casConfigBuffer.rotate();
        historyIndex = write;
        lastResolvedFrame = frameIndex;
        lastResolvedNanos = now;
        previousViewProjection.set(viewProjection);
        previousCameraPos = cameraPos;

        BetterAliasGpuTimer.end();
        profiler.pop();
    }

    /**
     * Writes the TaaConfig uniform block (std140, matches taa_resolve.fsh) for this frame, and returns this frame's
     * unjittered view-projection in {@code outViewProjection} (next frame's "previous"). No GPU access, so it can be
     * tested on its own.
     */
    static void putTaaConfig(Std140Builder out, Matrix4f levelProjection, Matrix4f viewRotation, boolean historyValid,
                             float cameraDeltaX, float cameraDeltaY, float cameraDeltaZ, boolean zZeroToOne,
                             Matrix4f outViewProjection) {
        // Positions are camera-relative (the view matrix is rotation only), like everything Minecraft renders.
        Matrix4f inverseJitteredViewProjection = new Matrix4f(levelProjection).mul(viewRotation).invert();
        // The level projection is T(jitter) * P * (bobbing/nausea); undo T to get the camera without jitter.
        outViewProjection.translation(-jitterNdcX, -jitterNdcY, 0.0f).mul(levelProjection).mul(viewRotation);
        out.putMat4f(inverseJitteredViewProjection)
                .putMat4f(outViewProjection)
                .putMat4f(historyValid ? previousViewProjection : outViewProjection)
                .putVec4(cameraDeltaX, cameraDeltaY, cameraDeltaZ, zZeroToOne ? 1.0f : 0.0f)
                .putVec4(historyValid ? 1.0f : 0.0f, CURRENT_FRAME_WEIGHT, jitterPixelsX, jitterPixelsY);
    }

    /** Frees the history textures (~16 MB each at 1080p) while TAA isn't selected. */
    public static void releaseIfUnused() {
        if (historyTextures[0] != null && BetterAliasConfig.getMode() != BetterAliasConfig.AntiAliasMode.TAA) {
            closeHistory();
            lastResolvedFrame = Long.MIN_VALUE;
        }
    }

    /** CasSetup: peak = -1 / lerp(8, 5, sharpness). 0% turns sharpening off entirely (plain copy). */
    static float casPeak(int sharpnessPercent) {
        if (sharpnessPercent <= 0) {
            return 0.0f;
        }
        float sharpness = Math.min(sharpnessPercent, 100) / 100.0f;
        return -1.0f / (8.0f + (5.0f - 8.0f) * sharpness);
    }

    static float halton(int index, int base) {
        float f = 1.0f;
        float result = 0.0f;
        while (index > 0) {
            f /= base;
            result += f * (index % base);
            index /= base;
        }
        return result;
    }

    private static boolean ensurePipelines(GpuDevice device) {
        if (resolvePipeline == null) {
            resolvePipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("better-alias", "pipeline/taa_resolve"))
                    .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("better-alias", "post/taa_resolve"))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withSampler("CurrentSampler")
                            .withSampler("DepthSampler")
                            .withSampler("HistorySampler")
                            .withUniform("TaaConfig", UniformType.UNIFORM_BUFFER)
                            .build())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
                    .build();
            casPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("better-alias", "pipeline/cas"))
                    .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("better-alias", "post/cas"))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withSampler("InSampler")
                            .withUniform("CasConfig", UniformType.UNIFORM_BUFFER)
                            .build())
                    .build();
            taaConfigBuffer = new MappableRingBuffer(() -> "better-alias TaaConfig", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, TAA_CONFIG_SIZE);
            casConfigBuffer = new MappableRingBuffer(() -> "better-alias CasConfig", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, CAS_CONFIG_SIZE);
        }
        // Cached by the device; after a resource reload (F3+T) this recompiles from the reloaded shader files.
        if (!device.precompilePipeline(resolvePipeline).isValid() || !device.precompilePipeline(casPipeline).isValid()) {
            pipelinesFailed = true;
            activeThisFrame = false;
            LOGGER.error("Failed to compile the TAA shaders; TAA is disabled until the game restarts. Check the log above for the shader error.");
            return false;
        }
        return true;
    }

    /** (Re)creates the ping-pong history textures at the current size. Returns true if they were (re)created. */
    private static boolean ensureHistory(GpuDevice device, int width, int height) {
        if (historyTextures[0] != null && historyWidth == width && historyHeight == height) {
            return false;
        }
        closeHistory();
        for (int i = 0; i < 2; i++) {
            int index = i;
            historyTextures[i] = device.createTexture(() -> "better-alias TAA history " + index,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
            historyViews[i] = device.createTextureView(historyTextures[i]);
        }
        historyWidth = width;
        historyHeight = height;
        return true;
    }

    private static void closeHistory() {
        for (int i = 0; i < 2; i++) {
            if (historyViews[i] != null) {
                historyViews[i].close();
                historyViews[i] = null;
            }
            if (historyTextures[i] != null) {
                historyTextures[i].close();
                historyTextures[i] = null;
            }
        }
    }
}
