package betteralias.compat;

import betteralias.BetterAliasConfig;
import betteralias.BetterAliasConfig.AntiAliasMode;
import betteralias.BetterAliasConfig.Cmaa2Quality;
import betteralias.BetterAliasConfig.PerformanceReadout;
import betteralias.BetterAliasConfig.RenderScale;
import betteralias.BetterAliasConfig.SsaaScale;
import betteralias.BetterAliasConfig.Upscaler;
import betteralias.BetterAliasRenderScale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * Adds a "Better Alias" section to Sodium's Video Settings screen, using Sodium's official config API
 * (0.7+; this was written against 0.9.2+mc26.2).
 *
 * <p>Registered in fabric.mod.json under the {@code "sodium:config_api_user"} entrypoint. Fabric only loads this
 * class when Sodium asks for that entrypoint, so the Sodium classes referenced here are never touched when Sodium
 * isn't installed. No mixins into Sodium's GUI are needed.
 *
 * <p>Sodium lists each mod's options in the sidebar under the mod's name/version from fabric.mod.json, with the pages
 * added here underneath. Changes are pending until the player presses Apply; then Sodium calls each changed option's
 * binding setter and afterwards the storage handler (our {@link BetterAliasConfig#save()}). The renderer reads the
 * settings every frame, so the change shows up immediately. Pressing Done without Apply discards pending changes
 * (standard Sodium behaviour for every option).
 *
 * <p>Tooltips are evaluated against the pending value, so flipping an option shows its warning before Apply.
 *
 * <p>To add a setting later: create another option with builder.createBooleanOption / createIntegerOption /
 * createEnumOption, give it a unique id, name, tooltip, default value, binding and storage handler (all required), and
 * add it to a group below.
 */
public final class SodiumOptionsPage implements ConfigEntryPoint {
    private static final String MOD_ID = "better-alias";

    /**
     * One shared handler instance: Sodium collects the handlers of all changed options into a Set and calls each once,
     * so sharing it means one file write per Apply no matter how many of our options changed.
     */
    private static final StorageEventHandler STORAGE = BetterAliasConfig::save;

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        Identifier modeId = id("mode");
        Identifier upscalerId = id("upscaler");
        Identifier rcasId = id("rcas_sharpness");

        // Options that only apply to some settings declare what they depend on, so Sodium re-checks them as soon as a
        // dropdown changes, before Apply. Sodium can't remove a row, so an inactive one is greyed out with its control
        // hidden. (No cycles: mode depends on nothing, upscaler on mode, RCAS on both, the rest on those.)
        builder.registerOwnModOptions()
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("better-alias.options.page"))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("better-alias.options.group.post_process"))
                                .addOption(builder.createEnumOption(modeId, AntiAliasMode.class)
                                        .setName(Component.translatable("better-alias.options.mode"))
                                        .setTooltip(SodiumOptionsPage::modeTooltip)
                                        // Crossed out while a shader pack is pausing it. Uses the saved override: the
                                        // override's own option depends on this one, so reading it back would be a cycle.
                                        .setElementNameProvider(mode -> BetterAliasConfig.modeLabel(mode, BetterAliasConfig.isKeepWithShaderPacks()))
                                        .setImpact(OptionImpact.LOW)
                                        .setDefaultValue(AntiAliasMode.NONE)
                                        .setBinding(BetterAliasConfig::setMode, BetterAliasConfig::getMode)
                                        .setStorageHandler(STORAGE))
                                .addOption(builder.createEnumOption(id("cmaa2_quality"), Cmaa2Quality.class)
                                        .setName(Component.translatable("better-alias.options.cmaa2_quality"))
                                        .setTooltip(SodiumOptionsPage::cmaa2QualityTooltip)
                                        .setElementNameProvider(Cmaa2Quality::displayName)
                                        .setEnabledProvider(state -> mode(state, modeId) == AntiAliasMode.CMAA2, modeId)
                                        .setImpact(OptionImpact.LOW)
                                        .setDefaultValue(Cmaa2Quality.HIGH)
                                        .setBinding(BetterAliasConfig::setCmaa2Quality, BetterAliasConfig::getCmaa2Quality)
                                        .setStorageHandler(STORAGE))
                                .addOption(builder.createIntegerOption(id("taa_sharpness"))
                                        .setName(Component.translatable("better-alias.options.taa_sharpness"))
                                        .setTooltip(Component.translatable("better-alias.options.taa_sharpness.tooltip"))
                                        .setEnabledProvider(state -> mode(state, modeId) == AntiAliasMode.TAA, modeId)
                                        .setRange(0, 100, 5)
                                        .setValueFormatter(BetterAliasConfig::sharpnessLabel)
                                        .setImpact(OptionImpact.LOW)
                                        .setDefaultValue(50)
                                        .setBinding(BetterAliasConfig::setTaaSharpness, BetterAliasConfig::getTaaSharpness)
                                        .setStorageHandler(STORAGE))
                                .addOption(builder.createEnumOption(id("ssaa_scale"), SsaaScale.class)
                                        .setName(Component.translatable("better-alias.options.ssaa_scale"))
                                        // Includes the resolution this scale renders at for the current window
                                        .setTooltip(BetterAliasRenderScale::ssaaScaleTooltip)
                                        .setElementNameProvider(SsaaScale::displayName)
                                        .setEnabledProvider(state -> mode(state, modeId) == AntiAliasMode.SSAA, modeId)
                                        .setImpact(OptionImpact.HIGH)
                                        .setDefaultValue(SsaaScale.X2)
                                        .setBinding(BetterAliasConfig::setSsaaScale, BetterAliasConfig::getSsaaScale)
                                        .setStorageHandler(STORAGE)))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("better-alias.options.group.upscaling"))
                                .addOption(builder.createEnumOption(upscalerId, Upscaler.class)
                                        .setName(Component.translatable("better-alias.options.upscaler"))
                                        .setTooltip(SodiumOptionsPage::upscalerTooltip)
                                        .setElementNameProvider(Upscaler::displayName)
                                        // SSAA already sets the resolution
                                        .setEnabledProvider(state -> mode(state, modeId) != AntiAliasMode.SSAA, modeId)
                                        .setImpact(OptionImpact.VARIES)
                                        .setDefaultValue(Upscaler.OFF)
                                        .setBinding(BetterAliasConfig::setUpscaler, BetterAliasConfig::getUpscaler)
                                        .setStorageHandler(STORAGE))
                                .addOption(builder.createEnumOption(id("render_scale"), RenderScale.class)
                                        .setName(Component.translatable("better-alias.options.render_scale"))
                                        // Includes the resolution this scale renders at for the current window
                                        .setTooltip(BetterAliasRenderScale::renderScaleTooltip)
                                        .setElementNameProvider(RenderScale::displayName)
                                        .setEnabledProvider(state -> BetterAliasConfig.upscalingApplies(mode(state, modeId), upscaler(state, upscalerId)), modeId, upscalerId)
                                        .setImpact(OptionImpact.VARIES)
                                        .setDefaultValue(RenderScale.QUALITY)
                                        .setBinding(BetterAliasConfig::setRenderScale, BetterAliasConfig::getRenderScale)
                                        .setStorageHandler(STORAGE))
                                .addOption(builder.createIntegerOption(id("nis_sharpness"))
                                        .setName(Component.translatable("better-alias.options.nis_sharpness"))
                                        .setTooltip(Component.translatable("better-alias.options.nis_sharpness.tooltip"))
                                        .setEnabledProvider(state -> BetterAliasConfig.upscalingApplies(mode(state, modeId), upscaler(state, upscalerId))
                                                && upscaler(state, upscalerId) == Upscaler.NIS, modeId, upscalerId)
                                        .setRange(0, 100, 5)
                                        .setValueFormatter(value -> Component.literal(value + "%"))
                                        .setImpact(OptionImpact.LOW)
                                        .setDefaultValue(25)
                                        .setBinding(BetterAliasConfig::setNisSharpness, BetterAliasConfig::getNisSharpness)
                                        .setStorageHandler(STORAGE)))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("better-alias.options.group.sharpening"))
                                .addOption(builder.createIntegerOption(rcasId)
                                        .setName(Component.translatable("better-alias.options.rcas_sharpness"))
                                        .setTooltip(Component.translatable("better-alias.options.rcas_sharpness.tooltip"))
                                        // Greyed out for TAA (TAA Sharpening) and NIS upscaling (NIS Sharpness)
                                        .setEnabledProvider(state -> BetterAliasConfig.rcasAppliesTo(mode(state, modeId), upscaler(state, upscalerId)), modeId, upscalerId)
                                        .setRange(0, 100, 5)
                                        .setValueFormatter(BetterAliasConfig::sharpnessLabel)
                                        .setImpact(OptionImpact.LOW)
                                        .setDefaultValue(0)
                                        .setBinding(BetterAliasConfig::setRcasSharpness, BetterAliasConfig::getRcasSharpness)
                                        .setStorageHandler(STORAGE)))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("better-alias.options.group.shader_packs"))
                                .addOption(builder.createBooleanOption(id("keep_with_shader_packs"))
                                        .setName(Component.translatable("better-alias.options.keep_with_shader_packs"))
                                        .setTooltip(SodiumOptionsPage::keepWithShaderPacksTooltip)
                                        // Greyed out (value still shown) unless there is a finished-image filter to keep (FXAA,
                                        // SMAA 1x, CMAA2 or RCAS), and without Iris
                                        .setEnabledProvider(state -> BetterAliasConfig.canKeepWithShaderPacks(
                                                mode(state, modeId), state.readIntOption(rcasId), upscaler(state, upscalerId)), modeId, rcasId, upscalerId)
                                        .setControlHiddenWhenDisabled(false)
                                        .setDefaultValue(false)
                                        .setBinding(BetterAliasConfig::setKeepWithShaderPacks, BetterAliasConfig::isKeepWithShaderPacks)
                                        .setStorageHandler(STORAGE)))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("better-alias.options.group.debug"))
                                .addOption(builder.createBooleanOption(id("show_edges"))
                                        .setName(Component.translatable("better-alias.options.show_edges"))
                                        .setTooltip(Component.translatable("better-alias.options.show_edges.tooltip"))
                                        .setEnabledProvider(state -> BetterAliasConfig.edgeDebugAppliesTo(mode(state, modeId)), modeId)
                                        .setDefaultValue(false)
                                        .setBinding(BetterAliasConfig::setShowEdges, BetterAliasConfig::isShowEdges)
                                        .setStorageHandler(STORAGE))
                                .addOption(builder.createEnumOption(id("performance_readout"), PerformanceReadout.class)
                                        .setName(Component.translatable("better-alias.options.performance_readout"))
                                        .setTooltip(Component.translatable("better-alias.options.performance_readout.tooltip"))
                                        .setElementNameProvider(PerformanceReadout::displayName)
                                        .setDefaultValue(PerformanceReadout.F3)
                                        .setBinding(BetterAliasConfig::setPerformanceReadout, BetterAliasConfig::getPerformanceReadout)
                                        .setStorageHandler(STORAGE))));
    }

    private static AntiAliasMode mode(ConfigState state, Identifier modeId) {
        return state.readEnumOption(modeId, AntiAliasMode.class);
    }

    private static Upscaler upscaler(ConfigState state, Identifier upscalerId) {
        return state.readEnumOption(upscalerId, Upscaler.class);
    }

    /** Per-upscaler explanation (the SSAA note is in the text). */
    static Component upscalerTooltip(Upscaler upscaler) {
        return Component.translatable("better-alias.options.upscaler.tooltip." + upscaler.name().toLowerCase(Locale.ROOT))
                .append("\n\n")
                .append(Component.translatable("better-alias.options.upscaler.tooltip.shared").withStyle(ChatFormatting.GRAY));
    }

    /** Tooltip changes with the selected value, e.g. better-alias.options.mode.tooltip.fxaa. */
    private static Component modeTooltip(AntiAliasMode mode) {
        MutableComponent tooltip = Component.translatable("better-alias.options.mode.tooltip." + mode.name().toLowerCase(Locale.ROOT));
        if (BetterAliasConfig.isPausedByShaderPack(mode, BetterAliasConfig.isKeepWithShaderPacks())) {
            tooltip = Component.empty()
                    .append(BetterAliasConfig.pausedLine(mode).withStyle(ChatFormatting.RED))
                    .append("\n\n")
                    .append(tooltip);
        }
        if (IrisCompat.isIrisLoaded() && mode != AntiAliasMode.NONE) {
            tooltip = Component.empty()
                    .append(tooltip)
                    .append("\n\n")
                    .append(BetterAliasConfig.irisLine(mode));
        }
        return tooltip;
    }

    /** Per-preset explanation, plus a reminder that it only matters when the method is CMAA2. */
    static Component cmaa2QualityTooltip(Cmaa2Quality quality) {
        return Component.translatable("better-alias.options.cmaa2_quality.tooltip." + quality.name().toLowerCase(Locale.ROOT))
                .append("\n\n")
                .append(Component.translatable("better-alias.options.cmaa2_quality.tooltip.only_cmaa2").withStyle(ChatFormatting.GRAY));
    }

    /** Explains the default when off; shows a red warning as soon as the player flips it on (before Apply). */
    static Component keepWithShaderPacksTooltip(boolean keep) {
        MutableComponent tooltip = Component.translatable("better-alias.options.keep_with_shader_packs.tooltip");
        if (!BetterAliasConfig.canKeepWithShaderPacks(BetterAliasConfig.getMode(), BetterAliasConfig.getRcasSharpness(), BetterAliasConfig.getUpscaler())) {
            tooltip.append("\n\n").append(Component.translatable("better-alias.options.keep_with_shader_packs.unavailable").withStyle(ChatFormatting.GRAY));
        }
        if (keep) {
            tooltip.append("\n\n").append(Component.translatable("better-alias.options.keep_with_shader_packs.warning")
                    .withStyle(ChatFormatting.RED));
        }
        return tooltip.append("\n\n").append(irisStatus());
    }

    /** Current Iris state, so the player can see whether this setting matters right now. */
    static Component irisStatus() {
        if (!IrisCompat.isIrisLoaded()) {
            return Component.translatable("better-alias.options.iris_status.not_installed").withStyle(ChatFormatting.GRAY);
        }
        return IrisCompat.isShaderPackInUse()
                ? Component.translatable("better-alias.options.iris_status.active").withStyle(ChatFormatting.YELLOW)
                : Component.translatable("better-alias.options.iris_status.inactive").withStyle(ChatFormatting.GRAY);
    }
}
