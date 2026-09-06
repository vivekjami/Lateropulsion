package com.lateropulsion.feature.report

import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.BodyPosition
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.DeviationMetrics
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.EpisodeStats
import com.lateropulsion.core.model.LesionSide
import com.lateropulsion.core.model.Patient
import com.lateropulsion.core.model.PatientId
import com.lateropulsion.core.model.Session
import com.lateropulsion.core.model.SessionId
import com.lateropulsion.core.model.SessionSummary
import com.lateropulsion.core.model.Sex
import com.lateropulsion.core.model.Side
import com.lateropulsion.core.model.VisualMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportPureTest {
    @Test
    fun `nice axis covers the range with round ticks`() {
        val a = ChartModel.niceAxis(-27.0, 31.0, 5)
        assertTrue(a.min <= -27.0 && a.max >= 31.0)
        assertEquals(listOf(-40.0, -20.0, 0.0, 20.0, 40.0), a.ticks)
        assertEquals(0.5, ChartModel.niceAxis(0.0, 0.0, 5).let { it.frac(0.5) }, 1e-9)
    }

    @Test
    fun `downsampling keeps peaks`() {
        val pts = (0 until 10_000).map { Pt(it / 50.0, if (it == 5_000) 40.0 else kotlin.math.sin(it / 30.0) * 5) }
        val ds = ChartModel.downsample(pts, 400)
        assertTrue(ds.size <= 402)
        assertEquals(40.0, ds.maxOf { it.y })
        assertTrue(ds.zipWithNext().all { (a, b) -> a.x <= b.x })
    }

    @Test
    fun `layout maps data to plot rectangle`() {
        val spec = ChartSpec("t", "x", "y", listOf(Series("s", listOf(Pt(0.0, 0.0), Pt(10.0, 20.0)), 0)))
        val l = ChartModel.layout(spec, 400.0, 300.0)
        assertEquals(l.plot.left, l.px(l.xAxis.min), 1e-9)
        assertEquals(l.plot.right, l.px(l.xAxis.max), 1e-9)
        assertEquals(l.plot.bottom, l.py(l.yAxis.min), 1e-9)
    }

    private val patient = Patient(PatientId("p"), "LP-2026-0007", 61, Sex.MALE, "stroke, secret diagnosis", LesionSide.RIGHT, Side.LEFT, Side.LEFT, null, false, true, 1L, createdAt = 1, updatedAt = 1)
    private val session = Session(SessionId("s"), patient.id, ClinicianId("c"), "std-sitting-v3", 3, 4, VisualMode.VERTICAL_REFERENCE, 0.0, 1.0, null, "dev", "hs", BodyPosition.SITTING_UNSUPPORTED, 1_756_000_000_000L, 0L, "Asia/Kolkata",
        endedAtUtc = 1_756_000_900_000L, endReason = EndReason.COMPLETED, notes = "patient said, \"tired\"", appVersion = "0.1.0")
    private val summary = SessionSummary(session.id, null, 10.0, DeviationMetrics(4.0, 5.0, 6.0, 12.0, 2.0, 55.0, 90.0, 20.0, 300.0, 5.0, 0.3, 4500, 4600, 90.0), EpisodeStats.NONE, -5.0, 50.0, false, 2.0, false, null, 0, AssistanceLevel.SUPERVISION, 0.0, VisualMode.VERTICAL_REFERENCE, EndReason.COMPLETED, "metrics-1.0.0", 1L)

    @Test
    fun `REQ-SEC-006 CSV export is de-identified and quotes correctly`() {
        val csv = CsvExporter.sessionsCsv(patient, listOf(session to summary))
        val lines = csv.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("study_code,session_number"))
        assertTrue(lines[1].startsWith("LP-2026-0007,4,1756000000000,std-sitting-v3,3,VERTICAL_REFERENCE,0.0,SITTING_UNSUPPORTED,COMPLETED,5.0000,"))
        assertFalse(csv.contains("secret"))
        assertEquals("\"a,\"\"b\"\"\"", CsvExporter.csv("a,\"b\""))
    }

    @Test
    fun `REQ-SEC-006 JSON bundle never carries identifiers and strips free text when de-identified`() {
        val data = SessionReportData(patient, "Dr X", session, emptyList(), summary, null, emptyList(), emptyList(), "Standard sitting", com.lateropulsion.core.model.DeviceQualification.JIG, "0.1.0", "Ward 3", 2L)
        val deid = JsonExporter.sessionBundle(data, deidentified = true)
        assertTrue(deid.contains("\"study_code\": \"LP-2026-0007\""))
        assertFalse(deid.contains("secret diagnosis"))
        assertFalse(deid.contains("tired"))
        assertFalse(deid.contains("Dr X"))
        val ident = JsonExporter.sessionBundle(data, deidentified = false)
        assertTrue(ident.contains("secret diagnosis") && ident.contains("tired"))
        assertFalse(ident.contains("Dr X"))
    }

    @Test
    fun `report text carries the head-as-proxy limitation`() {
        assertTrue(ReportText.PROXY_LIMITATION.contains("proxy"))
        assertTrue(ReportText.IMPROVEMENT_CAVEAT.contains("gain"))
    }
}
