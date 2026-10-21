package com.mthtamim.phonelink

import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/** Records a short voice clip (up to 60 s) and sends it to the peer. */
class VoiceActivity : LocalizedActivity() {

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var recording = false
    private var startedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvVoiceStatus: TextView
    private lateinit var btnRecord: Button

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            tvVoiceStatus.text = getString(R.string.voice_recording, secs)
            if (secs >= 60) { stopAndSend(); return }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnRecord.setOnClickListener {
            if (recording) stopAndSend() else startRecording()
        }
        findViewById<Button>(R.id.btnVoiceClose).setOnClickListener { finish() }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 9)
            return
        }
        try {
            val file = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            outFile = file
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(22050)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recording = true
            startedAt = System.currentTimeMillis()
            btnRecord.text = getString(R.string.btn_voice_stop_send)
            handler.post(ticker)
        } catch (e: Exception) {
            toast(getString(R.string.toast_voice_failed))
        }
    }

    private fun stopAndSend() {
        if (!recording) return
        recording = false
        handler.removeCallbacks(ticker)
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        btnRecord.text = getString(R.string.btn_voice_record)
        tvVoiceStatus.text = getString(R.string.file_uploading)
        val file = outFile ?: return
        thread {
            var ok = false
            try {
                val bytes = file.readBytes()
                ok = Relay.postFile(applicationContext, "voice.m4a", bytes, "audio/mp4")
            } catch (_: Exception) { }
            try { file.delete() } catch (_: Exception) { }
            Handler(Looper.getMainLooper()).post {
                toast(getString(if (ok) R.string.file_sent else R.string.file_failed))
                if (ok) finish() else tvVoiceStatus.text = getString(R.string.voice_ready)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 9 && results.isNotEmpty() &&
            results[0] == PackageManager.PERMISSION_GRANTED) startRecording()
        else if (requestCode == 9) toast(getString(R.string.toast_mic_denied))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        try { recorder?.release() } catch (_: Exception) { }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
