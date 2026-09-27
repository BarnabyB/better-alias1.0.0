package betteralias;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalLong;

/**
 * Measures how long Better Alias's own GPU passes take, with GPU timestamp queries (blaze3d's timestamp query pool).
 *
 * <p>Each group of passes is wrapped in {@link #begin()} / {@link #end()} (the temporal resolve before the hand, the
 * SSAA/upscaling pass at the end of the world, the post-processing chain and RCAS). Results arrive a few frames later,
 * so each frame's timestamps go into their own slot of a small ring and are read back {@code FRAMES - 1} frames on,
 * without ever waiting on the GPU. The total is smoothed for display.
 *
 * <p>Only runs while the performance readout is on. The cost of rendering the world at a different resolution (SSAA,
 * upscaling) happens inside Minecraft's own passes and isn't included; the readout shows that resolution instead.
 */
public final class BetterAliasGpuTimer {
    private static final Logger LOGGER = LoggerFactory.getLogger("better-alias");
    private static final int FRAMES = 4;
    private static final int SEGMENTS = 8;

    private static GpuQueryPool pool;
    private static boolean unavailable;
    private static int frameSlot;
    private static final int[] segmentCount = new int[FRAMES];
    private static boolean open;

    /** Smoothed GPU time of our passes, in milliseconds; negative until a first result is in. */
    private static double smoothedMillis = -1.0;

    private BetterAliasGpuTimer() {
    }

    private static boolean enabled() {
        return BetterAliasConfig.getPerformanceReadout() != BetterAliasConfig.PerformanceReadout.OFF && !unavailable;
    }

    private static boolean ensurePool() {
        if (pool != null) {
            return true;
        }
        try {
            pool = RenderSystem.getDevice().createTimestampQueryPool(FRAMES * SEGMENTS * 2);
            return true;
        } catch (RuntimeException e) {
            unavailable = true;
            LOGGER.warn("GPU timestamp queries are not available; the Better Alias performance readout will not show GPU times", e);
            return false;
        }
    }

    private static void write(int index) {
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, index);
    }

    /** Start timing a group of passes. */
    public static void begin() {
        if (!enabled() || open || segmentCount[frameSlot] >= SEGMENTS || !ensurePool()) {
            return;
        }
        write((frameSlot * SEGMENTS + segmentCount[frameSlot]) * 2);
        open = true;
    }

    /** Stop timing the group started by {@link #begin()}. */
    public static void end() {
        if (!open) {
            return;
        }
        write((frameSlot * SEGMENTS + segmentCount[frameSlot]) * 2 + 1);
        segmentCount[frameSlot]++;
        open = false;
    }

    /** Called once per frame after the last pass: reads the oldest slot's results and moves to the next slot. */
    public static void endFrame() {
        if (open) {
            end();
        }
        if (!enabled() || pool == null) {
            return;
        }
        int oldest = (frameSlot + 1) % FRAMES;
        int count = segmentCount[oldest];
        if (count > 0) {
            OptionalLong[] values = pool.getValues(oldest * SEGMENTS * 2, count * 2);
            long ticks = 0;
            boolean complete = true;
            for (int i = 0; i < count && complete; i++) {
                OptionalLong start = values[i * 2];
                OptionalLong stop = values[i * 2 + 1];
                if (start.isEmpty() || stop.isEmpty()) {
                    complete = false;
                } else {
                    ticks += Math.max(0L, stop.getAsLong() - start.getAsLong());
                }
            }
            if (complete) {
                GpuDevice device = RenderSystem.getDevice();
                double millis = ticks * (double) device.getDeviceInfo().timestampPeriod() / 1_000_000.0;
                smoothedMillis = smoothedMillis < 0.0 ? millis : smoothedMillis * 0.9 + millis * 0.1;
            }
        } else {
            // Nothing of ours ran that frame (e.g. anti-aliasing Off)
            smoothedMillis = smoothedMillis < 0.0 ? 0.0 : smoothedMillis * 0.9;
        }
        frameSlot = oldest;
        segmentCount[frameSlot] = 0;
    }

    /** Smoothed GPU time of our passes in milliseconds, or a negative value if there is no measurement yet. */
    public static double millis() {
        return enabled() ? smoothedMillis : -1.0;
    }
}
