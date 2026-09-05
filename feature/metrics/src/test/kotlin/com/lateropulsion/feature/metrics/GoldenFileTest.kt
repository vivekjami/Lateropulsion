package com.lateropulsion.feature.metrics

import com.lateropulsion.core.model.MetricsConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * REQ-MET-030: every metric must match the independent Python implementation
 * (tools/analysis/reference_metrics.py) on >= 20 synthetic traces.
 */
class GoldenFileTest {
    private val dir = File(System.getProperty("lp.goldenDir"))
    private val tol = 1e-9

    @TestFactory
    fun `REQ-MET-030 golden traces match the Python reference`(): List<DynamicTest> {
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 20, "expected at least 20 golden traces, found ${files.size}")
        return files.map { f -> DynamicTest.dynamicTest(f.nameWithoutExtension) { check(f) } }
    }

    private fun check(f: File) {
        val root = Json.parseToJsonElement(f.readText()).jsonObject
        val fs = root["fs"]!!.jsonPrimitive.double
        val target = root["target_deg"]!!.jsonPrimitive.double
        val tolerance = root["tolerance_deg"]!!.jsonPrimitive.double
        val t = root["t"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        val theta = root["theta"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        val flags = root["flags"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        val expected = root["expected"]!!.jsonObject
        val em = expected["metrics"]!!.jsonObject

        val processed = TraceProcessor.process(RawTrace(t, theta, flags), fs, target, tolerance, MetricsConfig())
        val m = processed.metrics

        assertEquals(em["valid_samples"]!!.jsonPrimitive.int.toLong(), m.validSamples)
        assertEquals(em["total_samples"]!!.jsonPrimitive.int.toLong(), m.totalSamples)
        if (m.validSamples > 0) {
            assertNum(em, "mean_deg", m.meanDeg)
            assertNum(em, "mad_deg", m.madDeg)
            assertNum(em, "rms_deg", m.rmsDeg)
            assertNum(em, "max_deg", m.maxDeg)
            assertNum(em, "sd_deg", m.sdDeg)
            assertNum(em, "tib5_pct", m.tib5Pct)
            assertNum(em, "tib10_pct", m.tib10Pct)
            assertNum(em, "time_out_of_band_s", m.timeOutOfBandS)
            assertNum(em, "path_length_deg", m.pathLengthDeg)
            assertNum(em, "mean_velocity_deg_s", m.meanVelocityDegS)
            assertNum(em, "symmetry_index", m.symmetryIndex)
            assertNum(em, "valid_duration_s", m.validDurationS)
        }
        assertEquals(expected["filtered_sum"]!!.jsonPrimitive.double, processed.filteredDeg.sum(), 1e-6, "filtered sum")
        assertEquals(expected["filtered_abs_sum"]!!.jsonPrimitive.double, processed.filteredDeg.sumOf { kotlin.math.abs(it) }, 1e-6, "filtered abs sum")

        val eps = expected["episodes"]!!.jsonArray
        assertEquals(eps.size, processed.episodes.size, "episode count")
        for ((i, e) in eps.withIndex()) {
            val o = e.jsonObject
            val k = processed.episodes[i]
            assertEquals(o["start_s"]!!.jsonPrimitive.double, k.startS, tol, "episode $i start")
            val end = o["end_s"]
            if (end == null || end is JsonNull) assertEquals(null, k.endS) else assertEquals(end.jsonPrimitive.double, k.endS!!, tol, "episode $i end")
            assertEquals(o["peak_deg"]!!.jsonPrimitive.double, k.peakDeg, tol, "episode $i peak")
            assertEquals(o["partial"]!!.jsonPrimitive.content.toBoolean(), k.partial, "episode $i partial")
            val rec = o["recovery_s"]
            if (rec == null || rec is JsonNull) assertEquals(null, k.recoveryS) else assertEquals(rec.jsonPrimitive.double, k.recoveryS!!, tol, "episode $i recovery")
            assertEquals(o["direction"]!!.jsonPrimitive.content, k.direction.name, "episode $i direction")
        }

        // Live (causal) filter must also agree with the reference.
        val valid = processed.valid
        val filled = TraceProcessor.holdFill(theta, valid)
        val live = CausalFilter.live(fs)
        var liveSum = 0.0
        var last = 0.0
        for (x in filled) { last = live.step(x); liveSum += last }
        assertEquals(expected["live_sum"]!!.jsonPrimitive.double, liveSum, 1e-6, "live filter sum")
        expected["live_last"]?.takeIf { it !is JsonNull }?.let { assertEquals(it.jsonPrimitive.double, last, 1e-9, "live last") }
    }

    private fun assertNum(obj: Map<String, kotlinx.serialization.json.JsonElement>, key: String, actual: Double) {
        val p = obj[key]!!.jsonPrimitive
        val exp = p.doubleOrNull
        if (exp == null || (p is JsonPrimitive && p.content == "NaN")) {
            assertTrue(actual.isNaN(), "$key expected NaN but was $actual")
        } else {
            assertEquals(exp, actual, tol * maxOf(1.0, kotlin.math.abs(exp)), key)
        }
    }
}
