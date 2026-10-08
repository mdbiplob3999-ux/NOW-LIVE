package com.livestream.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.rtmp.RtmpFromFile
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity(), ConnectChecker, VideoDecoderInterface, AudioDecoderInterface {

    private lateinit var tvFile: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etUrl: EditText
    private lateinit var btnStart: Button
    private lateinit var btnShorts: Button
    private lateinit var btnLong: Button

    private lateinit var rtmp: RtmpFromFile
    private var videoFile: File? = null
    private var videoIsVertical = true
    private var shorts = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvFile = findViewById(R.id.tvFile)
        tvStatus = findViewById(R.id.tvStatus)
        etUrl = findViewById(R.id.etUrl)
        btnStart = findViewById(R.id.btnStart)
        btnShorts = findViewById(R.id.btnShorts)
        btnLong = findViewById(R.id.btnLong)

        rtmp = RtmpFromFile(this, this, this)

        findViewById<Button>(R.id.btnChoose).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
            }, 100)
        }
        btnShorts.setOnClickListener { setFormat(true) }
        btnLong.setOnClickListener { setFormat(false) }
        btnStart.setOnClickListener { if (rtmp.isStreaming) stopLive() else startLive() }
    }

    private fun setFormat(isShorts: Boolean) {
        shorts = isShorts
        btnShorts.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isShorts) "#1F6BFF" else "#1B2340"))
        btnLong.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isShorts) "#1B2340" else "#1F6BFF"))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 100 || resultCode != RESULT_OK) return
        tvFile.text = "Loading video..."
        thread {
            val f = File(cacheDir, "stream_video.mp4")
            contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val r = MediaMetadataRetriever().apply { setDataSource(f.absolutePath) }
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            r.release()
            videoFile = f
            videoIsVertical = h >= w
            runOnUiThread { tvFile.text = "Selected: ${w}x$h (${uri.lastPathSegment})" }
        }
    }

    private fun startLive() {
        val f = videoFile ?: return toast("Select a video first")
        val url = etUrl.text.toString().trim()
        if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) return toast("Link must start with rtmp:// or rtmps://")
        if (shorts && !videoIsVertical) toast("Shorts needs a vertical (9:16) video; yours is horizontal")
        if (!shorts && videoIsVertical) toast("Long Video works best with a horizontal (16:9) video")

        val ok = rtmp.prepareVideo(f.absolutePath, 3_000_000) && rtmp.prepareAudio(f.absolutePath, 128_000)
        if (!ok) return toast("Could not read this video file")
        rtmp.startStream(url)
        btnStart.text = "Stop Live"
        tvStatus.text = "Connecting..."
    }

    private fun stopLive() {
        rtmp.stopStream()
        btnStart.text = "Start Live"
        tvStatus.text = "Stopped."
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
    private fun status(m: String) = runOnUiThread { tvStatus.text = m }

    // ConnectChecker
    override fun onConnectionStarted(url: String) = status("Connecting...")
    override fun onConnectionSuccess() = status("LIVE - streaming now")
    override fun onConnectionFailed(reason: String) {
        runOnUiThread { rtmp.stopStream(); btnStart.text = "Start Live" }
        status("Connection failed: $reason")
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() = status("Disconnected")
    override fun onAuthError() = status("Auth error - check stream key")
    override fun onAuthSuccess() {}

    // Decoder callbacks (video finished)
    override fun onVideoDecoderFinished() { runOnUiThread { stopLive(); tvStatus.text = "Video finished." } }
    override fun onAudioDecoderFinished() {}
}
