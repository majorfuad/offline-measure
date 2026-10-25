package com.fuad.offlinemeasure.ui

import android.opengl.GLSurfaceView
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fuad.offlinemeasure.ar.MeasurementOverlay
import com.fuad.offlinemeasure.ar.MeasureRenderer
import com.fuad.offlinemeasure.arhelpers.TapHelper
import com.fuad.offlinemeasure.rendering.SampleRender

@Composable
fun MeasureScreen(
    modifier: Modifier = Modifier,
    trackingStatusText: String = "Searching for surfaces...",
    distanceText: String = "Tap two points on a plane to measure distance",
    overlay: MeasurementOverlay = MeasurementOverlay(emptyList(), 1, 1),
    unitIndex: Int = 0,
    onUnitChange: (Int) -> Unit = {},
    onPlaceAtCenter: (Float, Float) -> Unit = {},
    renderer: MeasureRenderer? = null,
    tapHelper: TapHelper? = null,
    onClear: () -> Unit = {},
    onSurfaceCreated: (GLSurfaceView) -> Unit = {}
) {
    var precisionMode by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var canvasWidth by remember { mutableIntStateOf(1) }
    var canvasHeight by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val nudge = with(density) { 3.dp.toPx() }
    fun adjust(dx: Float, dy: Float) {
        offsetX = (offsetX + dx).coerceIn(-canvasWidth * 0.46f, canvasWidth * 0.46f)
        offsetY = (offsetY + dy).coerceIn(-canvasHeight * 0.46f, canvasHeight * 0.46f)
    }
    fun confirmPoint() {
        val x = (0.5f + offsetX / canvasWidth.coerceAtLeast(1)).coerceIn(0.01f, 0.99f)
        val y = (0.5f + offsetY / canvasHeight.coerceAtLeast(1)).coerceIn(0.01f, 0.99f)
        onPlaceAtCenter(x, y)
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (renderer != null && tapHelper != null) {
            AndroidView(
                factory = { context ->
                    GLSurfaceView(context).apply {
                        SampleRender(this, renderer, context.assets)
                        setOnTouchListener(tapHelper)
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
                        change.consume()
                        adjust(dragAmount.x, dragAmount.y)
                    }
                }
            ) {
                val c = Offset(center.x + offsetX, center.y + offsetY)
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
                    }
                    if (overlay.points.isNotEmpty()) {
                        Button(onClick = onClear) { Text("Clear") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        precisionMode = !precisionMode
                        offsetX = 0f
                        offsetY = 0f
                    }) {
                        Text(if (precisionMode) "Tap mode" else "Precision")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (precisionMode) {
                        Button(onClick = { confirmPoint() }) { Text("Confirm point") }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { onUnitChange((unitIndex + 1) % 4) }) {
                        Text(listOf("m", "cm", "in", "ft")[unitIndex.coerceIn(0, 3)])
                    }
                }
                if (precisionMode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Drag the crosshair to the corner, nudge to refine, then confirm.",
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
