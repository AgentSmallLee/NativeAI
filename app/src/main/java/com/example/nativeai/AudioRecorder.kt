package com.example.nativeai

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 录音封装：基于 MediaRecorder，输出 AAC 编码的 m4a 文件。
 *
 * 用法：
 *   val file = recorder.start()  // 开始录音，返回输出文件
 *   recorder.stop()              // 停止录音
 *   recorder.release()           // 释放资源（Activity onDestroy 时调用）
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs = 0L

    /** 当前是否正在录音 */
    val isRecording: Boolean
        get() = recorder != null

    /**
     * 开始录音。
     *
     * @return 输出文件（录音结束后才有有效内容）
     * @throws IllegalStateException 已经在录音时抛异常
     * @throws RuntimeException 初始化失败（如麦克风被占用）
     */
    fun start(): File {
        check(recorder == null) { "录音已在进行中" }

        val outDir = File(context.cacheDir, "recordings").apply { mkdirs() }
        val file = File(outDir, "record_${System.currentTimeMillis()}.m4a")

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(file.absolutePath)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16000)   // ASR 推荐采样率
            mr.setAudioChannels(1)           // 单声道
            mr.setAudioEncodingBitRate(64000) // 64 kbps
            mr.prepare()
            mr.start()
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败", e)
            try { mr.release() } catch (_: Exception) {}
            throw e
        }

        recorder = mr
        outputFile = file
        startTimeMs = System.currentTimeMillis()
        return file
    }

    /**
     * 停止录音。
     *
     * @return 录音文件，以及录音时长（毫秒）
     */
    fun stop(): Pair<File, Long> {
        val mr = recorder ?: throw IllegalStateException("未在录音")
        val file = outputFile ?: throw IllegalStateException("没有输出文件")
        val duration = System.currentTimeMillis() - startTimeMs

        try {
            mr.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止录音异常", e)
        }
        mr.release()
        recorder = null

        return file to duration
    }

    /**
     * 取消录音：停止并删除文件。
     */
    fun cancel() {
        if (recorder == null) return
        try {
            stop()
        } catch (_: Exception) {}
        outputFile?.delete()
        outputFile = null
    }

    /** 释放所有资源（Activity 销毁时调用） */
    fun release() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        outputFile = null
    }

    companion object {
        private const val TAG = "AudioRecorder"

        /** 最短录音时长（毫秒），短于此时间视为无效录音 */
        const val MIN_DURATION_MS = 1000L

        /** 最长录音时长（毫秒），超过自动停止 */
        const val MAX_DURATION_MS = 60_000L
    }
}
