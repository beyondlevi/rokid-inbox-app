package com.rokid.inbox.glasses.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures PCM 16 kHz mono 16-bit from the glasses microphone and streams chunks
 * to a callback. The phone assembles the WAV and runs Whisper.
 */
class MicCapture(private val onChunk: (ByteArray) -> Unit) {
    private var record: AudioRecord? = null
    @Volatile private var recording = false
    private var thread: Thread? = null

    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord unavailable (minBuf=$minBuf)")
            return false
        }
        val bufferSize = maxOf(minBuf, CHUNK_BYTES * 2)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord create failed", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized")
            runCatching { rec.release() }
            return false
        }
        record = rec
        recording = true
        rec.startRecording()
        thread = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) onChunk(buf.copyOf(n))
            }
        }, "InboxMicCapture").also { it.start() }
        return true
    }

    fun stop() {
        recording = false
        thread?.interrupt()
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    private companion object {
        private const val TAG = "InboxMicCapture"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES = 3200 // ~100 ms at 16 kHz mono 16-bit
    }
}
