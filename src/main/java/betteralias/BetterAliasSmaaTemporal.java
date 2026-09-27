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
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import betteralias.BetterAliasConfig.AntiAliasMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * SMAA T2x and the temporal "SMAA 4x".
 *
 * <p>SMAA T2x (Jimenez et al. 2012) is SMAA 1x plus a two-position sub-pixel camera jitter: each frame is rendered at
 * a different sample position, SMAA is told where that sample sits inside the pixel (its subsample indices select the
 * matching part of the area texture), and the last two SMAA outputs are averaged. The reference SMAA 4x adds S2x on
 * top, which needs the world drawn into a 2x MSAA target; Minecraft's renderer has no multisampling, so our 4x instead
 * uses SMAA 4x's four sample positions one per frame and averages the last four frames.
 *
 * <p>Like TAA this drives blaze3d directly (camera jitter, per-frame uniforms, textures kept between frames). Per frame
 * (see GameRendererMixin):
 * <ol>
 *   <li>{@link #jitterLevelProjection}: offsets the level projection by this frame's sample position.</li>
 *   <li>{@link #resolve}, after the level and before the hand: SMAA edges, weights (with this frame's subsample
 *       indices) and blending into a ring of per-frame textures, then smaa_temporal_resolve.fsh averages this frame
 *       with the reprojected older ones back into the main target. The hand is drawn afterwards and is not included.</li>
 * </ol>
 *
 * <p>Never runs while an Iris shader pack is active (the pack's own camera jitter would fight ours).
 */
public final class BetterAliasSmaaTemporal {
    private static final Logger LOGGER = LoggerFactory.getLogger("better-alias");

    /*
     * One row per frame of the cycle: {jitter x, jitter y, subsample index x, y, z, w}.
     *
     * The jitter is how far this frame's image moves, in pixels, along the render target's texel x/y axes (both
     * backends map clip-space +y to increasing texel y, so this is the same on OpenGL and Vulkan). The subsample indices
     * tell SMAA that offset (SMAA.hlsl: x/y pick the area sub-texture for that x/y offset - 1: -0.25, 2: +0.25,
     * 3: -0.125, 4: +0.125, 5: -0.375, 6: +0.375 - and z/w the two diagonal ones). The index sets are the reference's;
     * the sign convention was checked against 16x16 supersampled scenes (vertical, horizontal and diagonal edges):
     * the opposite signs roughly halve how much of the aliasing is removed.
     */
    private static final float[][] T2X_PHASES = {
            // SMAA T2x: the reference's two diagonal positions
            {-0.25f, -0.25f, 1, 1, 1, 0},
            {0.25f, 0.25f, 2, 2, 2, 0},
    };
    private static final float[][] X4_PHASES = {
            // The four sample positions of SMAA 4x (its S2x pattern combined with its T2x jitter), with the subsample
            // indices the reference uses for each; ordered so every two consecutive frames are opposite each other.
            {-0.375f, -0.125f, 5, 3, 1, 3},
            {0.375f, 0.125f, 6, 4, 2, 4},
            {0.125f, 0.375f, 4, 6, 2, 3},
            {-0.125f, -0.375f, 3, 5, 1, 4},
    };
    /** Largest cycle; the resolve shader has this many - 1 history inputs. */
    private static final int MAX_FRAMES = 4;

    private static final int SMAA_CONFIG_SIZE = 16;
    private static final int RESOLVE_CONFIG_SIZE = 5 * 64 + 4 * 16; // 5 mat4 + 4 vec4 (std140)

    private static final Identifier AREA_TEXTURE = Identifier.fromNamespaceAndPath("better-alias", "textures/effect/smaa_area.png");
    private static final Identifier SEARCH_TEXTURE = Identifier.fromNamespaceAndPath("better-alias", "textures/effect/smaa_search.png");

    private static RenderPipeline edgesPipeline;
    private static RenderPipeline weightsPipeline;
    private static RenderPipeline blendPipeline;
    private static RenderPipeline resolvePipeline;
    private static boolean pipelinesFailed;
    private static boolean warnedFallback;

    private static MappableRingBuffer smaaConfigBuffer;
    private static MappableRingBuffer resolveConfigBuffer;

    // --- textures ---
    private static GpuTexture edgesTexture;
    private static GpuTextureView edgesView;
    private static GpuTexture weightsTexture;
    private static GpuTextureView weightsView;
    /** SMAA output of the last few frames (sRGB-encoded, like the main target). */
    private static final GpuTexture[] frameTextures = new GpuTexture[MAX_FRAMES];
    private static final GpuTextureView[] frameViews = new GpuTextureView[MAX_FRAMES];
    private static int targetsWidth;
    private static int targetsHeight;
    private static int frameCount;

    // --- what each ring slot holds ---
    private static final Matrix4f[] slotViewProjection = new Matrix4f[MAX_FRAMES];
    /** Camera position (x, y, z) each slot was rendered from; null while the slot holds nothing usable. */
    private static final double[][] slotCameraPos = new double[MAX_FRAMES][];
    private static int writeSlot;
    /** How many consecutive earlier frames are in the ring and usable (0 after a reset). */
    private static int historyFrames;

    // --- per-frame state ---
    private static long frameIndex;
    private static long lastResolvedFrame = Long.MIN_VALUE;
    private static long lastResolvedNanos;
    private static boolean activeThisFrame;
    private static boolean resolvedThisFrame;
    private static float[][] phases = T2X_PHASES;
    private static float[] phase = T2X_PHASES[0];
    private static float jitterNdcX;
    private static float jitterNdcY;
    /** The level projection of this frame (jittered). renderLevel keeps mutating it (view bobbing, nausea) after we see it. */
    private static Matrix4f levelProjection;

    static {
        for (int i = 0; i < MAX_FRAMES; i++) {
            slotViewProjection[i] = new Matrix4f();
        }
    }

    private BetterAliasSmaaTemporal() {
    }

    /** Runs when SMAA T2x or 4x is the selected mode and no Iris shader pack is active. */
    public static boolean isEnabled() {
        return phasesFor(BetterAliasConfig.getMode()) != null && !BetterAliasConfig.isShaderActive() && !pipelinesFailed;
    }

    static float[][] phasesFor(AntiAliasMode mode) {
        return switch (mode) {
            case SMAA_T2X -> T2X_PHASES;
            case SMAA_4X -> X4_PHASES;
            default -> null;
        };
    }

    /**
     * Called with the level projection matrix renderLevel is about to use (a fresh copy of the camera's projection).
     * Offsets it by this frame's sample position in place.
     */
    public static Matrix4f jitterLevelProjection(Matrix4f projection, int width, int height) {
        frameIndex++;
        resolvedThisFrame = false;
        activeThisFrame = isEnabled() && width > 0 && height > 0;
        levelProjection = projection;
        if (!activeThisFrame) {
            return projection;
        }

        phases = phasesFor(BetterAliasConfig.getMode());
        phase = phases[(int) (frameIndex % phases.length)];
        jitterNdcX = 2.0f * phase[0] / width;
        jitterNdcY = 2.0f * phase[1] / height;

        // projection' = T(jitter) * projection, exactly like TAA (see BetterAliasTaa.jitterLevelProjection)
        return projection.set(new Matrix4f().translation(jitterNdcX, jitterNdcY, 0.0f).mul(projection));
    }

    /**
     * Runs SMAA on this frame and averages it with the previous ones into the main target. Called before the hand is
     * drawn; also called as a fallback from the main post-processing hook if that point was skipped.
     */
    public static void resolve(RenderTarget mainTarget, CameraRenderState camera, boolean fallback) {
        if (!activeThisFrame || resolvedThisFrame || levelProjection == null) {
            return;
        }
        resolvedThisFrame = true;
        if (fallback && !warnedFallback) {
            warnedFallback = true;
            LOGGER.warn("SMAA T2x/4x could not run before the hand is drawn (another mod may have changed GameRenderer.renderLevel); "
                    + "running it after instead. The hand will be included and camera-movement reprojection is less accurate.");
        }

        GpuDevice device = RenderSystem.getDevice();
        if (!ensurePipelines(device)) {
            return;
        }
        AbstractTexture area = Minecraft.getInstance().getTextureManager().getTexture(AREA_TEXTURE);
        AbstractTexture search = Minecraft.getInstance().getTextureManager().getTexture(SEARCH_TEXTURE);

        ProfilerFiller profiler = Profiler.get();
        profiler.push("better_alias_smaa_temporal");
        BetterAliasGpuTimer.begin();

        int width = mainTarget.width;
        int height = mainTarget.height;
        boolean recreated = ensureTargets(device, width, height, phases.length);

        Vec3 cameraPos = camera.pos;
        long now = System.nanoTime();
        advanceRing(recreated, now, cameraPos.x, cameraPos.y, cameraPos.z);
        boolean zZeroToOne = device.getDeviceInfo().isZZeroToOne();

        try (GpuBufferSlice.MappedView view = smaaConfigBuffer.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(phase[2], phase[3], phase[4], phase[5]);
        }
        Matrix4f viewProjection = new Matrix4f();
        try (GpuBufferSlice.MappedView view = resolveConfigBuffer.currentBuffer().map(false, true)) {
            putResolveConfig(Std140Builder.intoBuffer(view.data()), levelProjection, camera.viewRotationMatrix,
                    cameraPos.x, cameraPos.y, cameraPos.z, zZeroToOne, viewProjection);
        }

        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        CommandEncoder encoder = device.createCommandEncoder();
        GpuTextureView current = frameViews[writeSlot];

        // 1. SMAA edge detection
        try (RenderPass pass = encoder.createRenderPass(() -> "better-alias SMAA edges", edgesView, Optional.empty())) {
            pass.setPipeline(edgesPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("ColorSampler", mainTarget.getColorTextureView(), nearest);
            pass.draw(3, 1, 0, 0);
        }

        // 2. SMAA blending weights, for where this frame's sample sits in the pixel
        try (RenderPass pass = encoder.createRenderPass(() -> "better-alias SMAA weights", weightsView, Optional.empty())) {
            pass.setPipeline(weightsPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("SmaaConfig", smaaConfigBuffer.currentBuffer());
            pass.bindTexture("EdgesSampler", edgesView, linear);
            pass.bindTexture("AreaSampler", area.getTextureView(), linear);
            pass.bindTexture("SearchSampler", search.getTextureView(), nearest);
            pass.draw(3, 1, 0, 0);
        }

        // 3. SMAA neighbourhood blending into this frame's ring slot
        try (RenderPass pass = encoder.createRenderPass(() -> "better-alias SMAA blend", current, Optional.empty())) {
            pass.setPipeline(blendPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("ColorSampler", mainTarget.getColorTextureView(), nearest);
            pass.bindTexture("WeightsSampler", weightsView, nearest);
            pass.draw(3, 1, 0, 0);
        }

        // 4. temporal resolve: this frame + reprojected older frames -> main colour
        try (RenderPass pass = encoder.createRenderPass(() -> "better-alias SMAA temporal resolve", mainTarget.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(resolvePipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("SmaaTemporalConfig", resolveConfigBuffer.currentBuffer());
            pass.bindTexture("CurrentSampler", current, nearest);
            pass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), nearest);
            for (int i = 1; i < MAX_FRAMES; i++) {
                // Unused inputs still need a texture bound; the shader skips them (CameraDeltaN.w = 0)
                GpuTextureView history = i < frameCount ? frameViews[slotFor(i)] : current;
                pass.bindTexture("History" + i + "Sampler", history, linear);
            }
            pass.draw(3, 1, 0, 0);
        }

        smaaConfigBuffer.rotate();
        resolveConfigBuffer.rotate();
        finishFrame(viewProjection, cameraPos.x, cameraPos.y, cameraPos.z, now);

        BetterAliasGpuTimer.end();
        profiler.pop();
    }

    /**
     * Start of a resolve: moves to the next ring slot and works out how many of the earlier frames in the ring are a
     * continuous run up to this one (anything else - a resize, a skipped frame, a pause, a teleport - starts over).
     */
    static void advanceRing(boolean recreated, long now, double cameraX, double cameraY, double cameraZ) {
        int previousSlot = writeSlot;
        boolean continuous = !recreated
                && lastResolvedFrame == frameIndex - 1
                && now - lastResolvedNanos < BetterAliasTaa.MAX_FRAME_GAP_NANOS
                && isNear(slotCameraPos[previousSlot], cameraX, cameraY, cameraZ);
        historyFrames = continuous ? Math.min(historyFrames + 1, frameCount - 1) : 0;
        writeSlot = (previousSlot + 1) % frameCount;
    }

    /** End of a resolve: remembers the camera this frame's slot was rendered with. */
    static void finishFrame(Matrix4f viewProjection, double cameraX, double cameraY, double cameraZ, long now) {
        slotViewProjection[writeSlot].set(viewProjection);
        slotCameraPos[writeSlot] = new double[] {cameraX, cameraY, cameraZ};
        lastResolvedFrame = frameIndex;
        lastResolvedNanos = now;
    }

    private static boolean isNear(double[] then, double x, double y, double z) {
        if (then == null) {
            return false;
        }
        double dx = x - then[0];
        double dy = y - then[1];
        double dz = z - then[2];
        return dx * dx + dy * dy + dz * dz < BetterAliasTaa.MAX_CAMERA_JUMP * BetterAliasTaa.MAX_CAMERA_JUMP;
    }

    /** The ring slot holding the frame from {@code framesAgo} frames ago. */
    private static int slotFor(int framesAgo) {
        return Math.floorMod(writeSlot - framesAgo, frameCount);
    }

    /**
     * Writes the SmaaTemporalConfig uniform block (std140, matches smaa_temporal_resolve.fsh) for this frame, and returns
     * this frame's unjittered view-projection in {@code outViewProjection}. No GPU access, so it can be tested on its own.
     * Expects {@link #advanceRing} to have run for this frame.
     */
    static void putResolveConfig(Std140Builder out, Matrix4f levelProjection, Matrix4f viewRotation,
                                 double cameraX, double cameraY, double cameraZ, boolean zZeroToOne, Matrix4f outViewProjection) {
        // Positions are camera-relative (the view matrix is rotation only), like everything Minecraft renders.
        Matrix4f inverseJitteredViewProjection = new Matrix4f(levelProjection).mul(viewRotation).invert();
        // The level projection is T(jitter) * P * (bobbing/nausea); undo T to get the camera without jitter.
        outViewProjection.translation(-jitterNdcX, -jitterNdcY, 0.0f).mul(levelProjection).mul(viewRotation);
        out.putMat4f(inverseJitteredViewProjection).putMat4f(outViewProjection);

        boolean[] usable = new boolean[MAX_FRAMES];
        for (int i = 1; i < MAX_FRAMES; i++) {
            usable[i] = i <= historyFrames && i < frameCount && isNear(slotCameraPos[slotFor(i)], cameraX, cameraY, cameraZ);
            out.putMat4f(usable[i] ? slotViewProjection[slotFor(i)] : outViewProjection);
        }
        for (int i = 1; i < MAX_FRAMES; i++) {
            if (usable[i]) {
                double[] then = slotCameraPos[slotFor(i)];
                out.putVec4((float) (cameraX - then[0]), (float) (cameraY - then[1]), (float) (cameraZ - then[2]), 1.0f);
            } else {
                out.putVec4(0.0f, 0.0f, 0.0f, 0.0f);
            }
        }
        out.putVec4(zZeroToOne ? 1.0f : 0.0f, 0.0f, 0.0f, 0.0f);
    }

    /** Frees the textures (~8 MB each at 1080p) while neither mode is selected. */
    public static void releaseIfUnused() {
        if (edgesTexture != null && phasesFor(BetterAliasConfig.getMode()) == null) {
            closeTargets();
            lastResolvedFrame = Long.MIN_VALUE;
        }
    }

    private static RenderPipeline postPipeline(String name, BindGroupLayout layout) {
        return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("better-alias", "pipeline/" + name))
                .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("better-alias", "post/" + name))
                .withBindGroupLayout(layout)
                .build();
    }

    private static boolean ensurePipelines(GpuDevice device) {
        if (edgesPipeline == null) {
            // The same SMAA shaders as the SMAA 1x post chain (post_effect/smaa.json)
            edgesPipeline = postPipeline("smaa_edges", BindGroupLayout.builder()
                    .withSampler("ColorSampler")
                    .build());
            weightsPipeline = postPipeline("smaa_weights", BindGroupLayout.builder()
                    .withSampler("EdgesSampler")
                    .withSampler("AreaSampler")
                    .withSampler("SearchSampler")
                    .withUniform("SmaaConfig", UniformType.UNIFORM_BUFFER)
                    .build());
            blendPipeline = postPipeline("smaa_blend", BindGroupLayout.builder()
                    .withSampler("ColorSampler")
                    .withSampler("WeightsSampler")
                    .build());
            resolvePipeline = postPipeline("smaa_temporal_resolve", BindGroupLayout.builder()
                    .withSampler("CurrentSampler")
                    .withSampler("DepthSampler")
                    .withSampler("History1Sampler")
                    .withSampler("History2Sampler")
                    .withSampler("History3Sampler")
                    .withUniform("SmaaTemporalConfig", UniformType.UNIFORM_BUFFER)
                    .build());
            smaaConfigBuffer = new MappableRingBuffer(() -> "better-alias SmaaConfig", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, SMAA_CONFIG_SIZE);
            resolveConfigBuffer = new MappableRingBuffer(() -> "better-alias SmaaTemporalConfig", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, RESOLVE_CONFIG_SIZE);
        }
        // Cached by the device; after a resource reload (F3+T) this recompiles from the reloaded shader files.
        if (!device.precompilePipeline(edgesPipeline).isValid() || !device.precompilePipeline(weightsPipeline).isValid()
                || !device.precompilePipeline(blendPipeline).isValid() || !device.precompilePipeline(resolvePipeline).isValid()) {
            pipelinesFailed = true;
            activeThisFrame = false;
            LOGGER.error("Failed to compile the SMAA T2x/4x shaders; they are disabled until the game restarts. Check the log above for the shader error.");
            return false;
        }
        return true;
    }

    /** (Re)creates the SMAA and ring textures for this size and cycle length. Returns true if they were (re)created. */
    private static boolean ensureTargets(GpuDevice device, int width, int height, int frames) {
        if (edgesTexture != null && targetsWidth == width && targetsHeight == height && frameCount == frames) {
            return false;
        }
        closeTargets();
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
        edgesTexture = device.createTexture(() -> "better-alias SMAA edges", usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        edgesView = device.createTextureView(edgesTexture);
        weightsTexture = device.createTexture(() -> "better-alias SMAA weights", usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        weightsView = device.createTextureView(weightsTexture);
        for (int i = 0; i < frames; i++) {
            int index = i;
            frameTextures[i] = device.createTexture(() -> "better-alias SMAA frame " + index, usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            frameViews[i] = device.createTextureView(frameTextures[i]);
            slotCameraPos[i] = null;
        }
        targetsWidth = width;
        targetsHeight = height;
        frameCount = frames;
        writeSlot = 0;
        historyFrames = 0;
        return true;
    }

    private static void closeTargets() {
        if (edgesView != null) {
            edgesView.close();
            edgesTexture.close();
            weightsView.close();
            weightsTexture.close();
            edgesView = null;
            edgesTexture = null;
            weightsView = null;
            weightsTexture = null;
        }
        for (int i = 0; i < MAX_FRAMES; i++) {
            if (frameViews[i] != null) {
                frameViews[i].close();
                frameViews[i] = null;
            }
            if (frameTextures[i] != null) {
                frameTextures[i].close();
                frameTextures[i] = null;
            }
            slotCameraPos[i] = null;
        }
        frameCount = 0;
    }
}
