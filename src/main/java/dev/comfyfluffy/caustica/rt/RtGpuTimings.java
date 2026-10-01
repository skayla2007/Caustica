package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Optional GPU timestamps read only after the owning graphics ring slot has completed. */
final class RtGpuTimings {
    static final boolean ENABLED = Boolean.getBoolean("caustica.rt.gpuTimings");
    private static final int POINTS = 8;
    private final RtContext ctx;
    private final long pool, mask;
    private final double tickMs;
    private final boolean[] written;
    private final double[] sums = new double[POINTS - 1];
    private long reportAt = System.nanoTime();
    private int samples;

    RtGpuTimings(RtContext ctx, int slots) {
        this.ctx = ctx;
        written = new boolean[slots];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var phys = ctx.vk().getPhysicalDevice();
            var props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(phys, props);
            tickMs = props.limits().timestampPeriod() / 1e6;
            var count = stack.ints(0);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(phys, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(phys, count, families);
            int bits = families.get(ctx.device().graphicsQueue().queueFamilyIndex()).timestampValidBits();
            mask = bits == 64 ? -1L : (1L << bits) - 1;
            if (bits == 0) throw new IllegalStateException("Graphics queue has no timestamp support");
            var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(slots * POINTS);
            var output = stack.mallocLong(1);
            if (VK10.vkCreateQueryPool(ctx.vk(), info, null, output) != VK10.VK_SUCCESS)
                throw new IllegalStateException("Cannot create RT timestamp pool");
            pool = output.get(0);
        }
    }

    void begin(VkCommandBuffer cmd, int slot) {
        if (written[slot]) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var values = stack.mallocLong(POINTS);
                int result = VK10.vkGetQueryPoolResults(ctx.vk(), pool, slot * POINTS, POINTS,
                        values, Long.BYTES, VK10.VK_QUERY_RESULT_64_BIT);
                if (result == VK10.VK_SUCCESS) {
                    for (int i = 0; i < sums.length; i++)
                        sums[i] += ((values.get(i + 1) - values.get(i)) & mask) * tickMs;
                    samples++;
                } else if (result != VK10.VK_NOT_READY) throw new IllegalStateException("RT timestamps: " + result);
            }
        }
        long now = System.nanoTime();
        if (samples > 0 && now - reportAt >= 2_000_000_000L) {
            CausticaMod.LOGGER.info("RT GPU ms entity={} tlas={} sky={} primary={} indirect={} reconstruction={} post={} samples={}",
                    sums[0]/samples, sums[1]/samples, sums[2]/samples, sums[3]/samples,
                    sums[4]/samples, sums[5]/samples, sums[6]/samples, samples);
            java.util.Arrays.fill(sums, 0); samples = 0; reportAt = now;
        }
        VK10.vkCmdResetQueryPool(cmd, pool, slot * POINTS, POINTS);
        written[slot] = true;
        mark(cmd, slot, 0);
    }

    void mark(VkCommandBuffer cmd, int slot, int point) {
        VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, slot * POINTS + point);
    }

    void destroy() { VK10.vkDestroyQueryPool(ctx.vk(), pool, null); }
}
