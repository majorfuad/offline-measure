package com.fuad.offlinemeasure

import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fuad.offlinemeasure.ar.ARCoreSessionLifecycleHelper
import com.fuad.offlinemeasure.ar.MeasurementOverlay
import com.fuad.offlinemeasure.ar.MeasureRenderer
import com.fuad.offlinemeasure.arhelpers.DisplayRotationHelper
import com.fuad.offlinemeasure.arhelpers.TapHelper
import com.fuad.offlinemeasure.ui.MeasureScreen
import com.fuad.offlinemeasure.ui.theme.OfflineMeasureTheme

class MainActivity : ComponentActivity() {
    lateinit var arCoreSessionLifecycleHelper: ARCoreSessionLifecycleHelper
    lateinit var displayRotationHelper: DisplayRotationHelper
    lateinit var tapHelper: TapHelper
    lateinit var renderer: MeasureRenderer
    var glSurfaceView: GLSurfaceView? = null

    var trackingStatusText by mutableStateOf("Searching for surfaces...")
    var distanceText by mutableStateOf("Tap two points on a plane to measure distance")
    var measurementOverlay by mutableStateOf(MeasurementOverlay(emptyList(), 1, 1))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        displayRotationHelper = DisplayRotationHelper(this)
        tapHelper = TapHelper(this)

        arCoreSessionLifecycleHelper = ARCoreSessionLifecycleHelper(this).apply {
            beforeSessionResume = { session ->
                val config = com.google.ar.core.Config(session).apply {
                    planeFindingMode = com.google.ar.core.Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    focusMode = com.google.ar.core.Config.FocusMode.AUTO
                }
                session.configure(config)
            }
        }
        arCoreSessionLifecycleHelper.exceptionCallback = { error ->
            android.util.Log.e("MainActivity", "ARCore session failed", error)
            runOnUiThread {
                trackingStatusText = "ARCore session failed: " +
                    (error.message ?: error.javaClass.simpleName)
            }
        }
        lifecycle.addObserver(arCoreSessionLifecycleHelper)

        renderer = MeasureRenderer(this, displayRotationHelper, tapHelper).apply {
            onOverlayChanged = { overlay -> measurementOverlay = overlay }
            onTrackingStatusChanged = { status ->
                trackingStatusText = status
            }
            onDistanceCalculated = { distance ->
                distanceText = if (distance != null) {
                    String.format("Distance: %.2f meters", distance)
                } else {
                    "Point 1 set. Tap second point."
                }
            }
        }

        setContent {
            OfflineMeasureTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MeasureScreen(
                        modifier = Modifier.padding(innerPadding),
                        trackingStatusText = trackingStatusText,
                        distanceText = distanceText,
                        overlay = measurementOverlay,
                        onClear = {
                            renderer.clearMeasurement()
                            distanceText = "Tap two points on a plane to measure distance"
                            measurementOverlay = MeasurementOverlay(emptyList(), 1, 1)
                        },
                        renderer = renderer,
                        tapHelper = tapHelper,
                        onSurfaceCreated = { view ->
                            glSurfaceView = view
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (::displayRotationHelper.isInitialized) {
            displayRotationHelper.onResume()
        }

        if (::renderer.isInitialized) {
            renderer.resetCameraTexture()
        }

        glSurfaceView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::displayRotationHelper.isInitialized) {
            displayRotationHelper.onPause()
        }
        glSurfaceView?.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (::arCoreSessionLifecycleHelper.isInitialized) {
            arCoreSessionLifecycleHelper.onRequestPermissionsResult(requestCode, permissions, results)
        }
    }
}
