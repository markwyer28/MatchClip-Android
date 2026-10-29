package com.matchclip.android

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
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
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val controls = LinearLayout(this)
        preview = TextureView(this)
        lenses = Spinner(this)
        startButton = Button(this).apply { text = "START" }
        markButton = Button(this).apply { text = "SAVE HIGHLIGHT"; isEnabled = false }
        status = TextView(this).apply { text = "15s before + 5s after · MP4"; textSize = 17f }
        controls.addView(lenses, LinearLayout.LayoutParams(0, 65, 1f))
        controls.addView(startButton)
        controls.addView(markButton)
        layout.addView(controls)
        layout.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(status)
        setContentView(layout)
        startButton.setOnClickListener { if (running.get()) stopRecording() else startRecording() }
        markButton.setOnClickListener { markHighlight() }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { listCameras() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
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
            "${if (index == 0) "Widest rear" else "Rear ${index + 1}"} · ${focal ?: "?"}mm · $id"
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
                            }.build()
                            configured.setRepeatingRequest(request, null, handler)
                            message("Recording · press volume up to tag")
                        }
                        override fun onConfigureFailed(configured: CameraCaptureSession) { message("Camera configuration failed"); stopRecording() }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); stopRecording() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); message("Camera error $error"); stopRecording() }
            }, handler)
            startButton.text = "STOP"
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
        runOnUiThread { markButton.isEnabled = false; startButton.text = "START"; lenses.isEnabled = true }
        try { session?.close(); camera?.close() } catch (_: Exception) {}
        session = null; camera = null
        try { drainThread?.join(1500) } catch (_: Exception) {}
        try { encoder?.stop(); encoder?.release(); encoderSurface?.release() } catch (_: Exception) {}
        encoder = null; encoderSurface = null
        val lost = synchronized(guard) { val n = pending.size; pending.clear(); n }
        message(if (lost > 0) "Stopped; $lost pending clips lost. Wait 5 seconds after tagging." else "Stopped · clips in Movies/MatchClip")
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