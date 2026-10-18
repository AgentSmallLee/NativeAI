package com.example.nativeai

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 聊天仓库：调用后端 /chat/stream 接口，解析 SSE 流式响应。
 *
 * SSE 帧协议（见 server/README.md）：
 *   data: {"delta":"增量文本"}
 *   data: {"error":"错误信息"}
 *   data: [DONE]
 */
class ChatRepository {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 长连接不能有读超时
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 流式问答。
     *
     * @param question 本次新提问
     * @param history 之前的对话历史（不含本次提问）
     * @return OkHttp Call，界面销毁时调用 cancel() 中止
     */
    fun streamChat(
        question: String,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit,
    ): Call {
        val body = JSONObject().apply {
            put("messages", JSONArray().apply {
                history.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.apiRole)
                        put("content", msg.content)
                    })
                }
            })
            put("question", question)
        }.toString()

        val request = Request.Builder()
            .url("$BASE_URL/chat/stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) onError(e.message ?: "网络错误")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onError("HTTP ${resp.code}")
                        return
                    }
                    // 通过source获取响应体的流式数据源
                    val source = resp.body?.source() ?: run {
                        onError("空响应")
                        return
                    }
                    try {
                        var frame = 0
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            when {
                                payload == "[DONE]" -> {
                                    onDone(); return
                                }

                                payload.isEmpty() -> Unit
                                else -> {
                                    val obj = JSONObject(payload)
                                    frame++
                                    when {
                                        obj.has("delta") -> {
                                            val delta = obj.getString("delta")
                                            onDelta(delta)
                                        }

                                        obj.has("error") -> {
                                            onError(obj.getString("error"))
                                            return
                                        }
                                    }
                                }
                            }
                        }
                        // 流意外中断（没收到 [DONE]）
                        onDone()
                    } catch (e: IOException) {
                        if (!call.isCanceled()) onError(e.message ?: "连接中断")
                    }
                }
            }
        })
        return call
    }

    companion object {
        private const val TAG = "ChatRepository"

        /** 模拟器访问宿主机的专用别名 */
        internal const val HOST_EMULATOR = "10.0.2.2"

        /** 电脑的局域网 IP（真机与电脑需连同一 Wi-Fi；IP 变了改这里） */
        internal const val HOST_COMPUTER = "192.168.1.25"

        internal const val PORT = 3000

        internal val isEmulator: Boolean
            get() = android.os.Build.FINGERPRINT.contains("generic", true) ||
                    android.os.Build.MODEL.startsWith("Emulator", true) ||
                    android.os.Build.PRODUCT.contains("sdk", true)

        /**
         * 后端地址：模拟器自动用 10.0.2.2，真机用电脑局域网 IP。
         */
        internal val BASE_URL: String
            get() = "http://${if (isEmulator) HOST_EMULATOR else HOST_COMPUTER}:$PORT"
    }
}
