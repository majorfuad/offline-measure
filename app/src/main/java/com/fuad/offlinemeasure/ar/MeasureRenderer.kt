package com.fuad.offlinemeasure.ar

import android.util.Log
import android.opengl.Matrix
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import com.google.ar.core.Pose
import kotlin.math.abs
import com.fuad.offlinemeasure.arhelpers.DisplayRotationHelper
import com.fuad.offlinemeasure.arhelpers.TapHelper
import com.fuad.offlinemeasure.arhelpers.TrackingStateHelper
import com.fuad.offlinemeasure.measurement.DistanceCalculator
import com.fuad.offlinemeasure.rendering.SampleRender
import com.fuad.offlinemeasure.rendering.arcore.BackgroundRenderer
import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.IOException

data class ScreenPoint(val x: Float, val y: Float)
data class MeasurementOverlay(val points: List<ScreenPoint>, val width: Int, val height: Int)

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
    private val trackingStateHelper = TrackingStateHelper(activity)

    // Retain ARCore anchors so points stay fixed in world space.
    private val measurementAnchors = mutableListOf<Anchor>()
    private var viewportWidth = 1
    private var viewportHeight = 1
    private val clearRequested = AtomicBoolean(false)
    private data class AnchorNudge(val index: Int, val dx: Float, val dy: Float)
    private val pendingNudges = ConcurrentLinkedQueue<AnchorNudge>()

    /** Queued from UI, applied exclusively on the ARCore GL thread. Pixels are GL-view pixels. */
    fun nudgeAnchor(index: Int, dxPixels: Float, dyPixels: Float) {
        if (index in 0..1 && dxPixels.isFinite() && dyPixels.isFinite()) {
            pendingNudges.offer(AnchorNudge(index, dxPixels, dyPixels))
        }
    }

    private fun applyNudges(
        session: com.google.ar.core.Session,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projectionMatrix, 0, viewMatrix, 0)
        val inverse = FloatArray(16)
        if (!Matrix.invertM(inverse, 0, vp, 0)) {
            pendingNudges.clear()
            return
        }
        while (true) {
            val request = pendingNudges.poll() ?: break
            val anchor = measurementAnchors.getOrNull(request.index) ?: continue
            if (anchor.trackingState != TrackingState.TRACKING) continue
            val pose = anchor.pose
            val clip = FloatArray(4)
            Matrix.multiplyMV(
                clip, 0, vp, 0,
                floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f), 0
            )
            if (clip[3] <= 0.001f) continue
            val deltaX = 2f * request.dx.coerceIn(-24f, 24f) / viewportWidth.coerceAtLeast(1)
            val deltaY = -2f * request.dy.coerceIn(-24f, 24f) / viewportHeight.coerceAtLeast(1)
            // Preserve clip-space depth while moving in screen space.
            // This avoids unreliable new surface hits on thin edges.
            val shiftedClip = floatArrayOf(
                clip[0] + deltaX * clip[3],
                clip[1] + deltaY * clip[3],
                clip[2], clip[3]
            )
            val world = FloatArray(4)
            Matrix.multiplyMV(world, 0, inverse, 0, shiftedClip, 0)
            if (kotlin.math.abs(world[3]) < 1e-6f) continue
            val x = world[0] / world[3]
            val y = world[1] / world[3]
            val z = world[2] / world[3]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            try {
                val moved = session.createAnchor(Pose.makeTranslation(x, y, z))
                measurementAnchors[request.index] = moved
                anchor.detach()
                if (measurementAnchors.size == 2) {
                    val a = measurementAnchors[0].pose
                    val b = measurementAnchors[1].pose
                    val meters = DistanceCalculator.distanceMeters(
                        MeasurementPoint(a.tx(), a.ty(), a.tz()),
                        MeasurementPoint(b.tx(), b.ty(), b.tz())
                    )
                    activity.runOnUiThread { onDistanceCalculated?.invoke(meters) }
                }
                activity.runOnUiThread {
                    onPlacementQualityChanged?.invoke(
                        "Fine adjustment at retained depth — verify actual surface"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not reposition anchor", e)
            }
        }
    }
    private var lastOverlay: MeasurementOverlay? = null
    var onOverlayChanged: ((MeasurementOverlay) -> Unit)? = null

    fun clearMeasurement() {
        clearRequested.set(true)
    }

    private fun publishOverlay(overlay: MeasurementOverlay) {
        val old = lastOverlay
        if (old != null && old.width == overlay.width && old.height == overlay.height &&
            old.points.size == overlay.points.size &&
            old.points.zip(overlay.points).all { (a, b) ->
                abs(a.x - b.x) < 1.5f && abs(a.y - b.y) < 1.5f
            }) return
        lastOverlay = overlay
        activity.runOnUiThread { onOverlayChanged?.invoke(overlay) }
    }

    private fun updateOverlay(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projectionMatrix, 0, viewMatrix, 0)
        val screenPoints = measurementAnchors.mapNotNull { anchor ->
            if (anchor.trackingState != TrackingState.TRACKING) return@mapNotNull null
            val pose = anchor.pose
            val clip = FloatArray(4)
            Matrix.multiplyMV(
                clip, 0, vp, 0,
                floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f), 0
            )
            if (clip[3] <= 0.001f) return@mapNotNull null
            val nx = clip[0] / clip[3]
            val ny = clip[1] / clip[3]
            if (!nx.isFinite() || !ny.isFinite()) return@mapNotNull null
            ScreenPoint((nx + 1f) * viewportWidth / 2f,
                (1f - ny) * viewportHeight / 2f)
        }
        publishOverlay(MeasurementOverlay(screenPoints, viewportWidth, viewportHeight))
    }

    private var hasSetTextureNames = false
    private var rendererReady = false
    private var lastStatus: String? = null
    private var placementFeedback: String? = null

    private fun postStatus(message: String) {
        if (lastStatus == message) return
        lastStatus = message
        activity.runOnUiThread { onTrackingStatusChanged?.invoke(message) }
    }
    var onPlacementQualityChanged: ((String) -> Unit)? = null
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


            rendererReady = true
            Log.d(TAG, "AR renderer initialized successfully")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize AR renderer assets", e)
            postStatus("AR renderer initialization failed. Check Logcat.")
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
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

        if (clearRequested.getAndSet(false)) {
            pendingNudges.clear()
            measurementAnchors.forEach { it.detach() }
            measurementAnchors.clear()
            lastOverlay = null
            publishOverlay(MeasurementOverlay(emptyList(), viewportWidth, viewportHeight))
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

        postStatus(placementFeedback ?: if (hasTrackingPlane) "Surface detected. Tap to measure."
            else "Searching for surfaces...")

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

        // Hide the ARCore grid for an uncluttered camera/measurement view.
        val viewMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)
        applyNudges(session, viewMatrix, projectionMatrix)
        updateOverlay(viewMatrix, projectionMatrix)

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

                // For tile/floor/wall interiors, a tracked plane provides a consistent
                // geometric surface. Prefer it over raw depth, which can vary between taps.
                // Depth/feature hits remain fallbacks for thin door edges outside plane polygons.
                val validHits = hitResults.filter { hit ->
                    val distance = hit.distance
                    if (!distance.isFinite() || distance <= 0.05f || distance > 8f) {
                        false
                    } else {
                        when (val trackable = hit.trackable) {
                            is DepthPoint -> trackable.trackingState == TrackingState.TRACKING
                            is Plane -> trackable.trackingState == TrackingState.TRACKING &&
                                trackable.subsumedBy == null &&
                                trackable.isPoseInPolygon(hit.hitPose)
                            is Point -> trackable.trackingState == TrackingState.TRACKING &&
                                trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                            else -> false
                        }
                    }
                }

                val planeHit = validHits.filter { it.trackable is Plane }
                    .minByOrNull { it.distance }
                val depthHit = validHits.filter { it.trackable is DepthPoint }
                    .minByOrNull { it.distance }
                val featureHit = validHits.filter { it.trackable is Point }
                    .minByOrNull { it.distance }
                val preferredHit = planeHit ?: depthHit ?: featureHit

                if (preferredHit == null) {
                    placementFeedback = "No reliable 3D point here. Move closer and scan the edge."
                    postStatus(placementFeedback!!)
                } else {
                    placementFeedback = null
                }

                for (hit in listOfNotNull(preferredHit)) {

                        // Replace the selected anchor without discarding the other endpoint.
                        // A regular third tap starts a fresh two-point measurement.
                        if (tap.replaceIndex >= 0) {
                            if (tap.replaceIndex >= measurementAnchors.size) {
                                placementFeedback = "Select an existing endpoint to refine."
                                postStatus(placementFeedback!!)
                                break
                            }
                            val replacement = hit.createAnchor()
                            measurementAnchors[tap.replaceIndex].detach()
                            measurementAnchors[tap.replaceIndex] = replacement
                            Log.d(TAG, "Replaced measurement endpoint ${tap.replaceIndex + 1}")
                        } else {
                            if (measurementAnchors.size == 2) {
                                measurementAnchors.forEach { it.detach() }
                                measurementAnchors.clear()
                            }
                            measurementAnchors.add(hit.createAnchor())
                        }

                        if (measurementAnchors.size == 1) {
                            activity.runOnUiThread {
                                onDistanceCalculated?.invoke(null)
                            }
                            Log.d(TAG, "Measurement point 1 placed")
                        } else {
                            val a = measurementAnchors[0].pose
                            val b = measurementAnchors[1].pose
                            val distance = DistanceCalculator.distanceMeters(
                                MeasurementPoint(a.tx(), a.ty(), a.tz()),
                                MeasurementPoint(b.tx(), b.ty(), b.tz())
                            )
                            activity.runOnUiThread {
                                onDistanceCalculated?.invoke(distance)
                            }
                            Log.d(TAG, "Distance measured: $distance meters")
                        }

                        val quality = when (hit.trackable) {
                            is Plane -> "Tracked plane — consistent flat-surface placement"
                            is DepthPoint -> "Depth hit — edge depth may be noisy"
                            is Point -> "Feature point — lower confidence"
                            else -> "AR point — accuracy unknown"
                        }
                        activity.runOnUiThread { onPlacementQualityChanged?.invoke(quality) }
                        updateOverlay(viewMatrix, projectionMatrix)
                        Log.d(TAG, "Measurement tap at (" + tap.x + ", " + tap.y +
                            "), type=" + hit.trackable.javaClass.simpleName +
                            ", hit distance=" + hit.distance + "m")
                        break
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Hit test failed",
                    e
                )
            }
        }

        // The UI overlay renders projected anchor markers and a line.
    }
}
