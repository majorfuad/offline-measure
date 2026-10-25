package com.fuad.offlinemeasure

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fuad.offlinemeasure.ar.ARCoreSessionLifecycleHelper
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

    var distanceText by mutableStateOf("Tap two points on a plane to measure distance")

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                showApp()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showApp() {
        arCoreSessionLifecycleHelper = ARCoreSessionLifecycleHelper(this)
        lifecycle.addObserver(arCoreSessionLifecycleHelper)

        displayRotationHelper = DisplayRotationHelper(this)
        tapHelper = TapHelper(this)

        renderer = MeasureRenderer(this, displayRotationHelper, tapHelper).apply {
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
                        distanceText = distanceText,
                        renderer = renderer,
                        tapHelper = tapHelper
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        displayRotationHelper.onResume()
    }

    override fun onPause() {
        super.onPause()
        displayRotationHelper.onPause()
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
