package com.lateropulsion.feature.assessment

import com.lateropulsion.core.model.LpJson
import com.lateropulsion.core.model.ScaleDefinition
import com.lateropulsion.core.model.ValidityFlags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class AssessmentTest {
    private val scalesDir = File(System.getProperty("lp.configDir"), "scales")
    private fun load(name: String) = LpJson.strict.decodeFromString(ScaleDefinition.serializer(), File(scalesDir, name).readText())

    @Test
    fun `REQ-PAT-010 every shipped scale parses and is structurally valid`() {
        val files = scalesDir.listFiles { f -> f.extension == "json" }!!
        assertEquals(8, files.size)
        for (f in files) {
            val r = ScaleLoader.parse(f.readText())
            assertTrue(r.isSuccess, "${f.name}: ${r.errorOrNull()?.message}")
        }
        val codes = files.map { load(it.name).code }.toSet()
        assertEquals(setOf("SCP", "BLS", "4PPS", "FAC", "TCT", "BERG", "PASS", "SSQ"), codes)
    }

    @Test
    fun `SCP scoring sums six components to 0-6`() {
        val scp = load("scp.json")
        val worst = scp.items.associate { it.id to it.options.lastIndex }
        assertEquals(6.0, ScaleScorer.score(scp, worst).totalScore, 1e-9)
        val partial = mapOf("A_sit" to 1, "A_stand" to 2, "B_sit" to 1, "B_stand" to 2, "C_sit" to 1, "C_stand" to 0)
        val r = ScaleScorer.score(scp, partial)
        assertEquals(0.25 + 0.75 + 0.5 + 1.0 + 1.0, r.totalScore, 1e-9)
        assertTrue(r.complete)
        val incomplete = ScaleScorer.score(scp, mapOf("A_sit" to 1))
        assertFalse(incomplete.complete)
        assertEquals(5, incomplete.missingItems.size)
    }

    @Test
    fun `REQ-SAF-010 SSQ weighting matches Kennedy et al`() {
        val ssq = load("ssq.json")
        val allOne = ssq.items.associate { it.id to 1 }
        val s = SsqScoring.score(ssq, allOne, flagThresholdTotal = 20.0)
        assertEquals(7 * 9.54, s.nausea, 1e-9)
        assertEquals(7 * 7.58, s.oculomotor, 1e-9)
        assertEquals(7 * 13.92, s.disorientation, 1e-9)
        assertEquals(21 * 3.74, s.total, 1e-9)
        assertTrue(s.flagged)
        val none = SsqScoring.score(ssq, ssq.items.associate { it.id to 0 }, 20.0)
        assertEquals(0.0, none.total)
        assertFalse(none.flagged)
    }

    @Test
    fun `TCT and BERG ranges are consistent with their items`() {
        assertEquals(100.0, ScaleScorer.score(load("tct.json"), load("tct.json").items.associate { it.id to 2 }).totalScore)
        assertEquals(56.0, ScaleScorer.score(load("berg.json"), load("berg.json").items.associate { it.id to 4 }).totalScore)
    }

    @Test
    fun `SVV analysis reports direction and variability`() {
        val trials = SvvTest.startAngles(6).map { SvvTrial(it, 4.0 + (if (it > 0) 0.5 else -0.5)) }
        val r = SvvTest.analyse(trials)
        assertEquals(6, r.trials)
        assertEquals(4.0, r.meanErrorDeg, 1e-9)
        assertEquals(1, r.direction)
        assertTrue(r.sdDeg > 0.0)
        assertTrue(SvvTest.startAngles(4).zipWithNext().all { (a, b) -> a * b < 0 })
    }

    @Test
    fun `REQ-PAT-020 baseline capture produces measurement with drift and histogram`() {
        val acc = BaselineCaptureAccumulator(100.0)
        for (i in 0 until 6000) {
            val t = i / 100.0
            acc.add(t, 12.0 + 1.0 * sin(2 * PI * 1.0 * t) + 1.5 * t / 60.0, ValidityFlags.VALID)
        }
        val m = acc.result().getOrThrow()
        assertEquals(60.0, m.durationS, 0.1)
        assertTrue(m.meanDeg > 12.0 && m.meanDeg < 13.5, "mean=${m.meanDeg}")
        assertEquals(1.5, m.driftDegPerMin, 0.15) // a sinusoid biases a linear fit by -A/(omega*var(t)); kept small here
        assertEquals(m.histogram.sum(), 6000)
        assertTrue(m.histogram.indexOf(m.histogram.max()) >= 10) // mass in the +10..+15 bin region
        assertEquals(100.0, m.validSamplePct, 1e-9)
    }

    @Test
    fun `baseline capture with too little valid data is refused`() {
        val acc = BaselineCaptureAccumulator(100.0)
        for (i in 0 until 2000) acc.add(i / 100.0, 5.0, if (i < 1000) ValidityFlags.VALID else ValidityFlags.PITCH_OUT_OF_RANGE)
        assertTrue(acc.result().isFailure)
    }

    @Test
    fun `contraindication screen blocks on any positive and requires completeness`() {
        val all = Contraindication.entries.associateWith { false }
        assertTrue(ContraindicationChecklist.screen(all).passes)
        val one = all + (Contraindication.SEIZURE_DISORDER to true)
        val s = ContraindicationChecklist.screen(one)
        assertTrue(s.blocked)
        assertEquals(listOf(Contraindication.SEIZURE_DISORDER), s.positives)
        assertFalse(ContraindicationChecklist.screen(all - Contraindication.VESTIBULAR_CRISIS).complete)
    }
}
