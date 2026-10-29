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
    private lateinit var status: TextView
    private lateinit var lenses: Spinner
    private lateinit var zoomLabel: TextView
    private lateinit var exposure: SeekBar
    private lateinit var exposureLabel: TextView
    private var exposureValue = 0
    private lateinit var zoom: SeekBar
    private lateinit var torch: Button
    private var torchOn = false
    private var zoomValue = 1f
    private var activeBuilder: CaptureRequest.Builder? = null
    private var cameraId: String? = null
    private var sensorDegrees = 90
    private var clipOrientation = 90
    private lateinit var startButton: Button
    private lateinit var markButton: Button
    private val cameraThread = HandlerThread("CameraThread")
    private lateinit var handler: Handler
    private lateinit var manager: CameraManager
    private var camera: CameraDevice? = null
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(12, 10, 12, 12)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 2, 8, 10) }
        header.addView(TextView(this).apply {
            text = "MATCHCLIP"; textSize = 22f; setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD); letterSpacing = 0.09f
        })
        header.addView(caption("FOOTBALL HIGHLIGHT CAMERA  •  15s BEFORE / 5s AFTER", 11f))
        root.addView(header)
        val previewFrame = android.widget.FrameLayout(this).apply { background = rounded(Color.BLACK, 18f) }
        preview = TextureView(this)
        previewFrame.addView(preview, android.widget.FrameLayout.LayoutParams(-1, -1))
        val liveTag = TextView(this).apply {
            text = "●  PREVIEW"; textSize = 12f; setTextColor(Color.WHITE)
            setPadding(16, 10, 16, 10); background = rounded(Color.rgb(37, 48, 66), 10f)
        }
        previewFrame.addView(liveTag, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.LEFT).apply {
            setMargins(12, 12, 0, 0)
        })
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(14, 10, 14, 12)
            background = rounded(panel)
        }
        val cameraRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        cameraRow.addView(caption("CAMERA", 12f), LinearLayout.LayoutParams(76, -2))
        lenses = Spinner(this)
        cameraRow.addView(lenses, LinearLayout.LayoutParams(0, 48, 1f))
        torch = action("Torch off", Color.rgb(55, 72, 96))
        cameraRow.addView(torch, LinearLayout.LayoutParams(112, 46))
        bottom.addView(cameraRow)
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        zoomRow.addView(caption("ZOOM", 12f), LinearLayout.LayoutParams(76, -2))
        zoom = SeekBar(this).apply { max = 100; progress = 0; progressTintList = android.content.res.ColorStateList.valueOf(accent) }
        zoomRow.addView(zoom, LinearLayout.LayoutParams(0, 48, 1f))
        zoomLabel = TextView(this).apply { text = "1.0×"; setTextColor(Color.WHITE); textSize = 15f; gravity = android.view.Gravity.CENTER }
        zoomRow.addView(zoomLabel, LinearLayout.LayoutParams(68, -2))
        bottom.addView(zoomRow)
        val exposureRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        exposureRow.addView(caption("LIGHT", 12f), LinearLayout.LayoutParams(76, -2))
        exposure = SeekBar(this).apply { max = 24; progress = 12; progressTintList = android.content.res.ColorStateList.valueOf(accent) }
        exposureRow.addView(exposure, LinearLayout.LayoutParams(0, 48, 1f))
        exposureLabel = TextView(this).apply { text = "0"; setTextColor(Color.WHITE); textSize = 15f; gravity = android.view.Gravity.CENTER }
        exposureRow.addView(exposureLabel, LinearLayout.LayoutParams(68, -2))
        bottom.addView(exposureRow)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
        startButton = action("●  Start", Color.rgb(51, 111, 157))
        markButton = action("★  SAVE HIGHLIGHT", accent).apply { isEnabled = false }
        actions.addView(startButton, LinearLayout.LayoutParams(0, 58, 1f).apply { rightMargin = 10 })
        actions.addView(markButton, LinearLayout.LayoutParams(0, 58, 2f))
        bottom.addView(actions)
        status = TextView(this).apply {
            text = "Ready · Choose a camera, then press Start"
            textSize = 12f; setTextColor(muted); gravity = android.view.Gravity.CENTER
            setPadding(4, 4, 4, 0)
        }
        bottom.addView(status)
        root.addView(bottom)
        setContentView(root)
        startButton.setOnClickListener { if (running.get()) stopRecording() else startRecording() }
        markButton.setOnClickListener { markHighlight() }
        torch.setOnClickListener { torchOn = !torchOn; torch.text = if (torchOn) "Torch on" else "Torch off"; applyControls() }
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
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { listCameras(); updatePreview() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { updatePreview() }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { stopRecording(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        else listCameras()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listCameras()
        else message("Camera permission required")
    }

    private fun listCameras() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraIds = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.sortedBy {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 100f
        }
        val names = cameraIds.mapIndexed { index, id ->
            val focal = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            val name = if (index == 0) "Wide / ultra-wide" else "Rear camera ${index + 1}"
            "$name  ·  ${focal ?: "?"} mm  (ID $id)"
        }
        runOnUiThread { lenses.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names) }
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
            val id = cameraIds[lenses.selectedItemPosition.coerceIn(cameraIds.indices)]
            cameraId = id
            sensorDegrees = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            clipOrientation = (sensorDegrees - displayDegrees() + 360) % 360
            updatePreview()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val texture = preview.surfaceTexture ?: return
                    texture.setDefaultBufferSize(1280, 720)
                    val display = Surface(texture)
                    val surfaces = listOf(display, encoderSurface!!)
                    device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            session = configured
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                surfaces.forEach { addTarget(it) }
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                            activeBuilder = request
                            applyControls()
                            message("Recording · press volume up to tag")
                        }
                        override fun onConfigureFailed(configured: CameraCaptureSession) { message("Camera configuration failed"); stopRecording() }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); stopRecording() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); message("Camera error $error"); stopRecording() }
            }, handler)
            startButton.text = "■  Stop"
            markButton.isEnabled = true
            lenses.isEnabled = false
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
        runOnUiThread { markButton.isEnabled = false; startButton.text = "●  Start"; lenses.isEnabled = true }
        try { session?.close(); camera?.close() } catch (_: Exception) {}
        session = null; camera = null; activeBuilder = null
        try { drainThread?.join(1500) } catch (_: Exception) {}
        try { encoder?.stop(); encoder?.release(); encoderSurface?.release() } catch (_: Exception) {}
        encoder = null; encoderSurface = null
        val lost = synchronized(guard) { val n = pending.size; pending.clear(); n }
        message(if (lost > 0) "Stopped; $lost pending clips lost. Wait 5 seconds after tagging." else "Stopped · clips in Movies/MatchClip")
    }


    private fun displayDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun updatePreview() {
        if (!::preview.isInitialized || !preview.isAvailable) return
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w <= 0 || h <= 0) return
        val rotation = (sensorDegrees - displayDegrees() + 360) % 360
        val rotated = rotation == 90 || rotation == 270
        val bufferW = if (rotated) 720f else 1280f
        val bufferH = if (rotated) 1280f else 720f
        val scale = maxOf(w / bufferW, h / bufferH)
        val matrix = Matrix()
        val dst = RectF(0f, 0f, bufferW * scale, bufferH * scale)
        dst.offset((w - dst.width()) / 2, (h - dst.height()) / 2)
        matrix.setRectToRect(RectF(0f, 0f, w, h), dst, Matrix.ScaleToFit.FILL)
        // TextureView's transform must rotate the buffer into display coordinates.
        // The previous sign was reversed, giving an upside-down/sideways preview.
        if (rotated) matrix.postRotate(if (rotation == 90) 90f else -90f, w / 2, h / 2)
        preview.setTransform(matrix)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        preview.post { updatePreview() }
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
                if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    builder.set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
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
    override fun onDestroy() { stopRecording(); cameraThread.quitSafely(); super.onDestroy() }
}