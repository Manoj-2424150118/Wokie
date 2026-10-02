package com.wokie.message.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Log
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
class VoiceManager(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    init {
        val maxJitter = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxJitter * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    fun startRecording(onVoiceFrame: (ByteArray) -> Unit) {
        if (isRecording) return
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                isRecording = true
                thread {
                    val buffer = ByteArray(bufferSize)
                    while (isRecording) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            val frame = buffer.copyOfRange(0, read)
                            onVoiceFrame(frame)
                        }
                    }
                }
            } else {
                Log.e("VoiceManager", "AudioRecord initialization failed")
            }
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error starting recording", e)
        }
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun playFrame(frame: ByteArray) {
        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
             audioTrack?.write(frame, 0, frame.size)
        }
    }
    
    fun release() {
        stopRecording()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
