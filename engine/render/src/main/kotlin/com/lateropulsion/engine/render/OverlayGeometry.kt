package com.lateropulsion.engine.render

import com.lateropulsion.core.model.CueType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Colours as RGBA floats. */
public data class Rgba(val r: Float, val g: Float, val b: Float, val a: Float) {
    public companion object {
        public val WHITE: Rgba = Rgba(1f, 1f, 1f, 0.95f)
        public val GREEN: Rgba = Rgba(0.30f, 0.85f, 0.45f, 0.55f)
        public val AMBER: Rgba = Rgba(0.95f, 0.70f, 0.20f, 0.55f)
        public val RED: Rgba = Rgba(0.95f, 0.30f, 0.30f, 0.60f)
        public val CYAN: Rgba = Rgba(0.35f, 0.80f, 0.95f, 0.9f)
        public val GREY: Rgba = Rgba(0.8f, 0.8f, 0.8f, 0.6f)
    }
}

public sealed interface Primitive {
    public data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, val color: Rgba) : Primitive
    /** Filled triangle fan around (cx, cy) from angle a0 to a1 (radians, 0 = +x, counter-clockwise). */
    public data class Wedge(val cx: Float, val cy: Float, val radius: Float, val a0: Float, val a1: Float, val color: Rgba) : Primitive
    public data class Ring(val cx: Float, val cy: Float, val rInner: Float, val rOuter: Float, val a0: Float, val a1: Float, val color: Rgba) : Primitive
    public data class Marker(val x: Float, val y: Float, val size: Float, val color: Rgba) : Primitive
}

/** Everything the overlay needs each frame. */
public data class OverlayInput(
    val thetaHeadDeg: Double,
    val thetaDeg: Double,
    val state: RenderState,
    val valid: Boolean,
)

/**
 * Gravity-locked cue geometry (ARCHITECTURE §7.4). All coordinates are in eye space with the optical
 * centre at (0, 0), +y up, and 1 = half the eye's vertical field; the renderer projects per eye.
 *
 * When the head rolls right by θ, world-vertical appears rotated counter-clockwise by θ in head/screen
 * coordinates (+x right, +y up), so gravity-locked geometry is rotated by +θ_head: a 30° right roll draws
 * the plumb line with its top leaning 30° to the left of screen-up.
 */
public object OverlayGeometry {
    private const val DEG = PI / 180.0

    public fun build(input: OverlayInput): List<Primitive> {
        val s = input.state
        if (s.idle) return emptyList()
        val out = ArrayList<Primitive>(8)
        val a = input.thetaHeadDeg * DEG // ccw rotation applied to world-vertical geometry (see class doc)
        val dev = input.thetaDeg - s.targetDeg
        val inPrimary = abs(dev) <= s.bandPrimaryDeg
        val inTolerance = abs(dev) <= s.toleranceDeg
        val bandColor = when {
            !input.valid -> Rgba.GREY
            inPrimary -> Rgba.GREEN
            inTolerance -> Rgba.AMBER
            else -> Rgba.RED
        }

        if (CueType.TOLERANCE_BAND in s.cues) {
            // Wedge centred on true vertical (world up), ± tolerance, drawn from the optical centre upward.
            val up = PI / 2 + a - s.targetDeg * DEG
            val half = s.toleranceDeg * DEG
            out += Primitive.Wedge(0f, 0f, 0.9f, (up - half).toFloat(), (up + half).toFloat(), bandColor)
            val halfP = s.bandPrimaryDeg * DEG
            out += Primitive.Ring(0f, 0f, 0.86f, 0.9f, (up - halfP).toFloat(), (up + halfP).toFloat(), Rgba.WHITE.copy(a = 0.5f))
        }
        if (CueType.PLUMB_LINE in s.cues) {
            val (x1, y1) = rot(0.0, -0.95, a)
            val (x2, y2) = rot(0.0, 0.95, a)
            out += Primitive.Line(x1, y1, x2, y2, 0.012f, Rgba.WHITE)
        }
        if (CueType.HORIZON in s.cues) {
            val (x1, y1) = rot(-1.3, 0.0, a)
            val (x2, y2) = rot(1.3, 0.0, a)
            out += Primitive.Line(x1, y1, x2, y2, 0.008f, Rgba.CYAN)
        }
        if (CueType.TARGET in s.cues) {
            // Target column at the target angle (world frame), i.e. where the head should be.
            val ta = a - s.targetDeg * DEG
            val (x1, y1) = rot(0.0, 0.2, ta)
            val (x2, y2) = rot(0.0, 0.8, ta)
            out += Primitive.Line(x1, y1, x2, y2, 0.02f, if (inTolerance) Rgba.GREEN.copy(a = 0.9f) else Rgba.AMBER.copy(a = 0.9f))
            out += Primitive.Marker(x2, y2, 0.05f, Rgba.WHITE)
        }
        if (CueType.PROGRESS_RING in s.cues) {
            val start = PI / 2
            out += Primitive.Ring(0f, -0.75f, 0.10f, 0.13f, start.toFloat(), (start - 2 * PI * s.progress01.coerceIn(0.0, 1.0)).toFloat(), Rgba.WHITE.copy(a = 0.8f))
        }
        if (CueType.DEVIATION_READOUT in s.cues && s.showReadout) {
            // Head-locked marker below the ring showing tilt direction; text is drawn by the renderer.
            val dir = if (dev >= 0) 1f else -1f
            out += Primitive.Marker(dir * 0.3f, -0.9f, (0.03 + 0.002 * abs(dev)).toFloat().coerceAtMost(0.08f), bandColor.copy(a = 0.95f))
        }
        return out
    }

    /** Rotate (x, y) about the origin by angle (radians, counter-clockwise). */
    public fun rot(x: Double, y: Double, angle: Double): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        return (x * c - y * s).toFloat() to (x * s + y * c).toFloat()
    }

    /** Audio pan in [-1, 1] and loudness in [0, 1] from deviation (README §7.4 audio cue). */
    public fun audioPan(thetaDeg: Double, toleranceDeg: Double): Pair<Float, Float> {
        val pan = (thetaDeg / (3 * toleranceDeg)).coerceIn(-1.0, 1.0).toFloat()
        val loud = (abs(thetaDeg) / (2 * toleranceDeg)).coerceIn(0.0, 1.0).toFloat()
        return pan to loud
    }
}
