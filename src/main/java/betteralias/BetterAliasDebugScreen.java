package betteralias;

import betteralias.BetterAliasConfig.AntiAliasMode;
import betteralias.BetterAliasConfig.PerformanceReadout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Locale;

/**
 * The performance readout: one line in vanilla's debug screen (F3), registered as a debug screen entry, e.g.
 * {@code Better Alias: SMAA 1x + FSR 1 | world 1280x720 | passes 0.42 ms}.
 *
 * <p>The "Performance Readout" setting maps onto vanilla's entry statuses (Off = never, In F3 = in the overlay,
 * Always = shown even with F3 closed), so it can also be changed from vanilla's debug options screen.
 */
public final class BetterAliasDebugScreen implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("better-alias", "performance");

    private static PerformanceReadout appliedReadout;

    private BetterAliasDebugScreen() {
    }

    public static void register() {
        DebugScreenEntries.register(ID, new BetterAliasDebugScreen());
    }

    /**
     * Keeps the setting and vanilla's debug entry in step, both ways. Called every frame; cheap when nothing changed.
     * <ul>
     *   <li>The setting changed (or first frame): it is pushed into vanilla's debug entry list.</li>
     *   <li>The entry changed in F3's debug options screen (or an F3 preset was picked, which leaves it out): the
     *       setting follows and is saved, so the two never disagree and the choice survives a restart.</li>
     * </ul>
     */
    public static void applySetting(Minecraft minecraft) {
        PerformanceReadout readout = BetterAliasConfig.getPerformanceReadout();
        if (readout != appliedReadout) {
            appliedReadout = readout;
            DebugScreenEntryStatus status = toStatus(readout);
            if (minecraft.debugEntries.getStatus(ID) != status) {
                minecraft.debugEntries.setStatus(ID, status);
            }
            return;
        }
        PerformanceReadout fromDebugScreen = fromStatus(minecraft.debugEntries.getStatus(ID));
        if (fromDebugScreen != readout) {
            appliedReadout = fromDebugScreen;
            BetterAliasConfig.setPerformanceReadout(fromDebugScreen);
            BetterAliasConfig.save();
        }
    }

    static DebugScreenEntryStatus toStatus(PerformanceReadout readout) {
        return switch (readout) {
            case OFF -> DebugScreenEntryStatus.NEVER;
            case F3 -> DebugScreenEntryStatus.IN_OVERLAY;
            case ALWAYS -> DebugScreenEntryStatus.ALWAYS_ON;
        };
    }

    static PerformanceReadout fromStatus(DebugScreenEntryStatus status) {
        return switch (status) {
            case NEVER -> PerformanceReadout.OFF;
            case IN_OVERLAY -> PerformanceReadout.F3;
            case ALWAYS_ON -> PerformanceReadout.ALWAYS;
        };
    }

    @Override
    public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
        displayer.addLine(line());
    }

    static String line() {
        AntiAliasMode mode = BetterAliasConfig.getMode();
        StringBuilder text = new StringBuilder("Better Alias: ").append(mode.displayName().getString());
        if (BetterAliasConfig.upscalingApplies(mode, BetterAliasConfig.getUpscaler())) {
            text.append(" + ").append(BetterAliasConfig.getUpscaler().displayName().getString());
        }
        if (BetterAliasConfig.isShaderActive()) {
            text.append(" (shader pack)");
        }
        int[] world = BetterAliasRenderScale.lastWorldSize();
        if (world[0] > 0) {
            text.append(" | world ").append(world[0]).append('x').append(world[1]);
        }
        double millis = BetterAliasGpuTimer.millis();
        if (millis >= 0.0) {
            text.append(" | passes ").append(String.format(Locale.ROOT, "%.2f", millis)).append(" ms");
        }
        return text.toString();
    }
}
