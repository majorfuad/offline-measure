package com.fuad.offlinemeasure.ui

import android.opengl.GLSurfaceView
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.fuad.offlinemeasure.measurement.AccuracyMetrics
import com.fuad.offlinemeasure.measurement.RepeatabilityReport
import java.util.Locale
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fuad.offlinemeasure.ar.MeasurementOverlay
import com.fuad.offlinemeasure.ar.ScreenPoint
import com.fuad.offlinemeasure.ar.MeasureRenderer
import com.fuad.offlinemeasure.arhelpers.TapHelper
import com.fuad.offlinemeasure.rendering.SampleRender

private data class MagnifierSnapshot(
    val bitmap: androidx.compose.ui.graphics.ImageBitmap,
    val crop: Rect,
    val points: List<ScreenPoint>,
    val selectedIndex: Int,
    val target: Offset
)

@Composable
fun MeasureScreen(
    modifier: Modifier = Modifier,
    trackingStatusText: String = "Searching for surfaces...",
    placementQualityText: String = "Point quality not yet evaluated",
    distanceText: String = "Tap two points on a plane to measure distance",
    measuredMeters: Float? = null,
    overlay: MeasurementOverlay = MeasurementOverlay(emptyList(), 1, 1),
    unitIndex: Int = 0,
    onUnitChange: (Int) -> Unit = {},
    onPlaceAtCenter: (Float, Float) -> Unit = { _, _ -> },
    onReplacePoint: (Int, Float, Float) -> Unit = { _, _, _ -> },
    onNudgeAnchor: (Int, Float, Float) -> Unit = { _, _, _ -> },
    renderer: MeasureRenderer? = null,
    tapHelper: TapHelper? = null,
    onClear: () -> Unit = {},
    onSurfaceCreated: (GLSurfaceView) -> Unit = {}
) {
    var precisionMode by remember { mutableStateOf(false) }
    var showAccuracyDialog by remember { mutableStateOf(false) }
    var referenceCm by remember { mutableStateOf("30") }
    var capturedReadings by remember { mutableStateOf<List<Double>>(emptyList()) }
    var recordedReference by remember { mutableStateOf<Double?>(null) }
    val referenceMeters = referenceCm.trim().toDoubleOrNull()?.div(100.0)
    val accuracy = if (referenceMeters != null && measuredMeters != null) {
        AccuracyMetrics.calculate(referenceMeters, measuredMeters.toDouble())
    } else null
    val referenceMatches = referenceMeters != null && recordedReference != null &&
        kotlin.math.abs(referenceMeters - recordedReference!!) < 0.0000001
    val report = if (referenceMatches) {
        RepeatabilityReport.calculate(recordedReference!!, capturedReadings)
    } else null
    var editingIndex by remember { mutableIntStateOf(-1) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var magnifierSnapshot by remember { mutableStateOf<MagnifierSnapshot?>(null) }
    var capturePending by remember { mutableStateOf(false) }
    // Capture the marker coordinates together with their camera crop.
    // Rendering them against the latest overlay causes visible jitter at 5 FPS.
    val currentOverlay by rememberUpdatedState(overlay)
    val currentEditingIndex by rememberUpdatedState(editingIndex)
    val latestOffsetX by rememberUpdatedState(offsetX)
    val latestOffsetY by rememberUpdatedState(offsetY)
    var canvasWidth by remember { mutableIntStateOf(1) }
    var canvasHeight by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val nudge = with(density) { 3.dp.toPx() }
    fun adjust(dx: Float, dy: Float) {
        if (editingIndex in overlay.points.indices) {
            onNudgeAnchor(editingIndex, dx, dy)
        } else {
            offsetX = (offsetX + dx).coerceIn(-canvasWidth * 0.46f, canvasWidth * 0.46f)
            offsetY = (offsetY + dy).coerceIn(-canvasHeight * 0.46f, canvasHeight * 0.46f)
        }
    }
    fun confirmPoint() {
        if (editingIndex >= 0) {
            // Nudges have already changed this anchor; do not move it to screen center.
            editingIndex = -1
            offsetX = 0f
            offsetY = 0f
            return
        }
        val x = (0.5f + offsetX / canvasWidth.coerceAtLeast(1)).coerceIn(0.01f, 0.99f)
        val y = (0.5f + offsetY / canvasHeight.coerceAtLeast(1)).coerceIn(0.01f, 0.99f)
        onPlaceAtCenter(x, y)
    }
    fun editEndpoint(index: Int) {
        if (index !in overlay.points.indices) return
        precisionMode = true
        editingIndex = index
        offsetX = 0f
        offsetY = 0f
    }
    // PixelCopy samples the SurfaceView itself, not our Compose overlay.
    // This creates a genuine zoomed camera crop without changing the AR hit coordinates.
    LaunchedEffect(precisionMode, glView) {
        if (!precisionMode) {
            magnifierSnapshot = null
            return@LaunchedEffect
        }
        val view = glView ?: return@LaunchedEffect
        val handler = Handler(Looper.getMainLooper())
        while (true) {
            if (!capturePending && view.width >= 120 && view.height >= 120 &&
                view.holder.surface.isValid) {
                val radius = 60
                val selected = currentOverlay.points.getOrNull(currentEditingIndex)
                val x = if (selected == null) {
                    (view.width / 2f + latestOffsetX).roundToInt()
                } else {
                    (selected.x * view.width / currentOverlay.width.coerceAtLeast(1)).roundToInt()
                }
                val y = if (selected == null) {
                    (view.height / 2f + latestOffsetY).roundToInt()
                } else {
                    (selected.y * view.height / currentOverlay.height.coerceAtLeast(1)).roundToInt()
                }
                val left = (x - radius).coerceIn(0, view.width - radius * 2)
                val top = (y - radius).coerceIn(0, view.height - radius * 2)
                val source = Rect(left, top, left + radius * 2, top + radius * 2)
                val capturedTarget = Offset(
                    (x - left).coerceIn(0, radius * 2) / (radius * 2f),
                    (y - top).coerceIn(0, radius * 2) / (radius * 2f)
                )
                val capturedPoints = currentOverlay.points.map { point ->
                    ScreenPoint(
                        point.x * view.width / currentOverlay.width.coerceAtLeast(1),
                        point.y * view.height / currentOverlay.height.coerceAtLeast(1)
                    )
                }
                val capturedIndex = currentEditingIndex
                val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
                capturePending = true
                try {
                    PixelCopy.request(view, source, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) {
                            magnifierSnapshot = MagnifierSnapshot(
                                bitmap.asImageBitmap(), Rect(source),
                                capturedPoints, capturedIndex, capturedTarget
                            )
                        } else {
                            bitmap.recycle()
                        }
                        capturePending = false
                    }, handler)
                } catch (_: IllegalArgumentException) {
                    bitmap.recycle()
                    capturePending = false
                }
            }
            delay(180L)
        }
    }

    if (showAccuracyDialog) {
        AlertDialog(
            onDismissRequest = { showAccuracyDialog = false },
            title = { Text("Check measurement accuracy") },
            text = {
                Column {
                    Text("Enter the actual length measured with a ruler or tape.")
                    OutlinedTextField(
                        value = referenceCm,
                        onValueChange = { referenceCm = it },
                        label = { Text("Reference length (cm)") },
                        singleLine = true
                    )
                    if (accuracy == null) {
                        Text("Enter a positive reference length.")
                    } else {
                        Text(String.format(Locale.US, "Measured: %.2f cm", accuracy.measuredMeters * 100))
                        Text(String.format(Locale.US, "Absolute error: %.2f cm", accuracy.absoluteErrorCm))
                        Text(String.format(Locale.US, "Percentage error: %.2f%%", accuracy.percentError))
                        Text(
                            if (accuracy.signedErrorCm >= 0) "Measurement is higher than reference."
                            else "Measurement is lower than reference."
                        )
                        Text("One comparison does not establish device accuracy. Repeat from different angles.")
                        Button(onClick = {
                            val known = referenceMeters
                            val measured = measuredMeters
                            if (known != null && known > 0.0 && measured != null) {
                                if (!referenceMatches) capturedReadings = emptyList()
                                recordedReference = known
                                capturedReadings = capturedReadings + measured.toDouble()
                            }
                        }) { Text("Record this reading") }
                    }
                    if (capturedReadings.isNotEmpty() && !referenceMatches) {
                        Text("Reference length changed. Record a reading to start a new series.")
                    }
                    if (report != null) {
                        Text("Recorded: ${report.count} readings")
                        Text(String.format(Locale.US, "Mean: %.2f cm", report.meanMeters * 100.0))
                        Text(String.format(Locale.US, "Mean absolute error: %.2f cm (%.2f%%)",
                            report.meanAbsoluteErrorCm, report.meanAbsolutePercentError))
                        Text(String.format(Locale.US, "Bias: %+.2f cm", report.biasCm))
                        Text(String.format(Locale.US, "Sample variation (SD): %.2f cm", report.sampleStdDevCm))
                        Text("Variation indicates consistency, not absolute accuracy.")
                        Button(onClick = {
                            capturedReadings = emptyList()
                            recordedReference = null
                        }) { Text("Reset readings") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAccuracyDialog = false }) { Text("Close") }
            }
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (renderer != null && tapHelper != null) {
            AndroidView(
                factory = { context ->
                    GLSurfaceView(context).apply {
                        SampleRender(this, renderer, context.assets)
                        setOnTouchListener(tapHelper)
                        glView = this
                        onSurfaceCreated(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Pointer events continue through the drawn overlay to GLSurfaceView.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sx = size.width / overlay.width.coerceAtLeast(1)
            val sy = size.height / overlay.height.coerceAtLeast(1)
            val points = overlay.points.map { Offset(it.x * sx, it.y * sy) }
            if (points.size == 2) {
                drawLine(
                    color = Color(0xFFFFC857),
                    start = points[0],
                    end = points[1],
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            points.forEach { point ->
                drawCircle(color = Color.Black.copy(alpha = 0.6f), radius = 11.dp.toPx(), center = point)
                drawCircle(color = Color(0xFFFFC857), radius = 7.dp.toPx(), center = point)
                drawCircle(color = Color.White, radius = 3.dp.toPx(), center = point)
            }
        }

        if (precisionMode) {
            Canvas(modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasWidth = it.width; canvasHeight = it.height }
                .pointerInput(precisionMode) {
                    detectDragGestures { change, dragAmount ->
                        if (editingIndex < 0) {
                            change.consume()
                            adjust(dragAmount.x, dragAmount.y)
                        }
                    }
                }
            ) {
                val selected = overlay.points.getOrNull(editingIndex)
                val c = if (selected != null) Offset(
                    selected.x * size.width / overlay.width.coerceAtLeast(1),
                    selected.y * size.height / overlay.height.coerceAtLeast(1)
                ) else Offset(center.x + offsetX, center.y + offsetY)
                val yellow = Color(0xFFFFC857)
                val inner = 7.dp.toPx()
                val outer = 21.dp.toPx()
                drawCircle(color = Color.Black.copy(alpha = 0.7f),
                    radius = inner + 2.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                drawCircle(color = yellow, radius = inner, center = c, style = Stroke(2.dp.toPx()))
                drawLine(yellow, Offset(c.x - outer, c.y), Offset(c.x - inner - 4.dp.toPx(), c.y),
                    strokeWidth = 2.dp.toPx())
                drawLine(yellow, Offset(c.x + inner + 4.dp.toPx(), c.y), Offset(c.x + outer, c.y),
                    strokeWidth = 2.dp.toPx())
                drawLine(yellow, Offset(c.x, c.y - outer), Offset(c.x, c.y - inner - 4.dp.toPx()),
                    strokeWidth = 2.dp.toPx())
                drawLine(yellow, Offset(c.x, c.y + inner + 4.dp.toPx()), Offset(c.x, c.y + outer),
                    strokeWidth = 2.dp.toPx())
            }
        }

        if (precisionMode) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).size(154.dp),
                color = Color(0xE61A1A20),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    val snapshot = magnifierSnapshot
                    if (snapshot != null) {
                        Image(
                            bitmap = snapshot.bitmap,
                            contentDescription = "4 times magnified live camera region",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            val crop = snapshot.crop
                            run {
                                val sx = size.width / crop.width().coerceAtLeast(1)
                                val sy = size.height / crop.height().coerceAtLeast(1)
                                val markers = snapshot.points.map { point ->
                                    Offset(
                                        (point.x - crop.left) * sx,
                                        (point.y - crop.top) * sy
                                    )
                                }
                                if (markers.size == 2) {
                                    drawLine(
                                        Color(0xFFFFC857), markers[0], markers[1],
                                        2.dp.toPx(), cap = StrokeCap.Round
                                    )
                                }
                                markers.forEachIndexed { index, point ->
                                    if (point.x in -12f..(size.width + 12f) &&
                                        point.y in -12f..(size.height + 12f)) {
                                        drawCircle(
                                            if (index == snapshot.selectedIndex) Color(0xFFFFC857)
                                            else Color.White,
                                            if (index == snapshot.selectedIndex) 8.dp.toPx() else 5.dp.toPx(),
                                            point,
                                            style = Stroke(2.dp.toPx())
                                        )
                                        drawCircle(
                                            if (index == snapshot.selectedIndex) Color(0xFFFFC857)
                                            else Color.White,
                                            2.dp.toPx(), point
                                        )
                                    }
                                }
                            }
                            val selected = snapshot.points.getOrNull(snapshot.selectedIndex)
                            val c = if (selected != null) {
                                Offset(
                                    (selected.x - crop.left) * size.width / crop.width().coerceAtLeast(1),
                                    (selected.y - crop.top) * size.height / crop.height().coerceAtLeast(1)
                                )
                            } else Offset(
                                size.width * snapshot.target.x,
                                size.height * snapshot.target.y
                            )
                            drawLine(Color.Black, Offset(c.x - 17.dp.toPx(), c.y),
                                Offset(c.x + 17.dp.toPx(), c.y), 4.dp.toPx())
                            drawLine(Color.Black, Offset(c.x, c.y - 17.dp.toPx()),
                                Offset(c.x, c.y + 17.dp.toPx()), 4.dp.toPx())
                            drawLine(Color(0xFFFFC857), Offset(c.x - 17.dp.toPx(), c.y),
                                Offset(c.x + 17.dp.toPx(), c.y), 2.dp.toPx())
                            drawLine(Color(0xFFFFC857), Offset(c.x, c.y - 17.dp.toPx()),
                                Offset(c.x, c.y + 17.dp.toPx()), 2.dp.toPx())
                        }
                    } else {
                        Text(
                            "Magnifier starting…",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center).padding(12.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Text(
                        "4× LIVE",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        if (overlay.points.size == 2 && distanceText.startsWith("Distance:")) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val sx = constraints.maxWidth.toFloat() / overlay.width.coerceAtLeast(1)
                val sy = constraints.maxHeight.toFloat() / overlay.height.coerceAtLeast(1)
                val centerX = (overlay.points[0].x + overlay.points[1].x) * sx / 2
                val centerY = (overlay.points[0].y + overlay.points[1].y) * sy / 2
                val xDp = with(density) { centerX.toDp() }
                val yDp = with(density) { centerY.toDp() }
                Surface(
                    modifier = Modifier.offset(
                        x = (xDp - 70.dp).coerceIn(0.dp, (maxWidth - 140.dp).coerceAtLeast(0.dp)),
                        y = (yDp - 54.dp).coerceIn(0.dp, (maxHeight - 120.dp).coerceAtLeast(0.dp))
                    ),
                    color = Color(0xE6222530),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = distanceText.removePrefix("Distance: "),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = trackingStatusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = distanceText,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (overlay.points.isNotEmpty()) {
                            Text(
                                text = placementQualityText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    if (overlay.points.isNotEmpty()) {
                        Button(onClick = onClear) { Text("Clear") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (measuredMeters != null) {
                    Button(onClick = { showAccuracyDialog = true }) {
                        Text("Check accuracy")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        precisionMode = !precisionMode
                        editingIndex = -1
                        offsetX = 0f
                        offsetY = 0f
                    }) {
                        Text(if (precisionMode) "Tap mode" else "Precision")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (precisionMode) {
                        Button(onClick = { confirmPoint() }) {
                            Text(if (editingIndex >= 0) "Done editing" else "Confirm point")
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { onUnitChange((unitIndex + 1) % 4) }) {
                        Text(listOf("m", "cm", "in", "ft")[unitIndex.coerceIn(0, 3)])
                    }
                }
                if (overlay.points.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Refine:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(onClick = { editEndpoint(0) }) { Text("Point 1") }
                        if (overlay.points.size > 1) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = { editEndpoint(1) }) { Text("Point 2") }
                        }
                    }
                }
                if (precisionMode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (editingIndex >= 0)
                            "Arrows move Point ${editingIndex + 1} at its current estimated depth."
                        else "Drag crosshair, then confirm a new point.",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { adjust(-nudge, 0f) }) { Text("←") }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = { adjust(nudge, 0f) }) { Text("→") }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = { adjust(0f, -nudge) }) { Text("↑") }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = { adjust(0f, nudge) }) { Text("↓") }
                    }
                }
            }
        }
    }
}
