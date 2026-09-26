package org.malionaro.dlss_frame_generation.client.render;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Rotating per-slot GPU fence gate for resources the mod records into
 * Minecraft's command buffers but never submits itself.
 *
 * <p>Each slot owns a {@code VkFence} plus an empty marker command buffer.
 * {@link #markSlot(int)} arms the fence and submits the marker on Minecraft's
 * graphics queue <em>after</em> the caller recorded its work: queue order
 * guarantees the fence signals only once every previously submitted command
 * buffer completed, including the frame that last consumed the slot.
 * {@link #awaitSlot(int, long)} blocks (bounded) for exactly that.</p>
 *
 * <p>Fail-closed by design: any setup/submit/wait failure disables the fence
 * path and every wait reports <em>not confirmed</em> (or falls back to a
 * conservative CPU sequence heuristic), so callers must skip/delay reuse
 * instead of overwriting in-flight GPU resources. Timeouts never retire a
 * slot: the next frame simply retries the wait.</p>
 *
 * <p>All methods are intended for the render thread; initialization is lazy
 * on first use so construction is cheap and safe anywhere.</p>
 */
public final class SlotFenceGate implements AutoCloseable {
    /** Upper bound for a single wait so the render thread can never stall long. */
    private static final long MAX_WAIT_NANOS = 100_000_000L;
    /** Minimum interval between repeated wait-failure warnings (log-spam guard). */
    private static final long WARN_THROTTLE_NANOS = 1_000_000_000L;

    private final VulkanDevice minecraftDevice;
    private final Logger logger;
    private final String name;
    private final int slotCount;

    private final long[] slotFences;
    private final VkCommandBuffer[] slotMarkers;
    private final boolean[] slotInFlight;
    private final long[] slotMarkedSequence;

    private long commandPool = NULL;
    private boolean initializationAttempted;
    private boolean fenceSyncAvailable;
    private long markCounter;
    private long lastWarnNanos;

    public SlotFenceGate(VulkanDevice minecraftDevice, Logger logger, String name, int slotCount) {
        this.minecraftDevice = minecraftDevice;
        this.logger = logger;
        this.name = name;
        this.slotCount = Math.max(1, slotCount);
        this.slotFences = new long[this.slotCount];
        this.slotMarkers = new VkCommandBuffer[this.slotCount];
        this.slotInFlight = new boolean[this.slotCount];
        this.slotMarkedSequence = new long[this.slotCount];
    }

    /**
     * Waits (bounded by {@code timeoutNanos}, clamped to 100&nbsp;ms) until the
     * given slot is safe to reuse.
     *
     * @return {@code true} only when GPU completion was confirmed (or the slot
     *         was never in flight); {@code false} when the caller must skip or
     *         delay reuse. Never throws.
     */
    public boolean awaitSlot(int slot, long timeoutNanos) {
        if (slot < 0 || slot >= slotCount) {
            return true;
        }
        if (!slotInFlight[slot]) {
            return true;
        }
        ensureInitialized();
        if (!fenceSyncAvailable || slotFences[slot] == NULL) {
            return awaitSlotCpuFallback(slot);
        }
        VkDevice device = vkDevice();
        if (device == null) {
            return awaitSlotCpuFallback(slot);
        }
        long clamped = Math.max(0L, Math.min(timeoutNanos, MAX_WAIT_NANOS));
        int result;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            result = VK13.vkWaitForFences(device, stack.longs(slotFences[slot]), true, clamped);
        } catch (Throwable throwable) {
            warnThrottled("SlotFenceGate '{}': slot {} fence wait threw; keeping slot in flight ({})",
                    slot, throwable.toString());
            return false;
        }
        if (result == VK_SUCCESS) {
            retireSlot(slot);
            return true;
        }
        if (result == VK_TIMEOUT) {
            warnThrottled("SlotFenceGate '{}': slot {} still in flight after {}ms; deferring reuse",
                    slot, clamped / 1_000_000L);
        } else {
            warnThrottled("SlotFenceGate '{}': slot {} fence wait failed with VkResult {}; deferring reuse",
                    slot, result);
        }
        return false;
    }

    /**
     * Arms the slot fence and queues a drain-marker behind the work the caller
     * just recorded. Never throws; on failure the fence path is disabled and
     * {@link #awaitSlot} degrades to the CPU heuristic.
     */
    public void markSlot(int slot) {
        if (slot < 0 || slot >= slotCount) {
            return;
        }
        ensureInitialized();
        slotInFlight[slot] = true;
        slotMarkedSequence[slot] = ++markCounter;
        long fence = slotFences[slot];
        VkCommandBuffer marker = slotMarkers[slot];
        VkQueue queue = graphicsQueue();
        if (!fenceSyncAvailable || fence == NULL || marker == null || queue == null) {
            return;
        }
        VkDevice device = vkDevice();
        if (device == null) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK13.vkResetFences(device, stack.longs(fence));
            check(vkBeginCommandBuffer(marker, VkCommandBufferBeginInfo.calloc(stack)
                            .sType$Default()),
                    "begin " + name + " slot marker command buffer");
            check(vkEndCommandBuffer(marker), "end " + name + " slot marker command buffer");
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(marker.address()));
            check(vkQueueSubmit(queue, submitInfo, fence),
                    "submit " + name + " slot fence marker");
        } catch (Throwable throwable) {
            fenceSyncAvailable = false;
            logger.warn("SlotFenceGate '{}': slot {} fence marker submit failed ({}); "
                            + "fence synchronisation disabled, using CPU sequence tracking",
                    name, slot, throwable.toString());
        }
    }

    /** Retires every slot without touching the GPU (device loss / re-registration). */
    public void retireAll() {
        for (int index = 0; index < slotCount; index++) {
            slotInFlight[index] = false;
        }
    }

    @Override
    public void close() {
        VkDevice device = vkDevice();
        if (device != null) {
            for (int index = 0; index < slotCount; index++) {
                if (slotFences[index] != NULL) {
                    try {
                        vkDestroyFence(device, slotFences[index], null);
                    } catch (Throwable ignored) {
                    }
                    slotFences[index] = NULL;
                }
                slotMarkers[index] = null;
            }
            if (commandPool != NULL) {
                try {
                    vkDestroyCommandPool(device, commandPool, null);
                } catch (Throwable ignored) {
                }
                commandPool = NULL;
            }
        }
        fenceSyncAvailable = false;
        retireAll();
    }

    private boolean awaitSlotCpuFallback(int slot) {
        // Conservative rotation heuristic (mirrors the RT renderer's legacy
        // fallback): a slot is reused every slotCount marks, so once at least
        // slotCount-1 newer marks exist the previous use is two generations old.
        if (markCounter - slotMarkedSequence[slot] >= (long) slotCount - 1L) {
            retireSlot(slot);
            return true;
        }
        return false;
    }

    private void retireSlot(int slot) {
        slotInFlight[slot] = false;
    }

    private void ensureInitialized() {
        if (initializationAttempted) {
            return;
        }
        initializationAttempted = true;
        VkDevice device = vkDevice();
        var queueState = minecraftDevice != null ? minecraftDevice.graphicsQueue() : null;
        if (device == null || queueState == null) {
            logger.warn("SlotFenceGate '{}': graphics queue unavailable; using CPU sequence tracking",
                    name);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueState.queueFamilyIndex());
            check(vkCreateCommandPool(device, poolInfo, null, result),
                    "create " + name + " slot-fence command pool");
            commandPool = result.get(0);

            VkCommandBufferAllocateInfo allocateInfo =
                    VkCommandBufferAllocateInfo.calloc(stack)
                            .sType$Default()
                            .commandPool(commandPool)
                            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                            .commandBufferCount(slotCount);
            PointerBuffer buffers = stack.mallocPointer(slotCount);
            check(vkAllocateCommandBuffers(device, allocateInfo, buffers),
                    "allocate " + name + " slot marker command buffers");
            for (int index = 0; index < slotCount; index++) {
                slotMarkers[index] = new VkCommandBuffer(buffers.get(index), device);
            }

            for (int index = 0; index < slotCount; index++) {
                VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_FENCE_CREATE_SIGNALED_BIT);
                check(vkCreateFence(device, fenceInfo, null, result),
                        "create " + name + " slot fence");
                slotFences[index] = result.get(0);
            }
        } catch (Throwable throwable) {
            close();
            logger.warn("SlotFenceGate '{}': per-slot VkFence synchronisation unavailable ({}); "
                            + "using CPU sequence tracking",
                    name, throwable.toString());
            return;
        }
        fenceSyncAvailable = true;
    }

    private VkDevice vkDevice() {
        try {
            return minecraftDevice != null ? minecraftDevice.vkDevice() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private VkQueue graphicsQueue() {
        try {
            var queueState = minecraftDevice != null ? minecraftDevice.graphicsQueue() : null;
            return queueState != null ? queueState.vkQueue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void warnThrottled(String format, Object... args) {
        long now = System.nanoTime();
        if (now - lastWarnNanos < WARN_THROTTLE_NANOS) {
            return;
        }
        lastWarnNanos = now;
        logger.warn(format, name, args);
    }

    private static void check(int result, String action) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(action + " failed with VkResult " + result);
        }
    }
}
