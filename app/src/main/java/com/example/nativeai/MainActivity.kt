package com.example.nativeai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var btnToggleInput: ImageButton
    private lateinit var btnHoldTalk: Button
    private lateinit var btnSend: Button

    /** 是否自动跟随列表底部：用户上翻看历史时暂停，回到底部或发送新消息时恢复 */
    private var followBottom = true

    private val repository = ChatRepository()
    private var activeCall: okhttp3.Call? = null

    // ---- 输入模式 ----
    private enum class InputMode { KEYBOARD, VOICE }
    private var inputMode = InputMode.KEYBOARD

    // ---- 语音相关 ----
    private enum class VoiceState { IDLE, RECORDING, UPLOADING }
    private var voiceState = VoiceState.IDLE
    private lateinit var audioRecorder: AudioRecorder
    private val audioRepository = AudioRepository()
    private var audioCall: okhttp3.Call? = null

    private var recordStartTime = 0L
    private val recordTimer = object : Runnable {
        override fun run() {
            val duration = System.currentTimeMillis() - recordStartTime
            val sec = (duration / 1000).toInt()
            btnHoldTalk.text = "%02d:%02d  录音中…".format(sec / 60, sec % 60)
            if (duration >= AudioRecorder.MAX_DURATION_MS) {
                stopRecordingAndUpload()
            } else {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private val requestRecordPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 权限授权后，用户需要再次按住说话
            Toast.makeText(this, "权限已授予，按住说话", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                R.string.chat_voice_permission_denied,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {

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

        audioRecorder = AudioRecorder(this)

        adapter = ChatAdapter()
        input = findViewById(R.id.edit_input)
        recycler = findViewById(R.id.recycler_chat)
        btnToggleInput = findViewById(R.id.btn_toggle_input)
        btnHoldTalk = findViewById(R.id.btn_hold_talk)
        btnSend = findViewById(R.id.btn_send)
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

        btnSend.setOnClickListener { send() }

        // 切换输入模式（键盘 / 语音）
        btnToggleInput.setOnClickListener { toggleInputMode() }

        // 按住说话：按下开始录音，松开上传
        btnHoldTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 按下：检查权限，开始录音
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        startRecording()
                    } else {
                        requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // 松开或取消：停止录音并上传
                    if (voiceState == VoiceState.RECORDING) {
                        stopRecordingAndUpload()
                    }
                    true
                }
                else -> false
            }
        }

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

    // ======================== 输入模式切换 ========================

    private fun toggleInputMode() {
        inputMode = if (inputMode == InputMode.KEYBOARD) {
            InputMode.VOICE
        } else {
            InputMode.KEYBOARD
        }
        updateInputModeUI()
    }

    private fun updateInputModeUI() {
        when (inputMode) {
            InputMode.KEYBOARD -> {
                // 键盘模式：显示输入框 + 发送按钮，隐藏语音按钮
                btnToggleInput.setImageResource(R.drawable.ic_mic)
                input.visibility = View.VISIBLE
                btnHoldTalk.visibility = View.GONE
                btnSend.visibility = View.VISIBLE
                // 如果之前有识别结果，填入输入框；不做额外处理
            }
            InputMode.VOICE -> {
                // 语音模式：显示按住说话按钮，隐藏输入框 + 发送按钮
                btnToggleInput.setImageResource(R.drawable.ic_keyboard)
                input.visibility = View.GONE
                btnHoldTalk.visibility = View.VISIBLE
                btnSend.visibility = View.GONE
                hideKeyboard()
                // 如果有文字转过来，切回键盘模式才能看到
            }
        }
        // 切模式时重置语音状态
        if (voiceState == VoiceState.RECORDING) {
            audioRecorder.cancel()
            mainHandler.removeCallbacks(recordTimer)
            voiceState = VoiceState.IDLE
        }
        updateVoiceUI()
    }

    // ======================== 语音输入 ========================

    private fun startRecording() {
        try {
            audioRecorder.start()
            voiceState = VoiceState.RECORDING
            recordStartTime = System.currentTimeMillis()
            updateVoiceUI()
            mainHandler.postDelayed(recordTimer, 1000)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.chat_voice_record_error, e.message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stopRecordingAndUpload() {
        mainHandler.removeCallbacks(recordTimer)
        val (file, duration) = try {
            audioRecorder.stop()
        } catch (e: Exception) {
            voiceState = VoiceState.IDLE
            updateVoiceUI()
            Toast.makeText(
                this,
                getString(R.string.chat_voice_record_error, e.message),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // 录音太短，放弃
        if (duration < AudioRecorder.MIN_DURATION_MS) {
            file.delete()
            voiceState = VoiceState.IDLE
            updateVoiceUI()
            Toast.makeText(this, R.string.chat_voice_too_short, Toast.LENGTH_SHORT).show()
            return
        }

        voiceState = VoiceState.IDLE
        updateVoiceUI()

        // 1. 立即插入语音消息到列表（显示播放按钮 + 时长 + 识别中）
        val durationSec = ((duration + 500) / 1000).toInt() // 四舍五入到秒
        val voiceMsg = ChatMessage(
            role = ChatMessage.Role.USER,
            content = "",
            type = ChatMessage.MessageType.VOICE,
            audioPath = file.absolutePath,
            audioDurationSec = durationSec,
            asrStatus = ChatMessage.AsrStatus.LOADING,
        )
        adapter.add(voiceMsg)
        val voiceMsgIndex = adapter.itemCount - 1
        followBottom = true
        scrollToBottom()

        // 追加一条空的助手消息（等待中），等识别成功后再真正发请求
        // 这里先不加助手气泡，等识别成功后再加 + 发起请求
        // （避免识别失败时还多一条空气泡）

        // 2. 后台异步识别
        audioCall = audioRepository.transcribeAudio(
            file = file,
            onSuccess = { text ->
                runOnUiThread {
                    // 更新语音消息：识别成功
                    adapter.updateMessage(voiceMsgIndex) { msg ->
                        msg.content = text
                        msg.asrStatus =
                            if (text.isBlank()) ChatMessage.AsrStatus.FAILED
                            else ChatMessage.AsrStatus.SUCCESS
                    }

                    if (text.isBlank()) {
                        Toast.makeText(
                            this,
                            R.string.chat_voice_empty,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // 识别成功：自动把文字发给 AI 助手
                        sendVoiceAsQuestion(text)
                    }
                }
            },
            onError = { message ->
                runOnUiThread {
                    // 更新语音消息：识别失败
                    adapter.updateMessage(voiceMsgIndex) { msg ->
                        msg.asrStatus = ChatMessage.AsrStatus.FAILED
                    }
                    Toast.makeText(
                        this,
                        getString(R.string.chat_voice_asr_error, message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /**
     * 把语音识别出的文字作为问题发给 AI 助手。
     * 与 send() 类似，但不经过输入框，直接用识别文字发起请求。
     *
     * 调用此函数时，最后一条消息就是刚插入的语音消息；
     * historyForRequest() 返回除最后一条以外的所有消息，即语音之前的对话历史。
     */
    private fun sendVoiceAsQuestion(question: String) {
        val history = adapter.historyForRequest().filter {
            it.content.isNotBlank()
        }.map { ChatMessage(it.role, it.content) }

        // 追加空的助手气泡（等待中）
        streamingAnswer.clear()
        adapter.add(ChatMessage(ChatMessage.Role.ASSISTANT, getString(R.string.chat_waiting)))
        followBottom = true
        scrollToBottom()
        startTypewriter()

        activeCall = repository.streamChat(
            question = question,
            history = history,
            onDelta = { delta ->
                runOnUiThread {
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
                    streamActive = false
                    if (streamingAnswer.isEmpty()) {
                        stopTypewriter()
                        adapter.updateLast(getString(R.string.chat_network_error, "空响应"))
                    }
                }
            },
        )
    }

    /** 根据语音状态更新 UI */
    private fun updateVoiceUI() {
        when (voiceState) {
            VoiceState.IDLE -> {
                btnHoldTalk.text = getString(R.string.chat_voice_hold_to_talk)
                btnHoldTalk.isPressed = false
            }
            VoiceState.RECORDING -> {
                // 录音中：立刻显示 00:00 录音中，后续由计时器每秒更新
                btnHoldTalk.text = "00:00  " + getString(R.string.chat_voice_recording)
                btnHoldTalk.isPressed = true
            }
            VoiceState.UPLOADING -> {
                btnHoldTalk.text = getString(R.string.chat_voice_transcribing)
                btnHoldTalk.isPressed = false
            }
        }
    }

    override fun onDestroy() {
        // 界面销毁时中止进行中的流式请求
        stopTypewriter()
        activeCall?.cancel()
        // 释放语音播放
        adapter.releasePlayer()
        // 清理语音相关
        mainHandler.removeCallbacks(recordTimer)
        audioCall?.cancel()
        audioRecorder.cancel()
        audioRecorder.release()
        super.onDestroy()
    }
}
