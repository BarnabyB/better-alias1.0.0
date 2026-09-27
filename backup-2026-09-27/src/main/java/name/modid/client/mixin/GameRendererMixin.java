//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package name.modid.client.mixin;

import name.modid.client.AntiAliasConfig;
import name.modid.client.AntiAliasConfig.AntiAliasMode;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({GameRenderer.class})
public abstract class GameRendererMixin {
    @Shadow
    private boolean effectActive;
    @Unique
    private AntiAliasConfig.AntiAliasMode lastAppliedMode;

    public GameRendererMixin() {
        this.lastAppliedMode = AntiAliasMode.NONE;
    }

    @Shadow
    abstract void setPostEffect(Identifier var1);

    @Shadow
    abstract void checkEntityPostEffect(Entity var1);

    @Inject(
            method = {"tick"},
            at = {@At("TAIL")}
    )
    private void applyAATick(CallbackInfo ci) {
        if (!AntiAliasConfig.isShaderActive()) {
            AntiAliasConfig.AntiAliasMode targetMode = AntiAliasConfig.currentMode;
            if (this.lastAppliedMode != targetMode) {
                if (targetMode == AntiAliasMode.NONE) {
                    this.checkEntityPostEffect((Entity)null);
                } else {
                    this.setPostEffect(targetMode.postEffect);
                }

                this.lastAppliedMode = targetMode;
            } else if (!this.effectActive && targetMode != AntiAliasMode.NONE) {
                this.setPostEffect(targetMode.postEffect);
            }

        }
    }
}
