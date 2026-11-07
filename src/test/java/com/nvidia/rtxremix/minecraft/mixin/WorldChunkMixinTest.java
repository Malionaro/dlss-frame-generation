/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.*;

class WorldChunkMixinTest {
    static {
        try {
            SharedConstants.getCurrentVersion();
        } catch (IllegalStateException unset) {
            SharedConstants.tryDetectVersion();
        }
        Bootstrap.bootStrap();
    }

    @Test
    void absentSkySourcesInvokeTheOriginalWithoutReadingPacketHeightmaps() throws Exception {
        LevelChunk chunk = chunkWithoutSkySources();
        AtomicInteger calls = new AtomicInteger();
        Operation<Void> original = args -> {
            assertSame(chunk, args[0]);
            calls.incrementAndGet();
            return null;
        };
        invoke(chunk, original);
        assertEquals(1, calls.get());
    }

    @Test
    void theReplacementEnginesInitializationFailureIsPropagated() throws Exception {
        IllegalStateException failure = new IllegalStateException("Replacement engine failure");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> invoke(chunkWithoutSkySources(), args -> { throw failure; }));
        assertSame(failure, thrown.getCause());
    }

    private static void invoke(LevelChunk chunk, Operation<Void> original) throws Exception {
        Method method = WorldChunkMixin.class.getDeclaredMethod("rtxremix$seedSurfaceScan", LevelChunk.class,
                Operation.class, int.class, int.class, ClientboundLevelChunkPacketData.class);
        method.setAccessible(true);
        method.invoke(new WorldChunkMixin() {}, chunk, original, 0, 0, null);
    }

    private static LevelChunk chunkWithoutSkySources() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        // The guard reads only the sky-source field; no world or GPU is needed.
        return (LevelChunk) ((Unsafe) field.get(null)).allocateInstance(LevelChunk.class);
    }
}
