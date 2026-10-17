package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ray-traced world as a set of 16³ sections around the player.
 *
 * <p>The render thread decides which sections are wanted, copies their block
 * states ({@link SectionSnapshot}, cheap) and hands them to mesher threads
 * ({@link SectionMesher}, expensive). Finished meshes come back through
 * {@link #pollReady}; the renderer turns each into its own BLAS. Moving only
 * meshes the sections that came into range, and a block edit only its own
 * section, so the scene never stalls a frame with a full rescan.</p>
 *
 * <p>Every method is render-thread only except the mesher jobs.</p>
 */
public final class SectionScene implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.raytracing.scene");
    /** Ray-traced height around the player, in blocks above and below. */
    private static final int VERTICAL_RANGE = 32;
    private static final int CAPTURES_PER_FRAME = 24;
    private static final long CAPTURE_BUDGET_NANOS = 1_000_000L;
    private static final long NOT_LOADED_RETRY_NANOS = 500_000_000L;
    private static final int MAX_IN_FLIGHT = 64;

    private final ExecutorService mesher;
    private final SpriteTable sprites = new SpriteTable();
    private BlockAppearances appearances;
    private ClientLevel level;

    private final Map<Long, Long> generations = new HashMap<>();
    private long nextGeneration = 1L;
    private final Set<Long> desired = new HashSet<>();
    /** Sections whose latest mesh the renderer has (or that are empty). */
    private final Set<Long> resident = new HashSet<>();
    /**
     * LOD: sections beyond {@link #DETAIL_RANGE} blocks are meshed coarse (no
     * model triangles, no small decorations). Desired level per section, and
     * the level each captured mesh was built with.
     */
    public static final double DETAIL_RANGE = 48.0;
    private final Map<Long, Boolean> desiredCoarse = new HashMap<>();
    private final Map<Long, Boolean> meshedCoarse = new HashMap<>();
    private final ArrayDeque<Long> captureQueue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Map<Long, Long> notLoadedUntil = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ConcurrentLinkedQueue<SectionMesher.SectionMesh> completed = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<SectionMesher.SectionMesh> ready = new ArrayDeque<>();
    private final List<Long> removed = new ArrayList<>();

    private final Map<Long, List<SceneLights.Emitter>> lights = new HashMap<>();
    private boolean lightsDirty = true;
    private ByteBuffer lightBuffer = SceneLights.nearest(List.of(), 0, 0, 0, 0, 0, 0);
    private long lightsVersion;

    private int centerX = Integer.MIN_VALUE;
    private int centerY;
    private int centerZ;
    private int distance = -1;
    private boolean overflowWarned;

    public SectionScene() {
        AtomicInteger threads = new AtomicInteger();
        this.mesher = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "dlss_fg-rt-mesher-" + threads.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    public SpriteTable sprites() {
        return sprites;
    }

    /**
     * Advances section selection and capture by one frame's budget. The scene
     * follows {@code camera}, so third person and spectator views are covered.
     */
    public void update(Minecraft minecraft, int requestedDistance, net.minecraft.world.phys.Vec3 camera) {
        ClientLevel currentLevel = minecraft != null ? minecraft.level : null;
        if (currentLevel == null || minecraft.player == null || camera == null) {
            if (level != null) {
                resetAll();
                level = null;
            }
            return;
        }
        BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
        if (currentLevel != level || appearances == null || appearances.models() != models) {
            // New world, or a resource reload rebuilt models and the atlas.
            resetAll();
            sprites.clear();
            appearances = new BlockAppearances(models, sprites);
            level = currentLevel;
        }

        BlockPos player = BlockPos.containing(camera);
        int sectionX = SectionPos.blockToSectionCoord(player.getX());
        int sectionY = SectionPos.blockToSectionCoord(player.getY());
        int sectionZ = SectionPos.blockToSectionCoord(player.getZ());
        if (sectionX != centerX || sectionY != centerY || sectionZ != centerZ
                || requestedDistance != distance) {
            centerX = sectionX;
            centerY = sectionY;
            centerZ = sectionZ;
            distance = requestedDistance;
            // The origin moved with the centre section: lights are relative to it.
            lightsDirty = true;
            reselect(player);
        }
        requeueDirty();
        requeueNotLoaded();
        capture(minecraft.getBlockColors());
        collectCompleted();
        if (lightsDirty) {
            lightBuffer = SceneLights.nearest(lights.values(),
                    camera.x, camera.y, camera.z, originX(), originY(), originZ());
            lightsDirty = false;
            lightsVersion++;
        }
    }

    /** Recomputes the wanted sections around {@code player}; nearest first. */
    private void reselect(BlockPos player) {
        int horizontal = (distance + 15) >> 4;
        int vertical = (VERTICAL_RANGE + 15) >> 4;
        Set<Long> next = new HashSet<>();
        List<long[]> order = new ArrayList<>();
        double radiusSquared = (distance + 8.0) * (distance + 8.0);
        for (int dy = -vertical; dy <= vertical; dy++) {
            for (int dz = -horizontal; dz <= horizontal; dz++) {
                for (int dx = -horizontal; dx <= horizontal; dx++) {
                    int sx = centerX + dx;
                    int sy = centerY + dy;
                    int sz = centerZ + dz;
                    double cx = Math.max(sx * 16.0, Math.min(player.getX(), sx * 16.0 + 16.0)) - player.getX();
                    double cz = Math.max(sz * 16.0, Math.min(player.getZ(), sz * 16.0 + 16.0)) - player.getZ();
                    if (cx * cx + cz * cz > radiusSquared) {
                        continue;
                    }
                    long key = SectionPos.asLong(sx, sy, sz);
                    next.add(key);
                    boolean coarse = cx * cx + cz * cz > DETAIL_RANGE * DETAIL_RANGE;
                    desiredCoarse.put(key, coarse);
                    Boolean built = meshedCoarse.get(key);
                    if (built != null && built != coarse && resident.contains(key)) {
                        // Crossed the detail range: rebuild at the new level.
                        dirty.add(key);
                    }
                    order.add(new long[] {key, (long) dx * dx + (long) dy * dy * 4 + (long) dz * dz});
                }
            }
        }
        for (Long key : desired) {
            if (!next.contains(key)) {
                drop(key);
            }
        }
        desired.clear();
        desired.addAll(next);
        order.sort((a, b) -> Long.compare(a[1], b[1]));
        captureQueue.clear();
        queued.clear();
        for (long[] entry : order) {
            long key = entry[0];
            // generations holds sections already captured and still meshing.
            if (!resident.contains(key) && !generations.containsKey(key)
                    && !notLoadedUntil.containsKey(key)) {
                enqueue(key, false);
            }
        }
    }

    private void drop(long key) {
        desiredCoarse.remove(key);
        meshedCoarse.remove(key);
        generations.remove(key);
        notLoadedUntil.remove(key);
        dirty.remove(key);
        if (resident.remove(key)) {
            removed.add(key);
        }
        if (lights.remove(key) != null) {
            lightsDirty = true;
        }
    }

    private void enqueue(long key, boolean urgent) {
        if (!queued.add(key)) {
            return;
        }
        if (urgent) {
            captureQueue.addFirst(key);
        } else {
            captureQueue.addLast(key);
        }
    }

    private void requeueDirty() {
        if (dirty.isEmpty()) {
            return;
        }
        for (Long key : dirty) {
            if (desired.contains(key)) {
                enqueue(key, true);
            }
        }
        dirty.clear();
    }

    private void requeueNotLoaded() {
        if (notLoadedUntil.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        Iterator<Map.Entry<Long, Long>> iterator = notLoadedUntil.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                iterator.remove();
                if (desired.contains(entry.getKey())) {
                    enqueue(entry.getKey(), false);
                }
            }
        }
    }

    private void capture(BlockColors blockColors) {
        long deadline = System.nanoTime() + CAPTURE_BUDGET_NANOS;
        int captured = 0;
        while (!captureQueue.isEmpty()
                && captured < CAPTURES_PER_FRAME
                && inFlight.get() < MAX_IN_FLIGHT
                && System.nanoTime() < deadline) {
            long key = captureQueue.pollFirst();
            queued.remove(key);
            if (!desired.contains(key)) {
                continue;
            }
            captured++;
            Object snapshot = SectionSnapshot.capture(level, key);
            if (snapshot == SectionSnapshot.Result.NOT_LOADED) {
                notLoadedUntil.put(key, System.nanoTime() + NOT_LOADED_RETRY_NANOS);
                continue;
            }
            long generation = nextGeneration++;
            generations.put(key, generation);
            boolean coarse = desiredCoarse.getOrDefault(key, false);
            meshedCoarse.put(key, coarse);
            if (snapshot == SectionSnapshot.Result.EMPTY) {
                // Nothing to trace; drop an older mesh of this section.
                ready.add(SectionMesher.SectionMesh.empty(key, generation));
                continue;
            }
            SectionSnapshot section = (SectionSnapshot) snapshot;
            BlockAppearances looks = appearances;
            ClientLevel tintLevel = level;
            inFlight.incrementAndGet();
            mesher.execute(() -> {
                try {
                    completed.add(SectionMesher.mesh(section, generation, looks, blockColors, tintLevel, coarse));
                } catch (Throwable error) {
                    LOGGER.warn("RT section mesh failed at {}: {}", SectionPos.of(key), error.toString());
                    completed.add(SectionMesher.SectionMesh.empty(key, generation));
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    private void collectCompleted() {
        SectionMesher.SectionMesh mesh;
        while ((mesh = completed.poll()) != null) {
            ready.add(mesh);
        }
    }

    /**
     * Up to {@code max} meshes for the renderer, each the newest of its
     * section; a mesh with {@code boxCount == 0} means "section is empty".
     */
    public List<SectionMesher.SectionMesh> pollReady(int max) {
        List<SectionMesher.SectionMesh> result = new ArrayList<>();
        while (result.size() < max && !ready.isEmpty()) {
            SectionMesher.SectionMesh mesh = ready.pollFirst();
            Long current = generations.get(mesh.key());
            if (current == null || current != mesh.generation() || !desired.contains(mesh.key())) {
                continue;
            }
            resident.add(mesh.key());
            List<SceneLights.Emitter> previous = mesh.lights().isEmpty()
                    ? lights.remove(mesh.key())
                    : lights.put(mesh.key(), mesh.lights());
            if (previous != null || !mesh.lights().isEmpty()) {
                lightsDirty = true;
            }
            if (mesh.overflowed() && !overflowWarned) {
                overflowWarned = true;
                LOGGER.warn("RT section {} exceeded {} boxes; far parts of it are left out",
                        SectionPos.of(mesh.key()), SectionMesher.MAX_BOXES);
            }
            result.add(mesh);
        }
        return result;
    }

    /** Sections that left the scene since the last call; their GPU data can go. */
    public List<Long> drainRemoved() {
        if (removed.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(removed);
        removed.clear();
        return result;
    }

    /** A block changed: re-mesh its section, and neighbours it may uncover. */
    public void invalidate(BlockPos position) {
        if (position == null) {
            return;
        }
        int sx = SectionPos.blockToSectionCoord(position.getX());
        int sy = SectionPos.blockToSectionCoord(position.getY());
        int sz = SectionPos.blockToSectionCoord(position.getZ());
        dirty.add(SectionPos.asLong(sx, sy, sz));
        int lx = position.getX() & 15;
        int ly = position.getY() & 15;
        int lz = position.getZ() & 15;
        if (lx == 0) dirty.add(SectionPos.asLong(sx - 1, sy, sz));
        if (lx == 15) dirty.add(SectionPos.asLong(sx + 1, sy, sz));
        if (ly == 0) dirty.add(SectionPos.asLong(sx, sy - 1, sz));
        if (ly == 15) dirty.add(SectionPos.asLong(sx, sy + 1, sz));
        if (lz == 0) dirty.add(SectionPos.asLong(sx, sy, sz - 1));
        if (lz == 15) dirty.add(SectionPos.asLong(sx, sy, sz + 1));
    }

    /** Drops every section; the renderer receives them all through {@link #drainRemoved}. */
    public void resetAll() {
        desiredCoarse.clear();
        meshedCoarse.clear();
        removed.addAll(resident);
        resident.clear();
        desired.clear();
        generations.clear();
        captureQueue.clear();
        queued.clear();
        notLoadedUntil.clear();
        dirty.clear();
        ready.clear();
        completed.clear();
        lights.clear();
        lightsDirty = true;
        centerX = Integer.MIN_VALUE;
        distance = -1;
    }

    public ByteBuffer lightBuffer() {
        return lightBuffer.duplicate().order(lightBuffer.order());
    }

    public long lightsVersion() {
        return lightsVersion;
    }

    /**
     * Scene origin in blocks: the centre section's corner. Shader positions are
     * relative to it so they stay small and exact far from the world origin.
     */
    public int originX() {
        return centerX == Integer.MIN_VALUE ? 0 : centerX << 4;
    }

    public int originY() {
        return centerX == Integer.MIN_VALUE ? 0 : centerY << 4;
    }

    public int originZ() {
        return centerX == Integer.MIN_VALUE ? 0 : centerZ << 4;
    }

    public int residentCount() {
        return resident.size();
    }

    public int pendingCount() {
        return captureQueue.size() + inFlight.get() + ready.size();
    }

    @Override
    public void close() {
        mesher.shutdownNow();
    }
}
