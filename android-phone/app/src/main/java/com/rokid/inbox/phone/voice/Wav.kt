package com.rokid.inbox.phone.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM 16-bit LE mono -> WAV container. Ported from even-inbox `voice.ts`. */
object Wav {
    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeInt(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun writeShort(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())

        writeAscii("RIFF")
        writeInt(36 + pcm.size)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeInt(16)
        writeShort(1) // PCM
        writeShort(1) // mono
        writeInt(sampleRate)
        writeInt(sampleRate * 2) // byte rate (mono, 16-bit)
        writeShort(2) // block align
        writeShort(16) // bits per sample
        writeAscii("data")
        writeInt(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
