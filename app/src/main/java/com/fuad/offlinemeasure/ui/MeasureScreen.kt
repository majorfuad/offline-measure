package com.fuad.offlinemeasure.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fuad.offlinemeasure.ar.MeasureRenderer
import com.fuad.offlinemeasure.arhelpers.TapHelper
import com.fuad.offlinemeasure.rendering.SampleRender

@Composable
fun MeasureScreen(
    modifier: Modifier = Modifier,
    trackingStatusText: String = "Searching for surfaces...",
    distanceText: String = "Tap two points on a plane to measure distance",
    renderer: MeasureRenderer? = null,
    tapHelper: TapHelper? = null,
    onSurfaceCreated: (GLSurfaceView) -> Unit = {}
) {
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

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
        }
    }
}
