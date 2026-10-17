package org.malionaro.dlss_frame_generation.client.motion;

/**
 * Added to {@code EntityRenderState}: which entity a render state was
 * extracted from, so model parts drawn later can be matched across frames.
 */
public interface EntityIdHolder {
    /** -1 when the state does not belong to an entity. */
    int dlss_fg$entityId();

    void dlss_fg$setEntityId(int entityId);
}
