package com.example.nativeai

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 音频上传 + 语音识别仓库。
 *
 * 调用后端 POST /audio/transcribe 接口，返回识别出的文字。
 *
 * 响应格式：
 *   { "text": "识别结果" }  // 成功
 *   { "error": "错误信息" } // 失败
 */
class AudioRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)  // ASR 处理需要一些时间
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 上传音频文件进行语音识别。
     *
     * @param file 音频文件
     * @param onSuccess 成功回调，参数为识别出的文字
     * @param onError 失败回调，参数为错误信息
     * @return OkHttp Call，可用于取消
     */
    fun transcribeAudio(
        file: File,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ): Call {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/mp4".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("${ChatRepository.BASE_URL}/audio/transcribe")
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) onError(e.message ?: "网络错误")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        // 尝试解析服务端返回的 error 字段
                        val error = try {
                            JSONObject(body).optString("error")
                        } catch (_: Exception) { null }
                        onError(error ?: "HTTP ${resp.code}")
                        return
                    }
                    try {
                        val obj = JSONObject(body)
                        if (obj.has("error")) {
                            onError(obj.getString("error"))
                        } else if (obj.has("text")) {
                            onSuccess(obj.getString("text"))
                        } else {
                            onError("响应格式异常")
                        }
                    } catch (e: Exception) {
                        onError("响应解析失败：${e.message}")
                    }
                }
            }
        })
        return call
    }
}
