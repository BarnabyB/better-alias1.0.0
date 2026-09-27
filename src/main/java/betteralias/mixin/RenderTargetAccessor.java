package betteralias.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets SSAA and upscaling point the main render target at other-size textures while the world is drawn (see BetterAliasRenderScale). The
 * texture fields are protected; width and height are already public.
 */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
    @Accessor("colorTexture")
    GpuTexture betterAlias$getColorTexture();

    @Accessor("colorTexture")
    void betterAlias$setColorTexture(GpuTexture texture);

    @Accessor("colorTextureView")
    GpuTextureView betterAlias$getColorTextureView();

    @Accessor("colorTextureView")
    void betterAlias$setColorTextureView(GpuTextureView view);

    @Accessor("depthTexture")
    GpuTexture betterAlias$getDepthTexture();

    @Accessor("depthTexture")
    void betterAlias$setDepthTexture(GpuTexture texture);

    @Accessor("depthTextureView")
    GpuTextureView betterAlias$getDepthTextureView();

    @Accessor("depthTextureView")
    void betterAlias$setDepthTextureView(GpuTextureView view);
}
