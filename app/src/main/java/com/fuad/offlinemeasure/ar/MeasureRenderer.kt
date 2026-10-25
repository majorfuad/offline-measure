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
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
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
    private var hasSetTextureNames = false
    private var rendererReady = false
    private var lastStatus: String? = null

    private fun postStatus(message: String) {
        if (lastStatus == message) return
        lastStatus = message
        activity.runOnUiThread { onTrackingStatusChanged?.invoke(message) }
    }
    var onDistanceCalculated: ((Float?) -> Unit)? = null
    var onTrackingStatusChanged: ((String) -> Unit)? = null

    fun resetCameraTexture() {
        hasSetTextureNames = false
    }

    override fun onSurfaceCreated(render: SampleRender) {
        this.render = render
        rendererReady = false
        hasSetTextureNames = false

        try {
            backgroundRenderer = BackgroundRenderer(render)

            // IMPORTANT:
            // Creates the normal camera background shader.
            backgroundRenderer.setUseDepthVisualization(
                render,
                false
            )

            // We aren't using depth occlusion yet.
            backgroundRenderer.setUseOcclusion(
                render,
                false
            )

            planeRenderer = PlaneRenderer(render)

            rendererReady = true
            Log.d(TAG, "AR renderer initialized successfully")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize AR renderer assets", e)
            postStatus("AR renderer initialization failed. Check Logcat.")
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(render: SampleRender) {
        if (!rendererReady) return

        val activity =
            activity as? com.fuad.offlinemeasure.MainActivity
                ?: return

        val session =
            activity.arCoreSessionLifecycleHelper.session
                ?: run {
                    postStatus("Starting AR camera...")
                    return
                }

        /*
         * ARCore needs to know which OpenGL texture it should write
         * the live camera image into.
         *
         * Do this only once after the GL texture has been created.
         */
        if (!hasSetTextureNames) {

            session.setCameraTextureNames(
                intArrayOf(
                    backgroundRenderer
                        .cameraColorTexture
                        .textureId
                )
            )

            hasSetTextureNames = true

            Log.d(
                TAG,
                "Camera texture configured: " +
                        backgroundRenderer
                            .cameraColorTexture
                            .textureId
            )
        }

        /*
         * Update ARCore when display orientation/size changes.
         *
         * IMPORTANT:
         * This must happen before session.update().
         */
        displayRotationHelper.updateSessionIfNeeded(
            session
        )

        /*
         * Request the newest ARCore frame.
         */
        val frame = try {

            session.update()

        } catch (e: CameraNotAvailableException) {

            Log.e(
                TAG,
                "Camera not available",
                e
            )

            activity.runOnUiThread {

                onTrackingStatusChanged?.invoke(
                    "Camera unavailable"
                )
            }

            return

        } catch (e: Exception) {

            Log.e(TAG, "ARCore frame update failed", e)
            postStatus("ARCore camera update failed. Check Logcat.")
            return
        }

        val camera = frame.camera

        /*
         * Update texture coordinates whenever screen geometry,
         * camera orientation, etc. changes.
         */
        try {
            backgroundRenderer.updateDisplayGeometry(frame)
        } catch (e: Exception) {
            Log.e(TAG, "Camera display geometry update failed", e)
            postStatus("Camera preview geometry failed. Check Logcat.")
            return
        }

        /*
         * timestamp == 0 means ARCore has not yet supplied
         * a valid camera frame.
         */
        if (frame.timestamp != 0L) {

            try {

                backgroundRenderer.drawBackground(
                    render
                )

            } catch (e: Exception) {

                Log.e(TAG, "Failed to draw camera background", e)
                postStatus("Camera preview draw failed. Check Logcat.")
            }
        }

        /*
         * Update screen-on behavior based on AR tracking.
         */
        trackingStateHelper.updateKeepScreenOnFlag(
            camera.trackingState
        )

        /*
         * If ARCore isn't tracking yet, don't attempt
         * plane rendering or hit testing.
         */
        if (
            camera.trackingState !=
            TrackingState.TRACKING
        ) {

            val failureReason =
                TrackingStateHelper
                    .getTrackingFailureReasonString(
                        camera
                    )

            val message =
                if (failureReason.isNotEmpty()) {

                    failureReason

                } else {

                    "Searching for surfaces..."
                }

            postStatus(message)

            return
        }

        /*
         * Check whether we ACTUALLY have a tracked plane.
         *
         * Camera tracking alone does not mean that
         * a usable surface has been found.
         */
        val planes =
            session.getAllTrackables(
                Plane::class.java
            )

        val hasTrackingPlane =
            planes.any { plane ->

                plane.trackingState ==
                        TrackingState.TRACKING
            }

        postStatus(
            if (hasTrackingPlane) "Surface detected. Tap to measure."
            else "Searching for surfaces..."
        )

        /*
         * Get projection matrix for drawing AR objects.
         */
        val projectionMatrix =
            FloatArray(16)

        camera.getProjectionMatrix(
            projectionMatrix,
            0,
            0.1f,
            100.0f
        )

        /*
         * Draw detected planes.
         */
        try {

            planeRenderer.drawPlanes(
                render,
                planes,
                camera.displayOrientedPose,
                projectionMatrix
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to render planes",
                e
            )
        }

        /*
         * ---------------------------------------------------------
         * HANDLE USER TAP
         * ---------------------------------------------------------
         *
         * TapHelper stores MotionEvents coming from the UI thread.
         * We consume one here on the GL/render thread.
         */
        val tap =
            tapHelper.poll()

        if (
            tap != null &&
            camera.trackingState ==
            TrackingState.TRACKING
        ) {

            try {

                val hitResults =
                    frame.hitTest(
                        tap.x,
                        tap.y
                    )

                for (hit in hitResults) {

                    val trackable =
                        hit.trackable

                    val validHit =
                        when (trackable) {

                            is Plane -> {

                                trackable.isPoseInPolygon(
                                    hit.hitPose
                                )
                            }

                            is Point -> {

                                trackable.orientationMode ==
                                        Point.OrientationMode
                                            .ESTIMATED_SURFACE_NORMAL
                            }

                            else -> false
                        }

                    if (validHit) {

                        /*
                         * For now we're just logging the hit.
                         *
                         * T14/T16 will convert this into anchors.
                         */
                        val pose =
                            hit.hitPose

                        Log.d(
                            TAG,
                            """
                        Valid AR hit:
                        x=${pose.tx()}
                        y=${pose.ty()}
                        z=${pose.tz()}
                        distance=${hit.distance}
                        trackable=${trackable.javaClass.simpleName}
                        """.trimIndent()
                        )

                        /*
                         * If you ALREADY implemented your
                         * anchor-placement function, uncomment:
                         *
                         * addAnchor(hit.createAnchor())
                         */

                        break
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Hit test failed",
                    e
                )
            }
        }

        /*
         * Later phases go here:
         *
         * drawAnchors(...)
         * drawMeasurementLine(...)
         * updateDistance(...)
         */
    }
}
