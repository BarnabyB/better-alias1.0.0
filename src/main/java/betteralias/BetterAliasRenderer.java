package betteralias;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Runs the selected anti-aliasing post chain over the main render target.
 *
 * <p>Called once per frame from {@code GameRendererMixin}, after the world and hand have been drawn and before the
 * GUI. Post chains go through Mojang's blaze3d abstraction, so the same JSON + GLSL works on both the OpenGL and
 * Vulkan backends.
 */
public final class BetterAliasRenderer {
    /** Our own toast slot, shown for 10 seconds. */
    private static final SystemToast.SystemToastId SHADER_PACK_TOAST = new SystemToast.SystemToastId(10000L);

    /** The stacking warning is shown at most once per game session. */
    private static boolean warnedAboutShaderPack;

    private BetterAliasRenderer() {
    }

    // PostChain.process is marked @Deprecated by Mojang (they are slowly moving everything to the frame graph), but it
    // is exactly what vanilla's own GameRenderer.render uses for the spectator effects and menu blur in 26.2.
    @SuppressWarnings("deprecation")
    public static void render(Minecraft minecraft, RenderTarget mainTarget, GraphicsResourceAllocator allocator, CameraRenderState camera) {
        BetterAliasConfig.AntiAliasMode mode = BetterAliasConfig.getMode();

        BetterAliasDebugScreen.applySetting(minecraft);

        // SSAA / upscaling finish at the end of renderLevel; this only does anything if that didn't happen.
        BetterAliasRenderScale.endLevel(minecraft, allocator);
        BetterAliasRenderScale.releaseIfUnused();
        // With upscaling, the post-processing anti-aliasing already ran on the low-resolution image
        boolean postEffectDone = BetterAliasRenderScale.consumePostEffectApplied();

        // The temporal modes normally resolve before the hand (GameRendererMixin); this only does anything if that hook
        // was skipped.
        BetterAliasTaa.resolve(mainTarget, camera, true);
        BetterAliasTaa.releaseIfUnused();
        BetterAliasSmaaTemporal.resolve(mainTarget, camera, true);
        BetterAliasSmaaTemporal.releaseIfUnused();

        // Guard rail: while an Iris shader pack is active, the filters that work on the finished image (a
        // post-processing method and RCAS) pause, unless the player opted in to stacking them.
        boolean shaderPack = BetterAliasConfig.isShaderActive();
        Identifier postEffect = BetterAliasConfig.activePostEffect();
        boolean runPostEffect = postEffect != null && !postEffectDone && (!shaderPack || BetterAliasConfig.isKeepWithShaderPacks());
        boolean runRcas = BetterAliasRcas.isActive();
        if (shaderPack && (runPostEffect || runRcas)) {
            warnStackingOnce(minecraft, runPostEffect ? mode.displayName() : Component.translatable("better-alias.rcas.name"));
        }

        BetterAliasGpuTimer.begin();
        if (runPostEffect) {
            ProfilerFiller profiler = Profiler.get();
            profiler.push("better_alias");
            // ShaderManager caches compiled chains and clears the cache on resource reload (F3+T).
            // Returns null (and logs the error once) if the chain failed to compile.
            PostChain chain = minecraft.getShaderManager().getPostChain(postEffect, LevelTargetBundle.MAIN_TARGETS);
            if (chain != null) {
                chain.process(mainTarget, allocator);
            }
            profiler.pop();
        }

        // Sharpening goes last, on top of whichever anti-aliasing ran (including SSAA and SMAA T2x/4x).
        BetterAliasRcas.apply(mainTarget);
        BetterAliasRcas.releaseIfUnused();
        BetterAliasGpuTimer.end();
        BetterAliasGpuTimer.endFrame();
    }

    /**
     * Reminds the player, once per session, that anti-aliasing is being stacked on a shader pack. Covers the case where
     * the override was switched on long ago and a shader pack was only enabled later.
     */
    private static void warnStackingOnce(Minecraft minecraft, Component what) {
        if (warnedAboutShaderPack) {
            return;
        }
        warnedAboutShaderPack = true;
        SystemToast.addOrUpdate(
                minecraft.gui.toastManager(),
                SHADER_PACK_TOAST,
                Component.translatable("better-alias.toast.shader_pack.title"),
                Component.translatable("better-alias.toast.shader_pack.message", what));
    }
}
