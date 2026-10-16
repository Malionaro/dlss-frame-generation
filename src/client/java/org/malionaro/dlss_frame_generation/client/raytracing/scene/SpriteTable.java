package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Atlas UV rectangles of every sprite a ray-traced box references, indexed by
 * a small id stored in the box ({@link RtBoxFormat}). Id 0 is reserved for
 * "no texture". Mesher threads register sprites, the render thread uploads
 * the table when {@link #version()} changed.
 */
public final class SpriteTable {
    public static final int BYTES_PER_SPRITE = 16;

    private final Map<Object, Integer> ids = new IdentityHashMap<>();
    private float[] rects = new float[64 * 4];
    private int count = 1;
    private volatile long version;

    /** Returns the id for {@code sprite}, registering its rectangle on first use. */
    public synchronized int idFor(Object sprite, float u0, float v0, float u1, float v1) {
        if (sprite == null) {
            return 0;
        }
        Integer existing = ids.get(sprite);
        if (existing != null) {
            return existing;
        }
        if (count > RtBoxFormat.MAX_SPRITE_ID) {
            return 0;
        }
        int id = count++;
        if (id * 4 + 4 > rects.length) {
            float[] grown = new float[rects.length * 2];
            System.arraycopy(rects, 0, grown, 0, rects.length);
            rects = grown;
        }
        rects[id * 4] = u0;
        rects[id * 4 + 1] = v0;
        rects[id * 4 + 2] = u1;
        rects[id * 4 + 3] = v1;
        ids.put(sprite, id);
        version++;
        return id;
    }

    public long version() {
        return version;
    }

    public synchronized int count() {
        return count;
    }

    /** Snapshot as vec4(u0, v0, u1, v1) per id, native order, id 0 zeroed. */
    public synchronized ByteBuffer snapshot() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(count * BYTES_PER_SPRITE)
                .order(ByteOrder.nativeOrder());
        for (int index = 0; index < count * 4; index++) {
            buffer.putFloat(rects[index]);
        }
        return buffer.flip();
    }

    /** Forgets every sprite, e.g. after a resource reload rebuilt the atlas. */
    public synchronized void clear() {
        ids.clear();
        count = 1;
        java.util.Arrays.fill(rects, 0.0f);
        version++;
    }
}
