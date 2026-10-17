package com.example.nativeai

import android.Manifest
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var recycler: RecyclerView

    private val repository = ChatRepository()
    private var activeCall: okhttp3.Call? = null

    companion object {
        private const val REQUEST_LOCAL_NETWORK = 1001
    }

    /** 当前流式回答累积的文本 */
    private val streamingAnswer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = ChatAdapter()
        input = findViewById(R.id.edit_input)
        recycler = findViewById(R.id.recycler_chat)
        recycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // 内容不足一屏时贴底显示
        }
        recycler.adapter = adapter

        findViewById<View>(R.id.btn_send).setOnClickListener { send() }

        // edge-to-edge：根布局避开状态栏/导航栏；键盘弹出时顶起输入栏，收起时恢复
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                top = bars.top,
                bottom = maxOf(bars.bottom, ime.bottom),
            )
            insets
        }

        requestLocalNetworkPermissionIfNeeded()
    }

    /**
     * Android 16+ (targetSdk 36+) 访问局域网（10.0.2.2 / 电脑 IP）需要运行时权限，
     * 缺失时本地连接会被系统静默拦截：
     * - API 33~36: NEARBY_WIFI_DEVICES
     * - API 37+:   ACCESS_LOCAL_NETWORK（专用权限）
     */
    private fun requestLocalNetworkPermissionIfNeeded() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= 36) {
            wanted += "android.permission.ACCESS_LOCAL_NETWORK"
        }
        // 只请求本设备平台真实存在的权限（请求不存在的权限会立即回调拒绝）
        val toRequest = wanted.filter {
            runCatching { packageManager.getPermissionInfo(it, 0) }.isSuccess &&
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), REQUEST_LOCAL_NETWORK)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCAL_NETWORK &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "未授权局域网访问，无法连接本地服务器", Toast.LENGTH_LONG).show()
        }
    }

    private fun send() {
        val question = input.text.toString().trim()
        if (question.isEmpty()) {
            Toast.makeText(this, R.string.chat_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        input.text.clear()
        hideKeyboard()

        // 发送中的问题：历史 = 当前列表（不含即将添加的两条）
        val history = adapter.historyForRequest().filter {
            // 只回传有内容的消息；正在等待的那条空助手消息排除
            it.content.isNotBlank()
        }.map { ChatMessage(it.role, it.content) }

        // 界面：先显示用户问题，再追加一条空的助手气泡（等待中）
        adapter.add(ChatMessage(ChatMessage.Role.USER, question))
        streamingAnswer.clear()
        adapter.add(ChatMessage(ChatMessage.Role.ASSISTANT, getString(R.string.chat_waiting)))
        scrollToBottom()

        activeCall = repository.streamChat(
            question = question,
            history = history,
            onDelta = { delta ->
                runOnUiThread {
                    streamingAnswer.append(delta)
                    adapter.updateLast(streamingAnswer.toString())
                    scrollToBottom()
                }
            },
            onError = { message ->
                runOnUiThread {
                    adapter.updateLast(
                        getString(R.string.chat_network_error, message)
                    )
                }
            },
            onDone = {
                runOnUiThread {
                    if (streamingAnswer.isEmpty()) {
                        adapter.updateLast(getString(R.string.chat_network_error, "空响应"))
                    }
                }
            },
        )
    }

    private fun scrollToBottom() {
        val position = adapter.itemCount - 1
        if (position >= 0) recycler.smoothScrollToPosition(position)
    }

    /** 点击发送后收起软键盘 */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun onDestroy() {
        // 界面销毁时中止进行中的流式请求
        activeCall?.cancel()
        super.onDestroy()
    }
}
