package com.lateropulsion.feature.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.lateropulsion.core.model.AssistanceLevel
import com.lateropulsion.core.model.EndReason
import com.lateropulsion.core.model.SessionEventType
import com.lateropulsion.core.model.VisualMode
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A4 page with a text cursor. Everything is plain Paint drawing so the PDF needs no fonts or network. */
internal class Page(val canvas: Canvas, private val onFooter: (Canvas, Int) -> Unit, val number: Int) {
    var y = MARGIN
    val body = Paint().apply { color = Color.BLACK; textSize = 9.5f; isAntiAlias = true }
    val small = Paint().apply { color = Color.DKGRAY; textSize = 8f; isAntiAlias = true }
    val h1 = Paint().apply { color = Color.BLACK; textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
    val h2 = Paint().apply { color = Color.BLACK; textSize = 11.5f; isFakeBoldText = true; isAntiAlias = true }
    val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.8f }
    val warn = Paint().apply { color = Color.rgb(160, 40, 40); textSize = 9.5f; isFakeBoldText = true; isAntiAlias = true }

    fun title(t: String) { canvas.drawText(t, MARGIN, y + 14, h1); y += 24 }
    fun heading(t: String) { y += 6; canvas.drawText(t, MARGIN, y + 10, h2); y += 16 }
    fun line(t: String, p: Paint = body) { canvas.drawText(t, MARGIN, y + 9, p); y += 13 }
    fun wrapped(t: String, p: Paint = body, width: Float = WIDTH - 2 * MARGIN) {
        for (l in wrap(t, p, width)) line(l, p)
    }
    fun rule() { canvas.drawLine(MARGIN, y + 3, WIDTH - MARGIN, y + 3, rule); y += 8 }
    fun space(h: Float) { y += h }

    fun table(headers: List<String>, rows: List<List<String>>, widths: List<Float>) {
        var x = MARGIN
        val hp = Paint(body).apply { isFakeBoldText = true }
        for ((i, h) in headers.withIndex()) { canvas.drawText(h, x + 2, y + 9, hp); x += widths[i] }
        y += 13
        canvas.drawLine(MARGIN, y, MARGIN + widths.sum(), y, rule)
        for (r in rows) {
            x = MARGIN
            for ((i, c) in r.withIndex()) { canvas.drawText(clip(c, body, widths[i] - 4), x + 2, y + 9, body); x += widths[i] }
            y += 12
        }
        y += 2
    }

    fun finish() = onFooter(canvas, number)

    private fun clip(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    companion object {
        const val WIDTH = 595f
        const val HEIGHT = 842f
        const val MARGIN = 40f

        fun wrap(text: String, p: Paint, width: Float): List<String> {
            val out = ArrayList<String>()
            for (para in text.split('\n')) {
                var cur = StringBuilder()
                for (w in para.split(' ')) {
                    val candidate = if (cur.isEmpty()) w else "$cur $w"
                    if (p.measureText(candidate) > width && cur.isNotEmpty()) { out += cur.toString(); cur = StringBuilder(w) } else { cur = StringBuilder(candidate) }
                }
                out += cur.toString()
            }
            return out
        }
    }
}

internal object Fmt {
    fun deg(v: Double?): String = if (v == null || v.isNaN()) "—" else String.format(Locale.ROOT, "%.1f°", v)
    fun pct(v: Double?): String = if (v == null || v.isNaN()) "—" else String.format(Locale.ROOT, "%.0f %%", v)
    fun sec(v: Double?): String = if (v == null || v.isNaN()) "—" else String.format(Locale.ROOT, "%.1f s", v)
    fun num(v: Double?, d: Int = 2): String = if (v == null || v.isNaN()) "—" else String.format(Locale.ROOT, "%.${d}f", v)
    fun dateTime(utcMs: Long, zone: String): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())).format(Instant.ofEpochMilli(utcMs))
    fun mode(m: VisualMode): String = if (m == VisualMode.VERTICAL_REFERENCE) "Mode A – vertical reference" else "Mode B – compensated view (experimental)"
    fun assistance(a: AssistanceLevel?): String = a?.let { "${it.level} – ${it.name.lowercase().replace('_', ' ')}" } ?: "—"
}

/** Device-trust statement that every session report must carry (REQ-SAF-020, ADR-018). */
internal fun Page.qualificationNote(q: com.lateropulsion.core.model.DeviceQualification) {
    when (q) {
        com.lateropulsion.core.model.DeviceQualification.NONE -> line(ReportText.UNQUALIFIED_DEVICE, warn)
        com.lateropulsion.core.model.DeviceQualification.FIELD -> wrapped(ReportText.FIELD_CALIBRATED_DEVICE, small)
        com.lateropulsion.core.model.DeviceQualification.JIG -> Unit
    }
}

/** Session report, 3 pages (ARCHITECTURE §12.1). Generation is synchronous and must stay under 2 s (REQ-RPT-001). */
public class SessionReportBuilder(private val painter: CanvasChartPainter = CanvasChartPainter()) {
    public fun build(data: SessionReportData, out: OutputStream) {
        val doc = PdfDocument()
        val footer: (Canvas, Int) -> Unit = { c, n ->
            val p = Paint().apply { color = Color.DKGRAY; textSize = 7f; isAntiAlias = true }
            var y = Page.HEIGHT - 34f
            for (l in Page.wrap(ReportText.PROXY_LIMITATION, p, Page.WIDTH - 2 * Page.MARGIN)) { c.drawText(l, Page.MARGIN, y, p); y += 9 }
            c.drawText("Lateropulsion ${data.appVersion} · metrics ${data.summary.generatedByVersion} · ${data.patient.displayId} · session ${data.session.sessionNumber} · page $n/3", Page.MARGIN, Page.HEIGHT - 10f, p)
        }
        fun page(n: Int, block: Page.() -> Unit) {
            val info = PdfDocument.PageInfo.Builder(Page.WIDTH.toInt(), Page.HEIGHT.toInt(), n).create()
            val pg = doc.startPage(info)
            Page(pg.canvas, footer, n).apply { block(); finish() }
            doc.finishPage(pg)
        }
        val s = data.session; val m = data.summary; val p = data.patient
        page(1) {
            title("Lateropulsion session report")
            line("${p.displayId}  ·  ${p.age} y, ${p.sex.name.lowercase()}  ·  lesion ${p.lesionSide.name.lowercase()}, pushes ${p.lateropulsionDirection.name.lowercase()}", small)
            line("Session ${s.sessionNumber}  ·  ${Fmt.dateTime(s.startedAtUtc, s.deviceTimezone)} (${s.deviceTimezone})  ·  ${data.siteName}", small)
            line("Clinician: ${data.clinicianName}  ·  Protocol: ${data.protocolName} (${s.protocolId} v${s.protocolVersion})  ·  Position: ${s.position.name.lowercase().replace('_', ' ')}", small)
            line("${Fmt.mode(s.visualMode)}  ·  picture tilt ${Fmt.deg(s.appliedTiltDeg)}  ·  gain k = ${Fmt.num(s.gainUsed, 2)}  ·  sensor zero ${Fmt.deg(s.thetaRefDeg)}  ·  device ${s.deviceProfileId}", small)
            qualificationNote(data.deviceQualification)
            if (s.endReason == EndReason.ABORTED) line("ABORTED: ${s.abortReason ?: ""}  —  ${ReportText.ABORTED_NOTE}", warn)
            if (s.crashRecovered) line(ReportText.CRASH_RECOVERED_NOTE, warn)
            rule()
            heading("Baseline vs this session")
            val b = data.baseline?.measured
            table(
                listOf("Metric", "Baseline", "This session", "Change"),
                listOf(
                    listOf("Mean absolute deviation", Fmt.deg(b?.madDeg), Fmt.deg(m.metrics.madDeg), Fmt.deg(m.deltaDeg)),
                    listOf("RMS deviation", Fmt.deg(b?.rmsDeg), Fmt.deg(m.metrics.rmsDeg), Fmt.deg(b?.let { m.metrics.rmsDeg - it.rmsDeg })),
                    listOf("Time in band ±5°", Fmt.pct(b?.tib5Pct), Fmt.pct(m.metrics.tib5Pct), if (b != null) Fmt.num(m.metrics.tib5Pct - b.tib5Pct, 0) + " pp" else "—"),
                    listOf("Time in band ±10°", Fmt.pct(b?.tib10Pct), Fmt.pct(m.metrics.tib10Pct), if (b != null) Fmt.num(m.metrics.tib10Pct - b.tib10Pct, 0) + " pp" else "—"),
                    listOf("Episodes (>10°, ≥1 s)", "—", "${m.episodes.count} (${m.episodes.partialCount} partial)", "—"),
                    listOf("Mean recovery time", "—", Fmt.sec(m.episodes.recoveryMeanS), "—"),
                    listOf("Valid samples", Fmt.pct(b?.validSamplePct), Fmt.pct(m.metrics.validSamplePct), "—"),
                ),
                listOf(170f, 110f, 120f, 100f),
            )
            heading("Improvement")
            when {
                m.comparisonRefusedReason != null -> wrapped("Comparison with baseline refused: ${m.comparisonRefusedReason}", warn)
                m.improvementPct != null -> {
                    val within = if (m.withinMdc == true) " — within measurement noise (MDC ${Fmt.deg(m.mdcDeg)})" else ""
                    line("Improvement vs baseline: ${Fmt.num(m.improvementPct, 1)} % (Δ MAD ${Fmt.deg(m.deltaDeg)})$within${if (m.lowConfidence) "  · LOW CONFIDENCE (< 60 s valid data)" else ""}",
                        if (m.withinMdc == true || m.lowConfidence) warn else body)
                }
                else -> line("No baseline measurement available for comparison.", small)
            }
            wrapped(ReportText.IMPROVEMENT_CAVEAT, small)
            wrapped("Internal progress metric — not a validated clinical outcome. ${ReportText.MDC_NOTE}", small)
            space(6f)
            val plotRect = Rect(Page.MARGIN.toDouble(), y + 20.0, (Page.WIDTH - Page.MARGIN).toDouble(), y + 230.0)
            drawTrace(canvas, plotRect, data)
            y = plotRect.bottom.toFloat() + 40
        }
        page(2) {
            title("Per-block results")
            table(
                listOf("Block", "Exercise", "Dur", "k", "MAD", "RMS", "Max", "TIB5", "TIB10", "Epis.", "Recov.", "Valid", "End"),
                data.blocks.map { b ->
                    listOf(b.blockId, b.exercise.name.lowercase().replace('_', ' '), Fmt.sec(b.durationS), Fmt.num(b.gain, 1), Fmt.deg(b.metrics.madDeg), Fmt.deg(b.metrics.rmsDeg),
                        Fmt.deg(b.metrics.maxDeg), Fmt.pct(b.metrics.tib5Pct), Fmt.pct(b.metrics.tib10Pct), "${b.episodes.count}", Fmt.sec(b.episodes.recoveryMeanS), Fmt.pct(b.metrics.validSamplePct), b.endReason.name.lowercase())
                },
                listOf(58f, 82f, 36f, 26f, 40f, 40f, 40f, 36f, 36f, 30f, 38f, 36f, 52f),
            )
            heading("Checkpoints")
            val cps = data.blocks.flatMap { b -> b.checkpoints.map { c -> listOf(b.blockId, Fmt.sec(c.atS), Fmt.deg(c.thetaDeg), Fmt.deg(c.madSoFarDeg), if (c.inBand) "in band" else "out of band") } }
            if (cps.isEmpty()) line("No checkpoints recorded.", small) else table(listOf("Block", "At", "θ", "MAD so far", "Status"), cps.take(24), listOf(90f, 60f, 60f, 80f, 90f))
            heading("Angle distribution")
            val hist = histogram(data)
            val hr = Rect(Page.MARGIN.toDouble(), y + 14.0, Page.MARGIN + 240.0, y + 110.0)
            painter.drawHistogram(canvas, hr, hist.first, hist.second, "Filtered θ, valid samples (5° bins)")
            val cues = data.blocks.flatMap { it.cues }.toSet().joinToString { it.name.lowercase().replace('_', ' ') }
            var yy = y + 20f
            for (l in Page.wrap("Cue set: $cues", small, 240f)) { canvas.drawText(l, Page.MARGIN + 260f, yy, small); yy += 11 }
            canvas.drawText("Gain trace: k = ${data.blocks.map { Fmt.num(it.gain, 1) }.joinToString(" → ")}", Page.MARGIN + 260f, yy, small); yy += 11
            canvas.drawText("Valid samples: ${Fmt.pct(m.metrics.validSamplePct)}  ·  valid time ${Fmt.sec(m.metrics.validDurationS)}", Page.MARGIN + 260f, yy, small); yy += 11
            canvas.drawText("Filter: ${data.blocks.firstOrNull()?.filterParams?.let { "${it.type} n=${it.order} fc=${it.cutoffHz} Hz zero-phase=${it.zeroPhase}" } ?: "—"}", Page.MARGIN + 260f, yy, small)
            y = hr.bottom.toFloat() + 24
            heading("Events")
            val interesting = data.events.filter { it.type in EVENT_TYPES_FOR_REPORT }
            if (interesting.isEmpty()) line("No aborts, tracking losses, mount shifts or therapist marks.", small)
            else table(listOf("Time", "Event", "Detail"), interesting.take(28).map { listOf(Fmt.sec(it.tNanos / 1e9), it.type.name, it.payloadJson.take(60)) }, listOf(60f, 140f, 300f))
        }
        page(3) {
            title("Therapist observations")
            wrapped(s.notes.ifBlank { "(no notes recorded)" })
            space(8f)
            heading("Assistance and tolerance")
            line("Assistance level before: ${Fmt.assistance(s.assistanceLevelBefore)}    after: ${Fmt.assistance(s.assistanceLevelAfter)}")
            line("Balance-loss events: ${m.balanceLossEvents}    Therapist override: ${s.overrideReason ?: "none"}")
            line("SSQ pre: ${s.ssqPre?.let { "total ${Fmt.num(it.total, 1)} (N ${Fmt.num(it.nausea, 0)} O ${Fmt.num(it.oculomotor, 0)} D ${Fmt.num(it.disorientation, 0)})${if (it.flagged) " FLAGGED" else ""}" } ?: "—"}")
            line("SSQ post: ${s.ssqPost?.let { "total ${Fmt.num(it.total, 1)} (N ${Fmt.num(it.nausea, 0)} O ${Fmt.num(it.oculomotor, 0)} D ${Fmt.num(it.disorientation, 0)})${if (it.flagged) " FLAGGED" else ""}" } ?: "—"}")
            line("Gyro bias: ${s.gyroBias?.let { String.format(Locale.ROOT, "(%.4f, %.4f, %.4f) rad/s", it.x, it.y, it.z) } ?: "—"}    Drift: ${s.driftDegPerMin?.let { Fmt.num(it, 2) + " °/min" } ?: "—"}")
            heading("Media")
            if (data.screenshotPaths.isEmpty()) line(if (p.consentMedia) "No screenshots captured." else "Media capture off (no consent recorded).", small)
            else data.screenshotPaths.forEach { line("Screenshot: $it", small) }
            space(20f)
            heading("Signature")
            line("Clinician: ${data.clinicianName}    Signature: ______________________________    Date: ____________")
            space(12f)
            rule()
            wrapped(ReportText.NOT_A_DIAGNOSIS, small)
            line("Generated ${Fmt.dateTime(data.generatedAtUtc, s.deviceTimezone)} · app ${data.appVersion} · metrics engine ${m.generatedByVersion} · device profile ${s.deviceProfileId}", small)
        }
        doc.writeTo(out)
        doc.close()
    }

    private fun drawTrace(canvas: Canvas, rect: Rect, data: SessionReportData) {
        val pts = ChartModel.downsample(data.trace.filter { it.valid }.map { Pt(it.tS, it.thetaDeg) }, 1500)
        val episodes = data.blocks.flatMap { b -> b.episodeList.map { e -> Shade(b.startedMonoNs.let { (it - data.session.startedMonoNs) / 1e9 } + e.startS,
            b.startedMonoNs.let { (it - data.session.startedMonoNs) / 1e9 } + (e.endS ?: (e.startS + 1.0)), 3) } }
        val cps = data.blocks.flatMap { b -> b.checkpoints.map { c -> ((b.startedMonoNs - data.session.startedMonoNs) / 1e9 + c.atS) to "cp" } }
        val tol = data.blocks.firstOrNull()?.toleranceDeg ?: 5.0
        val spec = ChartSpec(
            title = "Deviation from midline over the session (filtered, valid samples)",
            xLabel = "time (s)", yLabel = "θ (°) + = right",
            series = listOf(Series("θ", pts, 0)),
            shades = episodes,
            bands = listOf(HBand(-tol, tol, 2, "±${ChartModel.formatTick(tol)}° tolerance")),
            refLines = listOf(RefLine(0.0, "midline", 6, dashed = false)),
            markersX = cps.take(12),
            yMinHint = -30.0, yMaxHint = 30.0,
        )
        painter.draw(canvas, ChartModel.layout(spec, rect.right, rect.bottom, marginLeft = rect.left + 30, marginTop = rect.top, marginRight = 30.0, marginBottom = 36.0).let {
            // constrain to the requested rectangle
            it.copy(plot = Rect(rect.left + 30, rect.top, rect.right - 30, rect.bottom - 36))
        })
    }

    private fun histogram(data: SessionReportData): Pair<List<Int>, List<Double>> {
        val edges = (-8..8).map { it * 5.0 }
        val counts = IntArray(edges.size - 1)
        for (t in data.trace) if (t.valid && t.thetaDeg >= edges.first() && t.thetaDeg <= edges.last()) {
            val bin = edges.indexOfLast { t.thetaDeg >= it }.coerceIn(0, counts.size - 1); counts[bin]++
        }
        return counts.toList() to edges
    }

    private companion object {
        val EVENT_TYPES_FOR_REPORT = setOf(
            SessionEventType.ABORT, SessionEventType.STOP_RULE, SessionEventType.PERF_DEGRADED, SessionEventType.TRACKING_LOST, SessionEventType.MOUNT_SHIFT,
            SessionEventType.THERAPIST_MARK, SessionEventType.BALANCE_LOSS, SessionEventType.OVERRIDE, SessionEventType.CAMERA_STALL, SessionEventType.THERMAL,
            SessionEventType.FUSION_DISAGREEMENT, SessionEventType.CRASH_RECOVERED,
        )
    }
}

/** Progress report (ARCHITECTURE §12.2): trend of MAD/TIB5 with baseline, gain k on a secondary axis, assistance stepped, scales overlaid, MDC band. */
public class ProgressReportBuilder(private val painter: CanvasChartPainter = CanvasChartPainter()) {
    public fun build(data: ProgressReportData, out: OutputStream) {
        val doc = PdfDocument()
        val footer: (Canvas, Int) -> Unit = { c, n ->
            val p = Paint().apply { color = Color.DKGRAY; textSize = 7f; isAntiAlias = true }
            var y = Page.HEIGHT - 34f
            for (l in Page.wrap(ReportText.PROXY_LIMITATION, p, Page.WIDTH - 2 * Page.MARGIN)) { c.drawText(l, Page.MARGIN, y, p); y += 9 }
            c.drawText("Lateropulsion ${data.appVersion} · ${data.patient.displayId} · progress report · page $n", Page.MARGIN, Page.HEIGHT - 10f, p)
        }
        val info = PdfDocument.PageInfo.Builder(Page.WIDTH.toInt(), Page.HEIGHT.toInt(), 1).create()
        val pg = doc.startPage(info)
        Page(pg.canvas, footer, 1).apply {
            val p = data.patient
            title("Lateropulsion progress report")
            line("${p.displayId}  ·  ${p.age} y, ${p.sex.name.lowercase()}  ·  lesion ${p.lesionSide.name.lowercase()}, pushes ${p.lateropulsionDirection.name.lowercase()}  ·  ${data.sessions.size} sessions  ·  ${data.siteName}", small)
            data.baseline?.let { b ->
                line(
                    "Baseline ${Fmt.dateTime(b.recordedAt, "UTC")}: severity ${b.severity.name.lowercase().replace('_', ' ')}, " +
                        "MAD ${Fmt.deg(b.measured?.madDeg)}, TIB5 ${Fmt.pct(b.measured?.tib5Pct)}, assistance ${Fmt.assistance(b.assistanceLevel)}",
                small) }
            rule()
            val spec = trendSpec(data)
            val rect = Rect(Page.MARGIN.toDouble(), y + 24.0, (Page.WIDTH - Page.MARGIN).toDouble(), y + 250.0)
            painter.draw(canvas, ChartModel.layout(spec, 0.0, 0.0).copy(plot = Rect(rect.left + 34, rect.top, rect.right - 34, rect.bottom - 36)))
            y = rect.bottom.toFloat() + 46
            wrapped("${ReportText.IMPROVEMENT_CAVEAT} ${ReportText.MDC_NOTE} MDC used: ${Fmt.deg(data.mdcDeg)}.", small)
            heading("Sessions")
            table(
                listOf("#", "Date", "Protocol", "Mode", "k", "MAD", "TIB5", "Epis.", "Impr.", "Assist", "End"),
                data.sessions.takeLast(24).map { (s, m) ->
                    listOf("${s.sessionNumber}", Fmt.dateTime(s.startedAtUtc, s.deviceTimezone).take(10), s.protocolId, if (s.visualMode == VisualMode.VERTICAL_REFERENCE) "A" else "B",
                        Fmt.num(s.gainUsed, 1), Fmt.deg(m.metrics.madDeg), Fmt.pct(m.metrics.tib5Pct), "${m.episodes.count}",
                        m.improvementPct?.let { Fmt.num(it, 0) + "%" + (if (m.withinMdc == true) "*" else "") } ?: (m.comparisonRefusedReason?.let { "n/a" } ?: "—"),
                        m.assistanceLevel?.level?.toString() ?: "—", (s.endReason?.name ?: "").lowercase().take(7))
                },
                listOf(20f, 62f, 120f, 34f, 26f, 44f, 40f, 34f, 46f, 40f, 48f),
            )
            line("* within MDC (measurement noise). Aborted sessions are included (ADR-009).", small)
            heading("Clinical scales")
            if (data.assessments.isEmpty()) line("No clinical scale entries.", small)
            else table(listOf("Date", "Scale", "Version", "Score"), data.assessments.sortedBy { it.recordedAt }.takeLast(12).map { listOf(Fmt.dateTime(it.recordedAt, "UTC").take(10),
                it.scaleCode, it.scaleVersion, Fmt.num(it.totalScore, 1)) }, listOf(80f, 80f, 80f, 60f))
            wrapped(ReportText.NOT_A_DIAGNOSIS, small)
            finish()
        }
        doc.finishPage(pg)
        doc.writeTo(out)
        doc.close()
    }

    public fun trendSpec(data: ProgressReportData): ChartSpec {
        val xs = data.sessions.map { it.first.sessionNumber.toDouble() }
        val mad = data.sessions.map { (s, m) -> Pt(s.sessionNumber.toDouble(), m.metrics.madDeg) }.filter { !it.y.isNaN() }
        val tib = data.sessions.map { (s, m) -> Pt(s.sessionNumber.toDouble(), m.metrics.tib5Pct / 10.0) }.filter { !it.y.isNaN() }
        val gain = data.sessions.map { (s, _) -> Pt(s.sessionNumber.toDouble(), s.gainUsed) }
        val assist = data.sessions.mapNotNull { (s, m) -> m.assistanceLevel?.let { Pt(s.sessionNumber.toDouble(), it.level.toDouble()) } }
        val scales = data.assessments.filter { it.scaleCode == "SCP" }.mapIndexedNotNull { _, a ->
            data.sessions.minByOrNull { kotlin.math.abs(it.first.startedAtUtc - a.recordedAt) }?.let { Pt(it.first.sessionNumber.toDouble(), a.totalScore) }
        }
        val bMad = data.baseline?.measured?.madDeg
        return ChartSpec(
            title = "Session-by-session trend",
            xLabel = "session", yLabel = "MAD (°) · TIB5/10 (%) · SCP",
            series = listOfNotNull(
                Series("MAD (°)", mad, 0, markers = true),
                Series("TIB5 /10 (%)", tib, 2, markers = true),
                if (scales.isNotEmpty()) Series("SCP (0–6)", scales, 4, markers = true) else null,
                Series("gain k", gain, 1, stepped = true, secondaryAxis = true),
                if (assist.isNotEmpty()) Series("assistance 0–3", assist, 3, stepped = true, secondaryAxis = true) else null,
            ),
            refLines = listOfNotNull(bMad?.let { RefLine(it, "baseline MAD", 0) }),
            bands = listOfNotNull(bMad?.let { HBand(it - data.mdcDeg, it + data.mdcDeg, 6, "MDC band") }),
            yLabelSecondary = "k / assist",
            yMinHint = 0.0,
            xMinHint = xs.minOrNull()?.minus(0.5), xMaxHint = xs.maxOrNull()?.plus(0.5),
        )
    }
}
