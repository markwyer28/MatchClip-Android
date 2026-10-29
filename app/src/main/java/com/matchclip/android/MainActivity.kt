package com.matchclip.android

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var previewContainer: FrameLayout
    private lateinit var mainRoot: LinearLayout
    private lateinit var headerView: LinearLayout
    private lateinit var controlsPanel: LinearLayout
    private lateinit var status: TextView
    private lateinit var settingsButton: Button
    private lateinit var zoomLabel: TextView
    private lateinit var exposure: SeekBar
    private lateinit var exposureLabel: TextView
    private var exposureValue = 0
    private lateinit var zoom: SeekBar
    private var zoomValue = 1f
    private var activeBuilder: CaptureRequest.Builder? = null
    private var cameraId: String? = null
    private var sensorDegrees = 90
    private var frontFacing = false
    private var clipOrientation = 0
    private var rotationAdjustment = 0
    private var lastPreviewRotation = -1
    private lateinit var startButton: Button
    private lateinit var markButton: Button
    private val cameraThread = HandlerThread("CameraThread")
    private lateinit var handler: Handler
    private lateinit var manager: CameraManager
    private var camera: CameraDevice? = null
    private var openingCamera = false
    private var switchingSession = false
    private var previewSurface: Surface? = null
    private var cameraGeneration = 0
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var outputFormat: MediaFormat? = null
    private val samples = ArrayDeque<Sample>()
    private val pending = mutableListOf<Highlight>()
    private val guard = Any()
    private val running = AtomicBoolean(false)
    private var firstPts = -1L
    private var latestPts = 0L
    private var sequence = 0
    private var drainThread: Thread? = null
    private var cameraIds = listOf<String>()
    data class Sample(val bytes: ByteArray, val pts: Long, val flags: Int)
    data class Highlight(val target: Long, val number: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraThread.start()
        handler = Handler(cameraThread.looper)
        manager = getSystemService(CAMERA_SERVICE) as CameraManager
        window.statusBarColor = Color.rgb(12, 20, 35)
        window.navigationBarColor = Color.rgb(12, 20, 35)
        val navy = Color.rgb(12, 20, 35)
        val panel = Color.rgb(24, 36, 56)
        val accent = Color.rgb(52, 183, 139)
        val muted = Color.rgb(174, 190, 209)
        fun rounded(color: Int, radius: Float = 18f): GradientDrawable =
            GradientDrawable().apply { setColor(color); cornerRadius = radius * resources.displayMetrics.density }
        fun caption(value: String, size: Float = 13f): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(muted)
        }
        fun action(value: String, background: Int): Button = Button(this).apply {
            text = value; isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE); this.background = rounded(background, 14f)
            setPadding(14, 4, 14, 4)
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        // Apply real system-bar insets: Android 15+ may draw edge-to-edge,
        // otherwise the title and recording controls overlap system UI.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(android.view.WindowInsets.Type.systemBars())
            else null
            val top = bars?.top ?: insets.systemWindowInsetTop
            val bottom = bars?.bottom ?: insets.systemWindowInsetBottom
            val left = bars?.left ?: insets.systemWindowInsetLeft
            val right = bars?.right ?: insets.systemWindowInsetRight
            view.setPadding(dp(12) + left, dp(10) + top, dp(12) + right, dp(12) + bottom)
            insets
        }
        root.requestApplyInsets()
        // Landscape-only camera screen. The controls float over the video,
        // leaving the maximum possible area for the full 16:9 frame.
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerView = header
        previewContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val previewFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = TextureView(this)
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        previewContainer.addView(previewFrame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(previewContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            preview.post { updatePreview() }
        }

        val liveTag = TextView(this).apply {
            text = "MATCHCLIP  •  16:9"; textSize = 12f
            setTextColor(Color.WHITE); setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(37, 48, 66), 10f)
        }
        previewContainer.addView(liveTag, FrameLayout.LayoutParams(-2, -2,
            Gravity.TOP or Gravity.LEFT).apply { setMargins(dp(12), dp(12), 0, 0) })

        settingsButton = action("⚙ Settings", Color.rgb(55, 72, 96))
        previewContainer.addView(settingsButton, FrameLayout.LayoutParams(dp(122), dp(46),
            Gravity.TOP or Gravity.RIGHT).apply { setMargins(0, dp(12), dp(12), 0) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(panel)
            visibility = View.GONE
        }
        controlsPanel = bottom
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        zoomRow.addView(caption("ZOOM", 12f), LinearLayout.LayoutParams(dp(55), -2))
        zoom = SeekBar(this).apply {
            max = 100; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        zoomRow.addView(zoom, LinearLayout.LayoutParams(0, dp(44), 1f))
        zoomLabel = TextView(this).apply {
            text = "1.0×"; setTextColor(Color.WHITE); textSize = 15f
            gravity = Gravity.CENTER
        }
        zoomRow.addView(zoomLabel, LinearLayout.LayoutParams(dp(55), -2))
        bottom.addView(zoomRow)

        val exposureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        exposureRow.addView(caption("LIGHT", 12f), LinearLayout.LayoutParams(dp(55), -2))
        exposure = SeekBar(this).apply {
            max = 24; progress = 12
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        exposureRow.addView(exposure, LinearLayout.LayoutParams(0, dp(44), 1f))
        exposureLabel = TextView(this).apply {
            text = "0"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        }
        exposureRow.addView(exposureLabel, LinearLayout.LayoutParams(dp(55), -2))
        bottom.addView(exposureRow)
        bottom.addView(caption("HIGHLIGHTS  •  15 seconds before / 5 seconds after", 12f))
        previewContainer.addView(bottom, FrameLayout.LayoutParams(dp(330), -2,
            Gravity.TOP or Gravity.RIGHT).apply { setMargins(0, dp(65), dp(12), 0) })
        settingsButton.setOnClickListener {
            bottom.visibility = if (bottom.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            settingsButton.text = if (bottom.visibility == View.VISIBLE) "✕ Close" else "⚙ Settings"
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        startButton = action("● Start", Color.rgb(51, 111, 157))
        markButton = action("★ SAVE HIGHLIGHT", accent).apply { isEnabled = false }
        actions.addView(startButton, LinearLayout.LayoutParams(dp(125), dp(54)))
        actions.addView(markButton, LinearLayout.LayoutParams(dp(220), dp(54)).apply {
            leftMargin = dp(10)
        })
        previewContainer.addView(actions, FrameLayout.LayoutParams(-2, -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(14)
            })
        status = TextView(this).apply {
            text = "Live preview · frame the pitch, then Start"
            textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(Color.rgb(37, 48, 66), 10f)
        }
        previewContainer.addView(status, FrameLayout.LayoutParams(-2, -2,
            Gravity.BOTTOM or Gravity.LEFT).apply {
                setMargins(dp(12), 0, 0, dp(14))
            })
        mainRoot = root
        setContentView(root)
        startButton.setOnClickListener {
            if (running.get()) stopRecording() else startRecording()
        }
        markButton.setOnClickListener { markHighlight() }
        zoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val range = cameraId?.let { manager.getCameraCharacteristics(it).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }
                val low = range?.lower ?: 1f
                val high = (range?.upper ?: 8f).coerceAtMost(8f)
                zoomValue = low + (high - low) * value / 100f
                zoomLabel.text = String.format(java.util.Locale.US, "%.1f×", zoomValue)
                applyControls()
            }
            override fun onStartTrackingTouch(view: SeekBar?) {}
            override fun onStopTrackingTouch(view: SeekBar?) {}
        })
        exposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                exposureValue = value - 12
                exposureLabel.text = if (exposureValue > 0) "+$exposureValue" else "$exposureValue"
                applyControls()
            }
            override fun onStartTrackingTouch(view: SeekBar?) {}
            override fun onStopTrackingTouch(view: SeekBar?) {}
        })
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { listCameras(); updatePreview(); preview.post { openPreviewCamera() } }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { updatePreview() }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { stopRecording(); closeCameraPreview(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        else { listCameras(); preview.post { openPreviewCamera() } }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { listCameras(); preview.post { openPreviewCamera() } }
        else message("Camera permission required")
    }

    private fun listCameras() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraIds = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }.sortedWith(compareBy<String> {
            // Prefer the standard rear camera (Pixel 6 logical camera ID 0).
            if (it == "0") 0 else 1
        }.thenBy { it })
    }

    private fun closeCameraPreview() {
        cameraGeneration++
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { previewSurface?.release() } catch (_: Exception) {}
        session = null
        camera = null
        previewSurface = null
        activeBuilder = null
        openingCamera = false
        switchingSession = false
    }

    private fun openPreviewCamera() {
        if (running.get() || openingCamera || switchingSession || !preview.isAvailable || cameraIds.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val id = cameraIds.first()
        if (cameraId == id && camera != null && session != null) return
        closeCameraPreview()
        cameraId = id
        val characteristics = manager.getCameraCharacteristics(id)
        sensorDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        runOnUiThread { zoom.progress = 0 }
        zoomValue = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
        updatePreview()
        val generation = cameraGeneration
        openingCamera = true
        message("Opening camera preview…")
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (generation != cameraGeneration || running.get()) { device.close(); return }
                    camera = device
                    openingCamera = false
                    createCameraSession(record = false)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (generation == cameraGeneration) { closeCameraPreview(); message("Camera disconnected") }
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (generation == cameraGeneration) { closeCameraPreview(); message("Camera error $error") }
                }
            }, handler)
        } catch (e: Exception) { openingCamera = false; message("Preview error: " + e.message) }
    }

    private fun createCameraSession(record: Boolean) {
        val device = camera ?: return
        val texture = preview.surfaceTexture ?: return
        val generation = cameraGeneration
        switchingSession = true
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { previewSurface?.release() } catch (_: Exception) {}
        texture.setDefaultBufferSize(1280, 720)
        preview.post { updatePreview() }
        val display = Surface(texture)
        previewSurface = display
        val surfaces = mutableListOf(display)
        if (record) {
            val videoSurface = encoderSurface ?: return
            surfaces.add(videoSurface)
        }
        try {
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (generation != cameraGeneration || (record != running.get())) {
                        configured.close(); return
                    }
                    session = configured
                    switchingSession = false
                    try {
                        activeBuilder = device.createCaptureRequest(
                            if (record) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                        ).apply {
                            surfaces.forEach { addTarget(it) }
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        applyControls()
                        message(if (record) "Recording · volume up saves highlight" else "Live preview · frame the pitch, then Start")
                    } catch (e: Exception) { message("Camera request error: " + e.message) }
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    switchingSession = false
                    configured.close()
                    message("Camera preview configuration failed")
                }
            }, handler)
        } catch (e: Exception) { switchingSession = false; message("Session error: " + e.message) }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!preview.isAvailable || cameraIds.isEmpty()) { message("Camera unavailable"); return }
        try {
            synchronized(guard) { samples.clear(); pending.clear(); outputFormat = null; firstPts = -1L; latestPts = 0L; sequence = 0 }
            val config = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(config, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            encoderSurface = encoder!!.createInputSurface()
            encoder!!.start()
            running.set(true)
            drainThread = Thread({ drainEncoder() }, "EncoderDrain").also { it.start() }
            val id = cameraIds.first()
            cameraId = id
            val characteristics = manager.getCameraCharacteristics(id)
            sensorDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            // The MP4 orientation and preview must agree at recording start.
            clipOrientation = 0
            updatePreview()
            if (camera != null && cameraId == id) {
                createCameraSession(record = true)
            } else {
                closeCameraPreview()
                cameraId = id
                val generation = cameraGeneration
                openingCamera = true
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (generation != cameraGeneration) { device.close(); return }
                        openingCamera = false
                        camera = device
                        createCameraSession(record = true)
                    }
                    override fun onDisconnected(device: CameraDevice) { device.close(); stopRecording() }
                    override fun onError(device: CameraDevice, error: Int) {
                        device.close(); message("Camera error $error"); stopRecording()
                    }
                }, handler)
            }
            startButton.text = "■  Stop"
            markButton.isEnabled = true
        } catch (e: Exception) { message("Start failed: ${e.message}"); stopRecording() }
    }

    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val codec = encoder ?: break
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) synchronized(guard) { outputFormat = codec.outputFormat }
                else if (index >= 0) {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        synchronized(guard) {
                            if (firstPts < 0) firstPts = info.presentationTimeUs
                            latestPts = info.presentationTimeUs
                            samples.addLast(Sample(bytes, latestPts, info.flags))
                            while (samples.size > 1 && latestPts - samples.first.pts > 23_000_000L) samples.removeFirst()
                            val ready = pending.filter { latestPts >= it.target + 5_000_000L }
                            ready.forEach { highlight ->
                                val fmt = outputFormat
                                if (fmt != null) saveHighlight(samples.toList(), fmt, highlight)
                                pending.remove(highlight)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } catch (e: Exception) { message("Encoder failed: ${e.message}") }
    }

    private fun markHighlight() {
        if (!running.get()) return
        synchronized(guard) {
            if (firstPts < 0 || latestPts - firstPts < 16_000_000L) { message("Buffer warming up · wait 16 seconds"); return }
            val h = Highlight(latestPts, ++sequence)
            pending.add(h)
            message("Highlight ${h.number} marked; saving after 5 seconds")
        }
    }

    private fun saveHighlight(snapshot: List<Sample>, fmt: MediaFormat, h: Highlight) {
        Thread({
            try {
                val targetStart = h.target - 15_000_000L
                val keyframe = snapshot.indexOfLast { it.pts <= targetStart && (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
                val fallback = snapshot.indexOfFirst { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
                val startIndex = if (keyframe >= 0) keyframe else fallback
                if (startIndex < 0) throw IllegalStateException("No sync frame")
                val segment = snapshot.drop(startIndex).takeWhile { it.pts <= h.target + 5_000_000L }
                if (segment.isEmpty()) throw IllegalStateException("Empty clip")
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "MatchClip_${System.currentTimeMillis()}_${h.number}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MatchClip")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Cannot create output")
                try {
                    val descriptor = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("Cannot open output")
                    descriptor.use {
                        val muxer = MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        try {
                            muxer.setOrientationHint(clipOrientation)
                            val track = muxer.addTrack(fmt)
                            muxer.start()
                            val origin = segment.first().pts
                            val bufferInfo = MediaCodec.BufferInfo()
                            segment.forEach { sample ->
                                bufferInfo.set(0, sample.bytes.size, (sample.pts - origin).coerceAtLeast(0), sample.flags)
                                muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), bufferInfo)
                            }
                            muxer.stop()
                        } finally { muxer.release() }
                    }
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    message("Saved highlight ${h.number} in Movies/MatchClip")
                } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
            } catch (e: Exception) { message("Save failed: ${e.message}") }
        }, "SaveHighlight${h.number}").start()
    }

    private fun stopRecording() {
        if (!running.getAndSet(false)) return
        runOnUiThread { markButton.isEnabled = false; startButton.text = "● Start" }
        try { session?.close() } catch (_: Exception) {}
        session = null; activeBuilder = null
        try { drainThread?.join(1500) } catch (_: Exception) {}
        try { encoder?.stop(); encoder?.release(); encoderSurface?.release() } catch (_: Exception) {}
        encoder = null; encoderSurface = null
        val lost = synchronized(guard) { val n = pending.size; pending.clear(); n }
        message(if (lost > 0) "Stopped; $lost pending clips lost. Wait 5 seconds after tagging." else "Stopped · clips in Movies/MatchClip")
        preview.post { if (preview.isAvailable) {
            if (camera != null) createCameraSession(record = false) else openPreviewCamera()
        } }
    }


    private fun displayDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun calculatedPreviewDegrees(): Int = 0

    private fun updatePreview() {
        if (!::preview.isInitialized || !preview.isAvailable) return
        val holderW = previewContainer.width
        val holderH = previewContainer.height
        if (holderW <= 0 || holderH <= 0) return

        // The camera feeds identical 1280x720 buffers to the TextureView and
        // H.264 encoder. Do not rotate the preview independently: doing so
        // displays a portrait-shaped image that cannot match the 16:9 MP4.
        // Fit a true 16:9 viewport into the available landscape space.
        val aspect = 16f / 9f
        val fittedW = minOf(holderW.toFloat(), holderH * aspect).toInt().coerceAtLeast(1)
        val fittedH = minOf(holderH.toFloat(), holderW / aspect).toInt().coerceAtLeast(1)
        val frame = preview.parent as FrameLayout
        val params = frame.layoutParams as FrameLayout.LayoutParams
        if (params.width != fittedW || params.height != fittedH) {
            params.width = fittedW
            params.height = fittedH
            params.gravity = Gravity.CENTER
            frame.layoutParams = params
            preview.post { updatePreview() }
            return
        }

        // The Pixel 6 preview arrives 90 degrees clockwise in this
        // landscape configuration. Correct the live TextureView anticlockwise,
        // without touching the encoder surface or MP4 orientation metadata.
        // Keep the 16:9 viewport: compensate for the swapped dimensions.
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val transform = Matrix()
        transform.setRotate(270f, cx, cy)
        transform.postScale(w / h, h / w, cx, cy)
        preview.setTransform(transform)
    }

    private fun applyControls() {
        val builder = activeBuilder ?: return
        val active = session ?: return
        val id = cameraId ?: return
        handler.post {
            try {
                val characteristics = manager.getCameraCharacteristics(id)
                val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (range != null) {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomValue.coerceIn(range.lower, range.upper))
                }
                val compensation = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (compensation != null) {
                    val steps = if (exposureValue >= 0) exposureValue * compensation.upper / 12f
                        else exposureValue * -compensation.lower / 12f
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        steps.toInt().coerceIn(compensation.lower, compensation.upper))
                }
                active.setRepeatingRequest(builder.build(), null, handler)
            } catch (e: Exception) { message("Control error: " + e.message) }
        }
    }

    private fun message(text: String) { runOnUiThread { status.text = text } }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) markHighlight()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onDestroy() { stopRecording(); closeCameraPreview(); cameraThread.quitSafely(); super.onDestroy() }
}