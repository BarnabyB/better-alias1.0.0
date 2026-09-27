package betteralias.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import betteralias.BetterAliasRenderer;
import betteralias.BetterAliasSmaaTemporal;
import betteralias.BetterAliasRenderScale;
import betteralias.BetterAliasTaa;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the anti-aliasing pass into {@link GameRenderer#render}.
 *
 * <p>In 26.2 the relevant part of {@code render} looks like this:
 * <pre>
 *   renderLevel(deltaTracker);          // terrain (Sodium), entities, particles, weather, hand, screen overlays
 *   tryTakeScreenshotIfNeeded();
 *   levelRenderer.doEntityOutline();    // glowing outlines composited onto the main target
 *   &gt;&gt;&gt; we run here &lt;&lt;&lt;
 *   if (postEffectId != null &amp;&amp; effectActive) { ... }   // vanilla creeper/spider/enderman spectator effects
 *   ...
 *   guiRenderer.render();               // HUD / screens, drawn on top of the anti-aliased image
 * </pre>
 *
 * <p>We anchor on the first read of {@code postEffectId} (the start of vanilla's post-effect block) rather than on
 * {@code doEntityOutline()}: it is a private field only GameRenderer touches, so another mod redirecting the outline
 * call can't make this injection fail. Sodium does not touch this method region (it only hooks
 * {@code render} at {@code GuiRenderer.render()} for its console overlay).
 *
 * <p>Running our own PostChain here, instead of borrowing vanilla's single post-effect slot, means F4/F5, spectating a
 * mob, or another mod using that slot can no longer switch the anti-aliasing off, and setting changes apply on the
 * very next frame (even while paused).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @Shadow
    @Final
    private CrossFrameResourcePool resourcePool;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    /**
     * SSAA / upscaling: while renderLevel runs, the main render target points at textures of another size, so the whole
     * world (Sodium's terrain included) and the hand are drawn at that resolution. See BetterAliasRenderScale.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void betterAlias$beginRenderScale(DeltaTracker deltaTracker, CallbackInfo ci) {
        BetterAliasRenderScale.beginLevel(this.mainRenderTarget);
    }

    /** SSAA / upscaling: put the main target's own textures back and scale the world image into it. */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void betterAlias$endRenderScale(DeltaTracker deltaTracker, CallbackInfo ci) {
        BetterAliasRenderScale.endLevel(this.minecraft, this.resourcePool);
    }

    /**
     * TAA / SMAA T2x / SMAA 4x step 1: jitter the level projection (each class only acts while its mode is selected). In renderLevel this is {@code new Matrix4f(cameraState.projectionMatrix)},
     * the copy that bobbing/nausea are applied to and that is then handed to the renderer (and captured by Sodium for
     * terrain), so the jitter reaches everything in the world. MixinExtras' ModifyExpressionValue (bundled with Fabric
     * Loader) is used instead of a Redirect so other mods can still hook the same expression.
     */
    @ModifyExpressionValue(
            method = "renderLevel",
            at = @At(value = "NEW", target = "(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;")
    )
    private Matrix4f betterAlias$jitterLevelProjection(Matrix4f projection) {
        int width = this.mainRenderTarget.width;
        int height = this.mainRenderTarget.height;
        return BetterAliasSmaaTemporal.jitterLevelProjection(BetterAliasTaa.jitterLevelProjection(projection, width, height), width, height);
    }

    /**
     * TAA / SMAA T2x / SMAA 4x step 2: resolve once the level is drawn, right before the hand. Anchored on renderLevel's
     * {@code profiler.popPush("hand")}: at that point the depth buffer still holds the world (it is cleared for the hand
     * right after). Not required: if another mod changes this part of renderLevel, the resolve falls back to the main
     * post-processing hook below (and then includes the hand).
     */
    @Inject(
            method = "renderLevel",
            at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=hand"),
            require = 0
    )
    private void betterAlias$resolveTemporalBeforeHand(DeltaTracker deltaTracker, CallbackInfo ci) {
        BetterAliasTaa.resolve(this.mainRenderTarget, this.gameRenderState.levelRenderState.cameraRenderState, false);
        BetterAliasSmaaTemporal.resolve(this.mainRenderTarget, this.gameRenderState.levelRenderState.cameraRenderState, false);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;postEffectId:Lnet/minecraft/resources/Identifier;",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0
            )
    )
    private void betterAlias$applyPostProcessAA(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        BetterAliasRenderer.render(this.minecraft, this.mainRenderTarget, this.resourcePool, this.gameRenderState.levelRenderState.cameraRenderState);
    }
}
