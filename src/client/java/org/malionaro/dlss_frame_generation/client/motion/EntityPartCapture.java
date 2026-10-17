package org.malionaro.dlss_frame_generation.client.motion;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.malionaro.dlss_frame_generation.client.mixin.ModelPartAccessor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Walks an entity model the way {@code ModelPart.render} does and records the
 * pose of every drawn part in {@link EntityPartMotion}.
 *
 * <p>Called from {@code ModelFeatureRenderer.prepareModel} right before the
 * model is drawn, after {@code setupAnim}. The walk repeats the vanilla
 * transforms instead of hooking {@code ModelPart} itself, because Sodium
 * replaces the cube drawing there.</p>
 */
public final class EntityPartCapture {
    /** Surface points farther away than this are not worth the per-pixel cost. */
    private static final float RANGE = 96.0f;
    /** Local bounds of each cube of a part, from its vertices (in blocks). */
    private static final Map<ModelPart, float[]> BOUNDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private EntityPartCapture() {}

    public static void capture(Object state, Model<?> model, PoseStack.Pose pose) {
        capture(state, model, pose, null, -1);
    }

    /**
     * @param renderType what the model is drawn with (its texture colours the
     *                   parts in RT), or null
     * @param tint       ARGB tint of the submit
     */
    public static void capture(Object state, Model<?> model, PoseStack.Pose pose,
                               net.minecraft.client.renderer.rendertype.RenderType renderType, int tint) {
        EntityPartMotion motion = EntityPartMotion.INSTANCE;
        if (!motion.enabled() || !(state instanceof EntityIdHolder holder)) {
            return;
        }
        int entityId = holder.dlss_fg$entityId();
        if (entityId < 0) {
            return;
        }
        Matrix4f root = pose.pose();
        float x = root.m30();
        float y = root.m31();
        float z = root.m32();
        if (x * x + y * y + z * z > RANGE * RANGE) {
            return;
        }
        PoseStack stack = new PoseStack();
        stack.last().set(pose);
        net.minecraft.resources.Identifier texture =
                renderType != null ? EntityTextureColors.textureOf(renderType) : null;
        visit(motion, entityId, model.root(), stack, texture, tint);
    }

    private static void visit(EntityPartMotion motion, int entityId, ModelPart part, PoseStack stack,
                              net.minecraft.resources.Identifier texture, int tint) {
        if (!part.visible) {
            return;
        }
        ModelPartAccessor access = (ModelPartAccessor) (Object) part;
        if (access.dlss_fg$cubes().isEmpty() && access.dlss_fg$children().isEmpty()) {
            return;
        }
        stack.pushPose();
        part.translateAndRotate(stack);
        if (!part.skipDraw && !access.dlss_fg$cubes().isEmpty()) {
            float[] bounds = BOUNDS.computeIfAbsent(part, EntityPartCapture::bounds);
            int[] colors = EntityTextureColors.cubeColors(part, access.dlss_fg$cubes(), texture, tint);
            motion.record(entityId, part, stack.last().pose(), bounds, colors);
        }
        for (ModelPart child : access.dlss_fg$children().values()) {
            visit(motion, entityId, child, stack, texture, tint);
        }
        stack.popPose();
    }

    private static float[] bounds(ModelPart part) {
        List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).dlss_fg$cubes();
        float[] bounds = new float[cubes.size() * 6];
        for (int index = 0; index < cubes.size(); index++) {
            ModelPart.Cube cube = cubes.get(index);
            int at = index * 6;
            PartBounds.reset(bounds, at);
            for (ModelPart.Polygon polygon : cube.polygons) {
                for (ModelPart.Vertex vertex : polygon.vertices()) {
                    PartBounds.include(bounds, at, vertex.worldX(), vertex.worldY(), vertex.worldZ());
                }
            }
            if (PartBounds.isEmpty(bounds, at)) {
                // No faces drawn: the cube's own extent.
                PartBounds.include(bounds, at, cube.minX / 16.0f, cube.minY / 16.0f, cube.minZ / 16.0f);
                PartBounds.include(bounds, at, cube.maxX / 16.0f, cube.maxY / 16.0f, cube.maxZ / 16.0f);
            }
        }
        return bounds;
    }
}
