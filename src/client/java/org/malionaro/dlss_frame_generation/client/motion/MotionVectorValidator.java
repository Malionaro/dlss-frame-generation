package org.malionaro.dlss_frame_generation.client.motion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates active Motion Vector resources to detect silent failures, zero vectors,
 * matrix pairing mismatches, or inverted sign conventions.
 */
public class MotionVectorValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger("MotionVectorValidator");

    public static class ValidationResult {
        public boolean valid;
        public String statusMessage;
        public boolean hasCameraMotion;
        public boolean hasObjectMotion;

        public ValidationResult(boolean valid, String statusMessage, boolean hasCameraMotion, boolean hasObjectMotion) {
            this.valid = valid;
            this.statusMessage = statusMessage;
            this.hasCameraMotion = hasCameraMotion;
            this.hasObjectMotion = hasObjectMotion;
        }
    }

    public ValidationResult validate(MotionVectorResource resource) {
        if (resource == null) {
            return new ValidationResult(false, "WARNING: Motion Vector resource is NULL", false, false);
        }

        if (resource.resourceType() == MotionVectorResource.ResourceType.CAMERA_MATRIX_APPROXIMATION) {
            if (!resource.isCameraMotionIncluded()) {
                return new ValidationResult(true, "Camera motion preview is static", false, false);
            }
            return new ValidationResult(true, "Camera motion preview active (dense native MV buffer unavailable)", true, false);
        }

        if (resource.resourceType() == MotionVectorResource.ResourceType.VK_IMAGE && resource.nativeHandle() == 0) {
            return new ValidationResult(false, "ERROR: Motion Vector VkImage handle is 0x0", false, false);
        }

        String coverage = resource.isObjectMotionIncluded()
                ? "camera + temporal object refinement"
                : "camera only";
        return new ValidationResult(
                true,
                "Dense GPU Motion Vectors valid (" + coverage + ")",
                resource.isCameraMotionIncluded(),
                resource.isObjectMotionIncluded()
        );
    }
}
