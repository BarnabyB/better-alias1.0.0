package betteralias;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import betteralias.compat.IrisCompat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The mod's settings and their persistence (config/better-alias.json).
 *
 * <p>This class deliberately has no UI-library imports. Each settings UI (YACL via Mod Menu, Sodium's video settings)
 * lives in its own class and only reads/writes through here, so either UI can be absent at runtime.
 */
public final class BetterAliasConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("better-alias");

    /** In dropdown order. Saved by name, so entries can be reordered or added without breaking existing configs. */
    public enum AntiAliasMode {
        NONE(null, false),
        FXAA("better-alias:fxaa", false),
        SMAA("better-alias:smaa", false),
        /** Not a post chain: rendered by BetterAliasSmaaTemporal (needs camera jitter and the previous frames). */
        SMAA_T2X(null, true),
        /** SMAA 4x's four sample positions over four frames (true SMAA 4x needs MSAA, which Minecraft doesn't have). */
        SMAA_4X(null, true),
        CMAA2("better-alias:cmaa2", false),
        /** Not a post chain: rendered by BetterAliasTaa (needs camera jitter and a history buffer). */
        TAA(null, true),
        /** Not a post chain: BetterAliasRenderScale renders the world at a higher resolution (see {@link SsaaScale}). */
        SSAA(null, true);

        /** The post_effect JSON to run, or null if there isn't one. CMAA2's actually depends on {@link Cmaa2Quality}. */
        public final Identifier postEffect;
        /**
         * Changes how the world itself is drawn (camera jitter for the temporal modes, render resolution for SSAA)
         * instead of filtering the finished image. These can never run on top of a shader pack, which draws the world
         * through its own pipeline, so "Keep With Shader Packs" doesn't apply to them.
         */
        public final boolean changesWorldRendering;

        AntiAliasMode(String postEffect, boolean changesWorldRendering) {
            this.postEffect = postEffect == null ? null : Identifier.parse(postEffect);
            this.changesWorldRendering = changesWorldRendering;
        }

        /** Display name, from assets/better-alias/lang/en_us.json (e.g. better-alias.mode.fxaa). */
        public Component displayName() {
            return Component.translatable("better-alias.mode." + this.name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * CMAA2's quality presets (CMAA2_STATIC_QUALITY_PRESET in Intel's reference). They only change the edge detection
     * threshold, which each preset's post_effect JSON passes to cmaa2_edges.fsh. HIGH is the reference default.
     */
    public enum Cmaa2Quality {
        LOW("better-alias:cmaa2_low"),
        MEDIUM("better-alias:cmaa2_medium"),
        HIGH("better-alias:cmaa2"),
        ULTRA("better-alias:cmaa2_ultra");

        public final Identifier postEffect;

        Cmaa2Quality(String postEffect) {
            this.postEffect = Identifier.parse(postEffect);
        }

        /** Display name, e.g. better-alias.cmaa2_quality.high. */
        public Component displayName() {
            return Component.translatable("better-alias.cmaa2_quality." + this.name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * How much larger than the window SSAA renders the world, per axis (so X2 = 4 samples per pixel). The final image is
     * the area-weighted average of the samples each window pixel covers.
     */
    public enum SsaaScale {
        X1_25(1.25f),
        X1_5(1.5f),
        X1_75(1.75f),
        X2(2.0f),
        X2_5(2.5f),
        X3(3.0f),
        X4(4.0f);

        public final float factor;

        SsaaScale(float factor) {
            this.factor = factor;
        }

        /** Display name, e.g. better-alias.ssaa_scale.x2 = "2x (4x pixels)". */
        public Component displayName() {
            return Component.translatable("better-alias.ssaa_scale." + this.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Upscaler used when the world is rendered below the window's resolution (not with SSAA, which renders above it). */
    public enum Upscaler {
        OFF,
        FSR1,
        NIS;

        public Component displayName() {
            return Component.translatable("better-alias.upscaler." + this.name().toLowerCase(Locale.ROOT));
        }
    }

    /** How much of the window's resolution the world is rendered at before upscaling (FSR 1's presets). */
    public enum RenderScale {
        ULTRA_QUALITY(1.0f / 1.3f),
        QUALITY(1.0f / 1.5f),
        BALANCED(1.0f / 1.7f),
        PERFORMANCE(0.5f);

        public final float factor;

        RenderScale(float factor) {
            this.factor = factor;
        }

        public Component displayName() {
            return Component.translatable("better-alias.render_scale." + this.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Where the GPU time readout appears (vanilla's F3 entry statuses). */
    public enum PerformanceReadout {
        OFF,
        F3,
        ALWAYS;

        public Component displayName() {
            return Component.translatable("better-alias.performance_readout." + this.name().toLowerCase(Locale.ROOT));
        }
    }

    public static AntiAliasMode currentMode = AntiAliasMode.NONE;

    public static Cmaa2Quality cmaa2Quality = Cmaa2Quality.HIGH;

    /** CAS sharpening applied after TAA, 0-100 (%). 0 turns it off. */
    public static int taaSharpness = 50;

    /**
     * Shader-pack guard rail. When false (the default), anti-aliasing pauses automatically while an Iris shader pack is
     * active, because almost every pack ships its own FXAA/TAA and stacking a second filter on top mostly adds blur.
     * Players can opt in if their pack has no anti-aliasing (or theirs is switched off).
     */
    public static boolean keepWithShaderPacks = false;

    public static SsaaScale ssaaScale = SsaaScale.X2;

    /** RCAS sharpening applied after anti-aliasing, 0-100 (%). 0 turns it off. */
    public static int rcasSharpness = 0;

    public static Upscaler upscaler = Upscaler.OFF;

    public static RenderScale renderScale = RenderScale.QUALITY;

    /** NIS's own sharpening, 0-100 (%). */
    public static int nisSharpness = 25;

    /** Debug view of the edges FXAA / SMAA 1x / CMAA2 detect. Deliberately not saved: it is off after a restart. */
    public static boolean showEdges = false;

    public static PerformanceReadout performanceReadout = PerformanceReadout.F3;

    public static AntiAliasMode getMode() {
        return currentMode;
    }

    public static void setMode(AntiAliasMode mode) {
        currentMode = mode == null ? AntiAliasMode.NONE : mode;
    }

    public static Cmaa2Quality getCmaa2Quality() {
        return cmaa2Quality;
    }

    public static void setCmaa2Quality(Cmaa2Quality quality) {
        cmaa2Quality = quality == null ? Cmaa2Quality.HIGH : quality;
    }

    public static SsaaScale getSsaaScale() {
        return ssaaScale;
    }

    public static void setSsaaScale(SsaaScale scale) {
        ssaaScale = scale == null ? SsaaScale.X2 : scale;
    }

    public static int getRcasSharpness() {
        return rcasSharpness;
    }

    public static void setRcasSharpness(int sharpness) {
        rcasSharpness = Math.max(0, Math.min(100, sharpness));
    }

    /**
     * RCAS works after every method except TAA, which already sharpens with CAS (TAA Sharpening), and not with NIS
     * upscaling, which sharpens by itself (NIS Sharpness).
     */
    public static boolean rcasAppliesTo(AntiAliasMode mode, Upscaler upscaler) {
        return mode != AntiAliasMode.TAA && !(upscalingApplies(mode, upscaler) && upscaler == Upscaler.NIS);
    }

    /** Upscaling runs for every method except SSAA, which renders above the window's resolution instead. */
    public static boolean upscalingApplies(AntiAliasMode mode, Upscaler upscaler) {
        return upscaler != Upscaler.OFF && mode != AntiAliasMode.SSAA;
    }

    /** The edge debug view exists for the methods with an edge-detection pass of their own. */
    public static boolean edgeDebugAppliesTo(AntiAliasMode mode) {
        return mode == AntiAliasMode.FXAA || mode == AntiAliasMode.SMAA || mode == AntiAliasMode.CMAA2;
    }

    public static Upscaler getUpscaler() {
        return upscaler;
    }

    public static void setUpscaler(Upscaler value) {
        upscaler = value == null ? Upscaler.OFF : value;
    }

    public static RenderScale getRenderScale() {
        return renderScale;
    }

    public static void setRenderScale(RenderScale value) {
        renderScale = value == null ? RenderScale.QUALITY : value;
    }

    public static int getNisSharpness() {
        return nisSharpness;
    }

    public static void setNisSharpness(int sharpness) {
        nisSharpness = Math.max(0, Math.min(100, sharpness));
    }

    public static boolean isShowEdges() {
        return showEdges;
    }

    public static void setShowEdges(boolean show) {
        showEdges = show;
    }

    public static PerformanceReadout getPerformanceReadout() {
        return performanceReadout;
    }

    public static void setPerformanceReadout(PerformanceReadout value) {
        performanceReadout = value == null ? PerformanceReadout.F3 : value;
    }

    public static int getTaaSharpness() {
        return taaSharpness;
    }

    public static void setTaaSharpness(int sharpness) {
        taaSharpness = Math.max(0, Math.min(100, sharpness));
    }

    /**
     * Whether a shader pack is currently stopping {@code mode} from running, given the (possibly not yet saved)
     * "Keep With Shader Packs" choice. Modes that change how the world is drawn always stop with a shader pack; the
     * others only without the override.
     */
    public static boolean isPausedByShaderPack(AntiAliasMode mode, boolean keepWithShaderPacks) {
        return mode != AntiAliasMode.NONE && isShaderActive() && (mode.changesWorldRendering || !keepWithShaderPacks);
    }

    /** Dropdown label for a mode: crossed out while a shader pack is pausing it. Shared by both settings screens. */
    public static Component modeLabel(AntiAliasMode mode, boolean keepWithShaderPacks) {
        Component name = mode.displayName();
        return isPausedByShaderPack(mode, keepWithShaderPacks)
                ? name.copy().withStyle(ChatFormatting.STRIKETHROUGH, ChatFormatting.GRAY)
                : name;
    }

    /** Red first tooltip line while a shader pack is pausing {@code mode}. Shared by both settings screens. */
    public static MutableComponent pausedLine(AntiAliasMode mode) {
        return Component.translatable(mode.changesWorldRendering ? "better-alias.options.mode.paused_temporal" : "better-alias.options.mode.paused");
    }

    /** How {@code mode} behaves with Iris shader packs (shown when Iris is installed). Shared by both settings screens. */
    public static MutableComponent irisLine(AntiAliasMode mode) {
        if (mode == AntiAliasMode.SSAA) {
            return Component.translatable("better-alias.options.mode.tooltip.iris_ssaa", mode.displayName());
        }
        return mode.changesWorldRendering
                ? Component.translatable("better-alias.options.mode.tooltip.iris_temporal", mode.displayName())
                : Component.translatable("better-alias.options.mode.tooltip.iris");
    }

    /**
     * Whether "Keep With Shader Packs" means anything for these (possibly unsaved) settings: it keeps the filters that
     * work on the finished image - a post-processing method (FXAA, SMAA 1x, CMAA2) and/or RCAS sharpening. Modes that
     * change how the world is drawn always stop with a shader pack.
     */
    public static boolean canKeepWithShaderPacks(AntiAliasMode mode, int rcasSharpness, Upscaler upscaler) {
        boolean postProcessingMode = mode != AntiAliasMode.NONE && !mode.changesWorldRendering;
        boolean rcas = rcasSharpness > 0 && rcasAppliesTo(mode, upscaler);
        return IrisCompat.isIrisLoaded() && (postProcessingMode || rcas);
    }

    /** "Off" for 0, otherwise "50%" - shared by both settings screens. */
    public static Component sharpnessLabel(int percent) {
        return percent <= 0
                ? Component.translatable("better-alias.options.taa_sharpness.off")
                : Component.literal(percent + "%");
    }

    /**
     * The post_effect to run this frame for the current settings, or null when there isn't one. With "Show Detected
     * Edges" on, the method's edge debug chain (post_effect/debug/*.json) replaces it.
     */
    public static Identifier activePostEffect() {
        Identifier effect = currentMode == AntiAliasMode.CMAA2 ? cmaa2Quality.postEffect : currentMode.postEffect;
        if (effect != null && showEdges && edgeDebugAppliesTo(currentMode)) {
            return Identifier.fromNamespaceAndPath(effect.getNamespace(), "debug/" + debugName(effect.getPath()));
        }
        return effect;
    }

    /** fxaa -> fxaa_edges, cmaa2_low -> cmaa2_edges_low, ... */
    private static String debugName(String path) {
        int split = path.indexOf('_');
        return split < 0 ? path + "_edges" : path.substring(0, split) + "_edges" + path.substring(split);
    }

    public static boolean isKeepWithShaderPacks() {
        return keepWithShaderPacks;
    }

    public static void setKeepWithShaderPacks(boolean keep) {
        keepWithShaderPacks = keep;
    }

    /** True while an Iris shader pack is active (always false if Iris isn't installed). */
    public static boolean isShaderActive() {
        return IrisCompat.isShaderPackInUse();
    }

    /** True if a shader pack is active and the player hasn't opted in to stacking, i.e. our pass should be skipped. */
    public static boolean isPausedForShaderPack() {
        return !keepWithShaderPacks && isShaderActive();
    }

    // --- FILE SAVING LOGIC ---
    // Settings are written to disk the moment they are applied in either UI (Sodium "Apply" / YACL "Save"), and read
    // back in BetterAliasClient.onInitializeClient before the first frame. Nothing needs saving when the game closes.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("better-alias.json");
    /** Where development builds (named "Anti-Alias") saved; read once if there is no better-alias.json yet. */
    private static final Path LEGACY_CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("anti-alias.json");

    private static class ConfigData {
        // Field initializers are the defaults for any key missing from an older config file.
        AntiAliasMode mode = AntiAliasMode.NONE;
        Cmaa2Quality cmaa2Quality = Cmaa2Quality.HIGH;
        int taaSharpness = 50;
        boolean keepWithShaderPacks = false;
        SsaaScale ssaaScale = SsaaScale.X2;
        int rcasSharpness = 0;
        Upscaler upscaler = Upscaler.OFF;
        RenderScale renderScale = RenderScale.QUALITY;
        int nisSharpness = 25;
        PerformanceReadout performanceReadout = PerformanceReadout.F3;
    }

    public static void load() {
        Path path = CONFIG_PATH;
        if (!Files.exists(path) && Files.exists(LEGACY_CONFIG_PATH)) {
            path = LEGACY_CONFIG_PATH;
        }
        if (!Files.exists(path)) {
            LOGGER.info("No {} yet, using defaults (mode {})", CONFIG_PATH.getFileName(), currentMode);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data != null) {
                // An unknown/removed mode name deserializes to null, which falls back to NONE.
                setMode(data.mode);
                setCmaa2Quality(data.cmaa2Quality);
                setTaaSharpness(data.taaSharpness);
                keepWithShaderPacks = data.keepWithShaderPacks;
                setSsaaScale(data.ssaaScale);
                setRcasSharpness(data.rcasSharpness);
                setUpscaler(data.upscaler);
                setRenderScale(data.renderScale);
                setNisSharpness(data.nisSharpness);
                setPerformanceReadout(data.performanceReadout);
            }
            LOGGER.info("Loaded settings: mode {}, CMAA2 quality {}, TAA sharpening {}%, SSAA scale {}, RCAS {}%, upscaler {} at {} (NIS sharpness {}%), performance readout {}, keep with shader packs {}",
                    currentMode, cmaa2Quality, taaSharpness, ssaaScale, rcasSharpness, upscaler, renderScale, nisSharpness, performanceReadout, keepWithShaderPacks);
        } catch (Exception e) {
            LOGGER.error("Failed to load Better Alias config from {}", path, e);
        }
    }

    public static void save() {
        ConfigData data = new ConfigData();
        data.mode = currentMode;
        data.cmaa2Quality = cmaa2Quality;
        data.taaSharpness = taaSharpness;
        data.keepWithShaderPacks = keepWithShaderPacks;
        data.ssaaScale = ssaaScale;
        data.rcasSharpness = rcasSharpness;
        data.upscaler = upscaler;
        data.renderScale = renderScale;
        data.nisSharpness = nisSharpness;
        data.performanceReadout = performanceReadout;

        // Write to a temp file and move it over the real one, so a crash mid-write can't leave an empty config behind.
        Path tempPath = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(tempPath)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(tempPath, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempPath, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("Saved settings: mode {}, CMAA2 quality {}, TAA sharpening {}%, SSAA scale {}, RCAS {}%, upscaler {} at {} (NIS sharpness {}%), performance readout {}, keep with shader packs {}",
                    currentMode, cmaa2Quality, taaSharpness, ssaaScale, rcasSharpness, upscaler, renderScale, nisSharpness, performanceReadout, keepWithShaderPacks);
        } catch (Exception e) {
            LOGGER.error("Failed to save Better Alias config to {}", CONFIG_PATH, e);
        }
    }

    private BetterAliasConfig() {
    }
}
