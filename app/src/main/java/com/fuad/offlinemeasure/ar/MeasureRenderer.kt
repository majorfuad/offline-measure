package com.fuad.offlinemeasure.ar

import android.util.Log
import com.fuad.offlinemeasure.arhelpers.DisplayRotationHelper
import com.fuad.offlinemeasure.arhelpers.TapHelper
import com.fuad.offlinemeasure.arhelpers.TrackingStateHelper
import com.fuad.offlinemeasure.measurement.DistanceCalculator
import com.fuad.offlinemeasure.rendering.SampleRender
import com.fuad.offlinemeasure.rendering.arcore.BackgroundRenderer
import com.fuad.offlinemeasure.rendering.arcore.PlaneRenderer
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.io.IOException

class MeasureRenderer(
    val activity: android.app.Activity,
    val displayRotationHelper: DisplayRotationHelper,
    val tapHelper: TapHelper
) : SampleRender.Renderer {
    companion object {
        private const val TAG = "MeasureRenderer"
    }

    lateinit var render: SampleRender
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var planeRenderer: PlaneRenderer
    private val trackingStateHelper = TrackingStateHelper(activity)

    private val points = mutableListOf<MeasurementPoint>()
    var onDistanceCalculated: ((Float?) -> Unit)? = null

    override fun onSurfaceCreated(render: SampleRender) {
        this.render = render
        try {
            backgroundRenderer = BackgroundRenderer(render)
            planeRenderer = PlaneRenderer(render)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read asset", e)
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(render: SampleRender) {
        val session = (activity as? com.fuad.offlinemeasure.MainActivity)?.arCoreSessionLifecycleHelper?.session ?: return
        session.setCameraTextureName(backgroundRenderer.cameraColorTexture.textureId)

        displayRotationHelper.updateSessionIfNeeded(session)

        val frame = try {
            session.update()
        } catch (e: Exception) {
            Log.e(TAG, "Camera update exception", e)
            return
        }

        backgroundRenderer.updateDisplayGeometry(frame)
        backgroundRenderer.drawBackground(render)

        val camera = frame.camera
        val trackingState = camera.trackingState
        trackingStateHelper.updateKeepScreenOnFlag(trackingState)

        if (trackingState != TrackingState.TRACKING) {
            return
        }

        val projMatrix = FloatArray(16)
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

        planeRenderer.drawPlanes(render, session.getAllTrackables(Plane::class.java), camera.pose, projMatrix)

        val tap = tapHelper.poll()
        if (tap != null && camera.trackingState == TrackingState.TRACKING) {
            for (hit in frame.hitTest(tap)) {
                val trackable = hit.trackable
                if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose))) {
                    val pose = hit.hitPose
                    val point = MeasurementPoint(pose.tx(), pose.ty(), pose.tz())
                    points.add(point)
                    if (points.size >= 2) {
                        val p1 = points[points.size - 2]
                        val p2 = points[points.size - 1]
                        val distance = DistanceCalculator.distanceMeters(p1, p2)
                        onDistanceCalculated?.invoke(distance)
                    } else {
                        onDistanceCalculated?.invoke(null)
                    }
                    break
                }
            }
        }
    }
}
