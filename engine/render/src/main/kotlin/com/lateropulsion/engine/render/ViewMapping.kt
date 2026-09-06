package com.lateropulsion.engine.render

/**
 * Pure geometry shared by the passthrough shader and its tests (ADR-019).
 *
 * The camera image is never stretched to the viewport: it is scaled uniformly until it covers the viewport
 * and the overflow is cropped symmetrically ("cover"). Squeezing a 16:9 camera into a 1.1:1 eye viewport,
 * or stretching it across a 20:9 phone screen, would turn a 30° real-world roll into a different angle on
 * screen while the gravity-locked overlays stay exact, and the plumb-line check would then fail for the
 * wrong reason.
 */
public object ViewMapping {
    /** Width and height of the covering camera rectangle in units of the viewport height. */
    public data class Cover(val width: Double, val height: Double)

    /**
     * @param viewportAspect viewport width / height.
     * @param cameraAspect camera image width / height *after* the quarter-turn rotation that makes it upright.
     */
    public fun cover(viewportAspect: Double, cameraAspect: Double): Cover {
        require(viewportAspect > 0 && cameraAspect > 0)
        val w = maxOf(viewportAspect, cameraAspect)
        return Cover(w, w / cameraAspect)
    }

    /**
     * Viewport point (x in [0, aspect], y in [0, 1], isotropic units of the viewport height) → camera texture
     * coordinates in [0, 1]² of the upright camera image; values outside [0, 1] mean "no camera pixel here".
     */
    public fun toCamera(x: Double, y: Double, viewportAspect: Double, cover: Cover): Pair<Double, Double> =
        ((x - viewportAspect / 2) / cover.width + 0.5) to ((y - 0.5) / cover.height + 0.5)

    /** A buffer of aspect [bufferAspect] turned by an odd number of quarter turns has the reciprocal aspect. */
    public fun turnedAspect(bufferAspect: Double, quarterTurns: Int): Double =
        if (((quarterTurns % 4) + 4) % 4 % 2 == 1) 1.0 / bufferAspect else bufferAspect

    /** Horizontal half-extent (in viewport-height units) that a gravity-locked horizon needs to span the viewport at any roll. */
    public fun horizonHalfExtent(viewportAspect: Double): Double = kotlin.math.hypot(viewportAspect, 1.0) / 2 + HORIZON_MARGIN

    private const val HORIZON_MARGIN = 0.1
}
