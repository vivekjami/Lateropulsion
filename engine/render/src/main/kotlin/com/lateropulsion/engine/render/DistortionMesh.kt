package com.lateropulsion.engine.render

import kotlin.math.sqrt

/**
 * Per-eye lens distortion mesh (ARCHITECTURE §7.1). For each screen-space vertex at radius r from the
 * lens centre (normalised so r = 1 at the eye viewport's half-height), the texture is sampled at
 * r·(1 + k1 r² + k2 r⁴): drawing the pre-distorted image undoes the lens's pincushion. Red and blue
 * sample at slightly different radii for chromatic aberration.
 *
 * Output arrays are ready for GL: positions in NDC of the eye viewport, three sets of texcoords into the
 * eye's region of the offscreen framebuffer.
 */
public class DistortionMesh(
    public val gridN: Int,
    public val k1: Double,
    public val k2: Double,
    public val redScale: Double,
    public val blueScale: Double,
    /** Texture-space centre of this eye's image and its half extents (u in [uMin, uMax]). */
    public val uMin: Float,
    public val uMax: Float,
    public val centerX: Float = 0f,
    public val centerY: Float = 0f,
    /** Aspect of the eye viewport (width/height) so r is isotropic. */
    public val aspect: Float,
) {
    public val vertexCount: Int = (gridN + 1) * (gridN + 1)
    public val positions: FloatArray = FloatArray(vertexCount * 2)
    public val texR: FloatArray = FloatArray(vertexCount * 2)
    public val texG: FloatArray = FloatArray(vertexCount * 2)
    public val texB: FloatArray = FloatArray(vertexCount * 2)
    public val indices: ShortArray = ShortArray(gridN * gridN * 6)

    init {
        require(gridN in 2..255)
        var v = 0
        for (j in 0..gridN) for (i in 0..gridN) {
            val x = -1f + 2f * i / gridN
            val y = -1f + 2f * j / gridN
            positions[v * 2] = x; positions[v * 2 + 1] = y
            // isotropic radius from the lens centre
            val dx = (x - centerX) * aspect
            val dy = y - centerY
            val r = sqrt((dx * dx + dy * dy).toDouble())
            val f = distort(r)
            writeTex(texG, v, x, y, f)
            writeTex(texR, v, x, y, f * redScale)
            writeTex(texB, v, x, y, f * blueScale)
            v++
        }
        var k = 0
        for (j in 0 until gridN) for (i in 0 until gridN) {
            val a = (j * (gridN + 1) + i).toShort()
            val b = (a + 1).toShort()
            val c = (a + gridN + 1).toShort()
            val d = (c + 1).toShort()
            indices[k++] = a; indices[k++] = c; indices[k++] = b
            indices[k++] = b; indices[k++] = c; indices[k++] = d
        }
    }

    /** Radial scale factor for a vertex at radius r. */
    public fun distort(r: Double): Double = 1.0 + k1 * r * r + k2 * r * r * r * r

    private fun writeTex(dst: FloatArray, v: Int, x: Float, y: Float, f: Double) {
        // sample point in NDC of the eye image, scaled about the lens centre
        val sx = centerX + (x - centerX) * f.toFloat()
        val sy = centerY + (y - centerY) * f.toFloat()
        // NDC → texcoords within this eye's half of the framebuffer
        dst[v * 2] = uMin + (uMax - uMin) * (sx + 1f) / 2f
        dst[v * 2 + 1] = (sy + 1f) / 2f
    }

    public companion object {
        public fun forEye(left: Boolean, gridN: Int, k1: Double, k2: Double, red: Double, blue: Double, aspect: Float, centerOffsetY: Float = 0f): DistortionMesh =
            DistortionMesh(gridN, k1, k2, red, blue, if (left) 0f else 0.5f, if (left) 0.5f else 1f, 0f, centerOffsetY, aspect)
    }
}
