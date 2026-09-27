package betteralias;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionEventListener;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import betteralias.BetterAliasConfig.AntiAliasMode;
import betteralias.BetterAliasConfig.Cmaa2Quality;
import betteralias.BetterAliasConfig.PerformanceReadout;
import betteralias.BetterAliasConfig.RenderScale;
import betteralias.BetterAliasConfig.SsaaScale;
import betteralias.BetterAliasConfig.Upscaler;
import betteralias.compat.IrisCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * The YACL settings screen, opened from Mod Menu. Kept separate from {@link BetterAliasConfig} so the config class never
 * touches YACL classes (the Sodium page and the renderer don't need YACL at all).
 *
 * <p>YACL keeps changes pending until "Save Changes" is pressed. Descriptions, labels and which options are available
 * all follow the pending values, so the screen reacts as soon as something is changed, before anything is saved.
 */
public final class BetterAliasConfigScreen {
    private BetterAliasConfigScreen() {
    }

    /** The options, so each one's availability and labels can follow the others' pending (unsaved) values. */
    private static final class Options {
        Option<AntiAliasMode> mode;
        Option<Cmaa2Quality> cmaa2Quality;
        Option<Integer> taaSharpness;
        Option<SsaaScale> ssaaScale;
        Option<Integer> rcasSharpness;
        Option<Upscaler> upscaler;
        Option<RenderScale> renderScale;
        Option<Integer> nisSharpness;
        Option<Boolean> showEdges;
        Option<Boolean> keepWithShaderPacks;
        boolean refreshing;

        AntiAliasMode pendingMode() {
            return this.mode != null ? this.mode.pendingValue() : BetterAliasConfig.getMode();
        }

        Upscaler pendingUpscaler() {
            return this.upscaler != null ? this.upscaler.pendingValue() : BetterAliasConfig.getUpscaler();
        }

        int pendingRcas() {
            return this.rcasSharpness != null ? this.rcasSharpness.pendingValue() : BetterAliasConfig.getRcasSharpness();
        }

        boolean pendingKeepWithShaderPacks() {
            return this.keepWithShaderPacks != null ? this.keepWithShaderPacks.pendingValue() : BetterAliasConfig.isKeepWithShaderPacks();
        }

        /** Greys out whatever doesn't apply to the pending settings. YACL can't hide options, only disable them. */
        void refresh() {
            if (this.refreshing || this.mode == null || this.cmaa2Quality == null || this.taaSharpness == null || this.ssaaScale == null
                    || this.rcasSharpness == null || this.upscaler == null || this.renderScale == null || this.nisSharpness == null
                    || this.showEdges == null || this.keepWithShaderPacks == null) {
                return;
            }
            this.refreshing = true;
            AntiAliasMode selected = this.mode.pendingValue();
            Upscaler upscalerSelected = this.upscaler.pendingValue();
            boolean upscaling = BetterAliasConfig.upscalingApplies(selected, upscalerSelected);
            this.cmaa2Quality.setAvailable(selected == AntiAliasMode.CMAA2);
            this.taaSharpness.setAvailable(selected == AntiAliasMode.TAA);
            this.ssaaScale.setAvailable(selected == AntiAliasMode.SSAA);
            this.upscaler.setAvailable(selected != AntiAliasMode.SSAA);
            this.renderScale.setAvailable(upscaling);
            this.nisSharpness.setAvailable(upscaling && upscalerSelected == Upscaler.NIS);
            this.rcasSharpness.setAvailable(BetterAliasConfig.rcasAppliesTo(selected, upscalerSelected));
            this.showEdges.setAvailable(BetterAliasConfig.edgeDebugAppliesTo(selected));
            this.keepWithShaderPacks.setAvailable(BetterAliasConfig.canKeepWithShaderPacks(selected, this.rcasSharpness.pendingValue(), upscalerSelected));
            this.refreshing = false;
        }
    }

    public static Screen create(Screen parent) {
        Options options = new Options();

        options.mode = Option.<AntiAliasMode>createBuilder()
                .name(Component.translatable("better-alias.options.mode"))
                .description(mode -> OptionDescription.of(modeDescription(mode, options.pendingKeepWithShaderPacks())))
                .binding(
                        AntiAliasMode.NONE,
                        BetterAliasConfig::getMode,
                        BetterAliasConfig::setMode
                )
                // Crossed out while a shader pack is pausing the selected method
                .controller(opt -> EnumControllerBuilder.create(opt)
                        .enumClass(AntiAliasMode.class)
                        .formatValue(mode -> BetterAliasConfig.modeLabel(mode, options.pendingKeepWithShaderPacks())))
                .addListener((opt, event) -> {
                    if (event == OptionEventListener.Event.STATE_CHANGE || event == OptionEventListener.Event.INITIAL) {
                        options.refresh();
                    }
                })
                .build();

        options.cmaa2Quality = Option.<Cmaa2Quality>createBuilder()
                .name(Component.translatable("better-alias.options.cmaa2_quality"))
                .description(quality -> OptionDescription.of(
                        Component.translatable("better-alias.options.cmaa2_quality.tooltip." + quality.name().toLowerCase(Locale.ROOT))
                                .append("\n\n")
                                .append(Component.translatable("better-alias.options.cmaa2_quality.tooltip.only_cmaa2"))))
                .binding(
                        Cmaa2Quality.HIGH,
                        BetterAliasConfig::getCmaa2Quality,
                        BetterAliasConfig::setCmaa2Quality
                )
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(Cmaa2Quality.class).formatValue(Cmaa2Quality::displayName))
                .build();

        options.taaSharpness = Option.<Integer>createBuilder()
                .name(Component.translatable("better-alias.options.taa_sharpness"))
                .description(OptionDescription.of(Component.translatable("better-alias.options.taa_sharpness.tooltip")))
                .binding(
                        50,
                        BetterAliasConfig::getTaaSharpness,
                        BetterAliasConfig::setTaaSharpness
                )
                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                        .range(0, 100)
                        .step(5)
                        .formatValue(BetterAliasConfig::sharpnessLabel))
                .build();

        options.ssaaScale = Option.<SsaaScale>createBuilder()
                .name(Component.translatable("better-alias.options.ssaa_scale"))
                // Includes the resolution this scale renders at for the current window
                .description(scale -> OptionDescription.of(BetterAliasRenderScale.ssaaScaleTooltip(scale)))
                .binding(
                        SsaaScale.X2,
                        BetterAliasConfig::getSsaaScale,
                        BetterAliasConfig::setSsaaScale
                )
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(SsaaScale.class).formatValue(SsaaScale::displayName))
                .build();

        options.rcasSharpness = Option.<Integer>createBuilder()
                .name(Component.translatable("better-alias.options.rcas_sharpness"))
                .description(OptionDescription.of(Component.translatable("better-alias.options.rcas_sharpness.tooltip")))
                .binding(
                        0,
                        BetterAliasConfig::getRcasSharpness,
                        BetterAliasConfig::setRcasSharpness
                )
                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                        .range(0, 100)
                        .step(5)
                        .formatValue(BetterAliasConfig::sharpnessLabel))
                // Turning RCAS on or off changes whether "Keep With Shader Packs" has anything to keep
                .addListener((opt, event) -> {
                    if (event == OptionEventListener.Event.STATE_CHANGE) {
                        options.refresh();
                    }
                })
                .build();

        options.upscaler = Option.<Upscaler>createBuilder()
                .name(Component.translatable("better-alias.options.upscaler"))
                .description(upscaler -> OptionDescription.of(Component.translatable("better-alias.options.upscaler.tooltip." + upscaler.name().toLowerCase(Locale.ROOT))
                        .append("\n\n")
                        .append(Component.translatable("better-alias.options.upscaler.tooltip.shared").withStyle(ChatFormatting.GRAY))))
                .binding(
                        Upscaler.OFF,
                        BetterAliasConfig::getUpscaler,
                        BetterAliasConfig::setUpscaler
                )
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(Upscaler.class).formatValue(Upscaler::displayName))
                .addListener((opt, event) -> {
                    if (event == OptionEventListener.Event.STATE_CHANGE) {
                        options.refresh();
                    }
                })
                .build();

        options.renderScale = Option.<RenderScale>createBuilder()
                .name(Component.translatable("better-alias.options.render_scale"))
                // Includes the resolution this scale renders at for the current window
                .description(scale -> OptionDescription.of(BetterAliasRenderScale.renderScaleTooltip(scale)))
                .binding(
                        RenderScale.QUALITY,
                        BetterAliasConfig::getRenderScale,
                        BetterAliasConfig::setRenderScale
                )
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(RenderScale.class).formatValue(RenderScale::displayName))
                .build();

        options.nisSharpness = Option.<Integer>createBuilder()
                .name(Component.translatable("better-alias.options.nis_sharpness"))
                .description(OptionDescription.of(Component.translatable("better-alias.options.nis_sharpness.tooltip")))
                .binding(
                        25,
                        BetterAliasConfig::getNisSharpness,
                        BetterAliasConfig::setNisSharpness
                )
                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                        .range(0, 100)
                        .step(5)
                        .formatValue(value -> Component.literal(value + "%")))
                .build();

        options.showEdges = Option.<Boolean>createBuilder()
                .name(Component.translatable("better-alias.options.show_edges"))
                .description(OptionDescription.of(Component.translatable("better-alias.options.show_edges.tooltip")))
                .binding(
                        false,
                        BetterAliasConfig::isShowEdges,
                        BetterAliasConfig::setShowEdges
                )
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();

        Option<PerformanceReadout> performanceReadout = Option.<PerformanceReadout>createBuilder()
                .name(Component.translatable("better-alias.options.performance_readout"))
                .description(OptionDescription.of(Component.translatable("better-alias.options.performance_readout.tooltip")))
                .binding(
                        PerformanceReadout.F3,
                        BetterAliasConfig::getPerformanceReadout,
                        BetterAliasConfig::setPerformanceReadout
                )
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(PerformanceReadout.class).formatValue(PerformanceReadout::displayName))
                .build();

        options.keepWithShaderPacks = Option.<Boolean>createBuilder()
                .name(Component.translatable("better-alias.options.keep_with_shader_packs"))
                .description(keep -> keepWithShaderPacksDescription(keep, options.pendingMode(), options.pendingRcas(), options.pendingUpscaler()))
                .binding(
                        false,
                        BetterAliasConfig::isKeepWithShaderPacks,
                        BetterAliasConfig::setKeepWithShaderPacks
                )
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();

        options.refresh();

        return YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("better-alias.options.title"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("better-alias.options.page"))
                        .group(group("post_process", options.mode, options.cmaa2Quality, options.taaSharpness, options.ssaaScale))
                        .group(group("upscaling", options.upscaler, options.renderScale, options.nisSharpness))
                        .group(group("sharpening", options.rcasSharpness))
                        .group(group("shader_packs", options.keepWithShaderPacks))
                        .group(group("debug", options.showEdges, performanceReadout))
                        .build())
                .save(BetterAliasConfig::save) // Writes config/better-alias.json when "Save Changes" is clicked
                .build()
                .generateScreen(parent);
    }

    /** A titled group of options, named like the Sodium page's groups (better-alias.options.group.*). */
    private static OptionGroup group(String name, Option<?>... groupOptions) {
        OptionGroup.Builder builder = OptionGroup.createBuilder().name(Component.translatable("better-alias.options.group." + name));
        for (Option<?> option : groupOptions) {
            builder.option(option);
        }
        return builder.build();
    }

    private static Component modeDescription(AntiAliasMode mode, boolean keepWithShaderPacks) {
        MutableComponent text = Component.empty();
        if (BetterAliasConfig.isPausedByShaderPack(mode, keepWithShaderPacks)) {
            text.append(BetterAliasConfig.pausedLine(mode).withStyle(ChatFormatting.RED)).append("\n\n");
        }
        text.append(Component.translatable("better-alias.options.mode.tooltip." + mode.name().toLowerCase(Locale.ROOT)));
        if (mode != AntiAliasMode.NONE && IrisCompat.isIrisLoaded()) {
            text.append("\n\n").append(BetterAliasConfig.irisLine(mode));
        }
        return text;
    }

    private static OptionDescription keepWithShaderPacksDescription(boolean keep, AntiAliasMode mode, int rcasSharpness, Upscaler upscaler) {
        MutableComponent text = Component.translatable("better-alias.options.keep_with_shader_packs.tooltip");
        if (!BetterAliasConfig.canKeepWithShaderPacks(mode, rcasSharpness, upscaler)) {
            text.append("\n\n").append(Component.translatable("better-alias.options.keep_with_shader_packs.unavailable").withStyle(ChatFormatting.GRAY));
        }
        if (keep) {
            text.append("\n\n").append(Component.translatable("better-alias.options.keep_with_shader_packs.warning")
                    .withStyle(ChatFormatting.RED));
        }
        Component irisStatus = !IrisCompat.isIrisLoaded()
                ? Component.translatable("better-alias.options.iris_status.not_installed")
                : IrisCompat.isShaderPackInUse()
                        ? Component.translatable("better-alias.options.iris_status.active")
                        : Component.translatable("better-alias.options.iris_status.inactive");
        return OptionDescription.of(text.append("\n\n").append(irisStatus));
    }
}
