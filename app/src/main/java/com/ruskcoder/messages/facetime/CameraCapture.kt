package com.ruskcoder.messages.facetime

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.OrientationEventListener

/**
 * The camera, drawn into the encoder and, when there is one, a local preview at the same time.
 *
 * Frames leave in the sensor's own orientation and the rotation travels beside them, the way
 * FaceTime does it: the receiver turns the picture upright, so a phone turning mid-call costs a
 * metadata change instead of an encoder rebuild.
 */
class CameraCapture(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("CameraCapture").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null

    @Volatile var front: Boolean = true
        private set
    @Volatile private var sensorOrientation: Int = 0

    /** The capture size for the active camera, which the encoder must be created at. */
    fun captureSize(front: Boolean = this.front): Size {
        val id = cameraId(front) ?: return DEFAULT_SIZE
        val map = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return DEFAULT_SIZE
        val sizes = map.getOutputSizes(MediaCodec::class.java) ?: return DEFAULT_SIZE
        return sizes.firstOrNull { it == DEFAULT_SIZE }
            ?: sizes.filter { it.width * 9 == it.height * 16 && it.width <= DEFAULT_SIZE.width }
                .maxByOrNull { it.width }
            ?: sizes.minBy { kotlin.math.abs(it.width * it.height - DEFAULT_SIZE.width * DEFAULT_SIZE.height) }
    }

    /**
     * Clockwise degrees the receiver must rotate the sensor image by to show it upright, given how
     * the device is held right now. The front camera's image is mirrored, so its display rotation
     * runs the other way.
     */
    fun rotationDegrees(): Int {
        val device = deviceOrientation
        return if (front) (sensorOrientation + device) % 360 else (sensorOrientation - device + 360) % 360
    }

    /**
     * How the device is physically held, quantized to 90°. Read from the accelerometer rather than
     * the display, because the call screen is locked to portrait and its display never rotates.
     */
    @Volatile private var deviceOrientation = 0
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            // Clockwise device rotation from natural, as the display's own rotation counts it.
            deviceOrientation = (360 - ((orientation + 45) / 90 * 90) % 360) % 360
        }
    }

    @SuppressLint("MissingPermission") // the call flow requests CAMERA before turning video on
    fun start(front: Boolean, encoder: Surface, preview: Surface?) = handler.post {
        stopInternal()
        this.front = front
        orientationListener.enable()
        encoderSurface = encoder
        previewSurface = preview
        val id = cameraId(front) ?: run {
            Log.w(TAG, "No ${if (front) "front" else "back"} camera")
            return@post
        }
        sensorOrientation = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error $error")
                    camera.close()
                    if (device === camera) device = null
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
        }
    }

    /** Swap the local preview target without reopening the camera's encoder side. */
    fun setPreview(preview: Surface?) = handler.post {
        if (preview == previewSurface) return@post
        previewSurface = preview
        device?.let { createSession(it) }
    }

    fun stop() = handler.post {
        orientationListener.disable()
        stopInternal()
    }

    fun release() = handler.post {
        orientationListener.disable()
        stopInternal()
        thread.quitSafely()
    }

    private fun stopInternal() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
    }

    private fun createSession(camera: CameraDevice) {
        val targets = listOfNotNull(encoderSurface, previewSurface).filter { it.isValid }
        if (targets.isEmpty()) return
        runCatching { session?.close() }
        try {
            @Suppress("DEPRECATION") // the SessionConfiguration overload is API 28+
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(created: CameraCaptureSession) {
                    if (device !== camera) {
                        created.close()
                        return
                    }
                    session = created
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        targets.forEach { addTarget(it) }
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FRAME_RATE / 2, FRAME_RATE))
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    }.build()
                    runCatching { created.setRepeatingRequest(request, null, handler) }
                        .onFailure { Log.e(TAG, "setRepeatingRequest failed", it) }
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
        }
    }

    private fun cameraId(front: Boolean): String? {
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    companion object {
        private const val TAG = "CameraCapture"
        const val FRAME_RATE = 30
        val DEFAULT_SIZE = Size(1280, 720)
    }
}
