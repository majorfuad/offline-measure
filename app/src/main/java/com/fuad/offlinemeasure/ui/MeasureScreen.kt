package com.fuad.offlinemeasure.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Box
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
    distanceText: String = "Tap two points on a plane to measure distance",
    renderer: MeasureRenderer? = null,
    tapHelper: TapHelper? = null
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (renderer != null && tapHelper != null) {
            AndroidView(
                factory = { context ->
                    GLSurfaceView(context).apply {
                        SampleRender(this, renderer, context.assets)
                        setOnTouchListener(tapHelper)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = distanceText,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
