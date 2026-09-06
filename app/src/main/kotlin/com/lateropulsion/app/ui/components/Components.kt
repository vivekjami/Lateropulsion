package com.lateropulsion.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lateropulsion.feature.report.CanvasChartPainter
import com.lateropulsion.feature.report.ChartLayout
import com.lateropulsion.feature.report.ChartModel
import com.lateropulsion.feature.report.ChartSpec
import com.lateropulsion.feature.report.Rect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LpScreen(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}, banner: (@Composable () -> Unit)? = null, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { actions() },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            banner?.invoke()
            content(Modifier.padding(horizontal = 16.dp))
        }
    }
}

/** Persistent patient identity banner shown on every patient screen (H-03 wrong-patient control). */
@Composable
fun PatientBanner(displayId: String, name: String, detail: String = "") {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(displayId, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(16.dp))
        Column { Text(name, style = MaterialTheme.typography.titleMedium); if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, secondary: Boolean = false) {
    val colors = when {
        danger -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        else -> ButtonDefaults.buttonColors()
    }
    if (secondary) {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp), enabled = enabled) { Text(text, style = MaterialTheme.typography.labelLarge) }
    } else {
        Button(onClick = onClick, modifier = modifier.heightIn(min = 56.dp), enabled = enabled, colors = colors) { Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
fun LpTextField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, number: Boolean = false, password: Boolean = false, singleLine: Boolean = true, error: String? = null) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.NumberPassword; number -> KeyboardType.Number; else -> KeyboardType.Text }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun <T> Selector(label: String, options: List<T>, selected: T?, display: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("$label: ${selected?.let(display) ?: "—"}", style = MaterialTheme.typography.bodyLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun StatusChip(text: String, ok: Boolean?, modifier: Modifier = Modifier) {
    val color = when (ok) { true -> MaterialTheme.colorScheme.secondaryContainer; false -> MaterialTheme.colorScheme.errorContainer; null -> MaterialTheme.colorScheme.surfaceVariant }
    Box(modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text((if (ok == true) "✓ " else if (ok == false) "✗ " else "• ") + text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun WarningText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
}

/** Angle dial: needle at the current roll, coloured band for tolerance; the therapist's at-a-glance view. */
@Composable
fun AngleDial(thetaDeg: Double, toleranceDeg: Double, inBand: Boolean, valid: Boolean, modifier: Modifier = Modifier, rangeDeg: Double = 45.0) {
    val bandColor = when { !valid -> Color.Gray; inBand -> Color(0xFF2E7D32) else -> Color(0xFFB3261E) }
    Canvas(modifier.size(220.dp)) {
        val c = Offset(size.width / 2, size.height * 0.62f)
        val r = size.minDimension * 0.45f
        // arc from -range to +range, 0 at top
        drawArc(Color.LightGray, 180f + (90f - rangeDeg.toFloat()), 2 * rangeDeg.toFloat(), false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(10f, cap = StrokeCap.Round))
        drawArc(bandColor.copy(alpha = 0.35f), 270f - toleranceDeg.toFloat(), 2 * toleranceDeg.toFloat(), false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(16f))
        val a = (-90.0 + thetaDeg.coerceIn(-rangeDeg, rangeDeg)) * PI / 180.0
        val tip = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
        drawLine(bandColor, c, tip, strokeWidth = 8f, cap = StrokeCap.Round)
        drawLine(Color.DarkGray, Offset(c.x, c.y - r - 14f), Offset(c.x, c.y - r + 14f), strokeWidth = 4f)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = size.minDimension * 0.16f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
            drawText(String.format(java.util.Locale.ROOT, "%+.1f°", thetaDeg), c.x, c.y + size.minDimension * 0.28f, paint)
        }
    }
}

/** Compose host for the shared chart code path (ARCHITECTURE §12.2). */
@Composable
fun ChartCanvas(spec: ChartSpec, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val painter = remember(density) { CanvasChartPainter(density) }
    Canvas(modifier.fillMaxWidth().height(260.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(4.dp)) {
        val layout: ChartLayout = ChartModel.layout(spec, size.width.toDouble(), size.height.toDouble(), 48.0 * density, 44.0 * density, 28.0 * density, 40.0 * density)
        drawContext.canvas.nativeCanvas.let { painter.draw(it, layout.copy(plot = Rect(48.0 * density, 28.0 * density, size.width - 44.0 * density, size.height - 40.0 * density))) }
    }
}

/**
 * Collapsed by default: the operator only opens it to change something (ADR-020). [summary] says what the
 * defaults currently are so nothing is hidden.
 */
@Composable
fun Expander(title: String, summary: String = "", initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (open) "▲" else "▼ change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(open) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
        }
    }
}

/** "Step 2 of 3 · Baseline" header so the operator always knows where they are in the flow. */
@Composable
fun StepHeader(step: Int, total: Int, title: String, hint: String = "") {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Step $step of $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One big number with a caption: the way a result should read from across the room. */
@Composable
fun BigNumber(value: String, caption: String, modifier: Modifier = Modifier, emphasis: Boolean = true) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.displayMedium.copy(fontSize = if (emphasis) 44.sp else 32.sp), fontWeight = FontWeight.Bold,
            color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A checklist item shown as done / to do, without being a control. */
@Composable
fun StepRow(done: Boolean, text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else "○", style = MaterialTheme.typography.titleLarge, color = if (done) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
