package dev.comfyfluffy.caustica.rt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Driver estimates for this process; the budget also reflects competing system GPU usage. */
public final class RtMemoryBudget {
    public record Snapshot(long localUsage, long localBudget, long nonLocalUsage) {}
    private final RtContext ctx;
    private final boolean supported;

    public RtMemoryBudget(RtContext ctx) {
        this.ctx = ctx;
        boolean found = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var count = stack.ints(0);
            VK10.vkEnumerateDeviceExtensionProperties(ctx.vk().getPhysicalDevice(), (String) null, count, null);
            try (var extensions = VkExtensionProperties.calloc(count.get(0))) {
                VK10.vkEnumerateDeviceExtensionProperties(ctx.vk().getPhysicalDevice(), (String) null, count, extensions);
                for (int i = 0; i < extensions.limit(); i++)
                    if (extensions.get(i).extensionNameString().equals(EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME)) found = true;
            }
        }
        supported = found;
    }

    public Snapshot read() {
        if (!supported) return new Snapshot(0, 0, 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var budget = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
            var props = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default().pNext(budget.address());
            VK11.vkGetPhysicalDeviceMemoryProperties2(ctx.vk().getPhysicalDevice(), props);
            long localUsage = 0, localBudget = 0, nonLocalUsage = 0;
            var memory = props.memoryProperties();
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                if ((memory.memoryHeaps(i).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    localUsage += budget.heapUsage(i); localBudget += budget.heapBudget(i);
                } else nonLocalUsage += budget.heapUsage(i);
            }
            return new Snapshot(localUsage, localBudget, nonLocalUsage);
        }
    }
}
