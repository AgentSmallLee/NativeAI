package com.example.nativeai

import android.Manifest
import android.content.pm.PackageManager
import java.io.File
import android.view.inputmethod.InputMethodManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var recycler: RecyclerView

    /** 是否自动跟随列表底部：用户上翻看历史时暂停，回到底部或发送新消息时恢复 */
    private var followBottom = true

    private val repository = ChatRepository()
    private var activeCall: okhttp3.Call? = null

    companion object {
        private const val REQUEST_LOCAL_NETWORK = 1001

        /** 打字机刷新节拍（约 30fps） */
        private const val TYPEWRITER_TICK_MS = 33L
    }

    /** 当前流式回答累积的文本（网络侧已到达的全文） */
    private val streamingAnswer = StringBuilder()

    /**
     * 打字机节奏：显示进度与网络到达解耦。
     * 上游可能一次性吐完全文（实测 delta 平均间隔仅 ~8ms），
     * 直接刷新会导致整段答案瞬间砸出，改为按固定节拍逐段放出。
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var streamActive = false
    private var revealedChars = 0
    private val typewriter = object : Runnable {
        override fun run() {
            val total = streamingAnswer.length
            if (revealedChars < total) {
                // 剩余越多追得越快：真流式时几乎实时，一次性到达时平滑铺开
                revealedChars += max(1, (total - revealedChars) / 10)
                // 避免把代理对（emoji 等）从中间切开
                if (revealedChars < total &&
                    Character.isHighSurrogate(streamingAnswer[revealedChars - 1])
                ) {
                    revealedChars++
                }
                adapter.updateLast(streamingAnswer.substring(0, revealedChars))
                scrollToBottom()
            }
            if (streamActive || revealedChars < total) {
                mainHandler.postDelayed(this, TYPEWRITER_TICK_MS)
            }
        }
    }

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
        // 流式输出每 33ms 一次 notifyItemChanged，默认 Change 动画（~200ms）会让
        // 气泡尺寸和滚动持续滞后于文字，直接禁用动画以保证同步
        recycler.itemAnimator = null
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 用户拖动上翻：暂停跟随
                if (dy < 0 && rv.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    followBottom = false
                }
                // 已在底部：恢复跟随
                if (!rv.canScrollVertically(1)) followBottom = true
            }
        })

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

        // 调试：流式每一帧落盘，观察上游真实分段（adb pull 取出查看）
        repository.deltaLogFile = File(
            getExternalFilesDir(null) ?: filesDir,
            "stream_deltas.log",
        )
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
        followBottom = true // 新消息始终回到列表底部
        scrollToBottom()
        startTypewriter()

        activeCall = repository.streamChat(
            question = question,
            history = history,
            onDelta = { delta ->
                runOnUiThread {
                    // 只累积，由打字机按节奏放出
                    streamingAnswer.append(delta)
                }
            },
            onError = { message ->
                runOnUiThread {
                    stopTypewriter()
                    adapter.updateLast(
                        getString(R.string.chat_network_error, message)
                    )
                }
            },
            onDone = {
                runOnUiThread {
                    // 流结束：打字机继续把剩余文本放完，放完自动停
                    streamActive = false
                    if (streamingAnswer.isEmpty()) {
                        stopTypewriter()
                        adapter.updateLast(getString(R.string.chat_network_error, "空响应"))
                    }
                }
            },
        )
    }

    /** 启动打字机节拍（会先清掉上一轮的） */
    private fun startTypewriter() {
        mainHandler.removeCallbacks(typewriter)
        revealedChars = 0
        streamActive = true
        mainHandler.postDelayed(typewriter, TYPEWRITER_TICK_MS)
    }

    private fun stopTypewriter() {
        streamActive = false
        mainHandler.removeCallbacks(typewriter)
    }

    /**
     * 列表贴底。stackFromEnd 在条目增长时不会保持底部对齐，必须每次主动把
     * 最后一条锚定到底部。scrollToPosition 会发起一次以最后一条为锚点的布局，
     * 与 notifyItemChanged 的布局合并到同一帧：文字增长与底部对齐同步完成，
     * 最新文字始终贴在底部，列表连续上移。
     */
    private fun scrollToBottom() {
        if (!followBottom) return
        val last = adapter.itemCount - 1
        if (last >= 0) recycler.scrollToPosition(last)
    }

    /** 点击发送后收起软键盘 */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun onDestroy() {
        // 界面销毁时中止进行中的流式请求
        stopTypewriter()
        activeCall?.cancel()
        super.onDestroy()
    }
}
