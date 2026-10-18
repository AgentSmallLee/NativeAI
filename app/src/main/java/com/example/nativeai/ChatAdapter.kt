package com.example.nativeai

import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException

/**
 * 问答列表适配器：支持文本消息和语音消息，各分用户/助手两侧。
 *
 * ViewType:
 *   0 = 用户文本
 *   1 = 助手文本
 *   2 = 用户语音
 *   3 = 助手语音
 */
class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ChatMessage>()

    /** 当前正在播放的位置，-1 表示没有在播放 */
    private var playingPosition = -1

    /** 语音播放器（全局共用一个，避免多条同时播放） */
    private var mediaPlayer: MediaPlayer? = null

    // ---- 公开方法 ----

    fun add(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    /** 更新指定位置消息的内容（语音识别结果用） */
    fun updateMessage(index: Int, updater: (ChatMessage) -> Unit) {
        if (index < 0 || index >= items.size) return
        updater(items[index])
        notifyItemChanged(index)
    }

    /** 更新最后一条助手消息的内容（流式增量累积后调用） */
    fun updateLast(content: String) {
        if (items.isEmpty()) return
        val index = items.size - 1
        items[index].content = content
        notifyItemChanged(index)
    }

    /**
     * 除最后一条外的历史（用于请求体回传）。
     * 语音消息只有识别成功的才回传（文字内容）。
     */
    fun historyForRequest(): List<ChatMessage> {
        if (items.isEmpty()) return emptyList()
        return items.subList(0, items.size - 1).mapNotNull { msg ->
            when (msg.type) {
                ChatMessage.MessageType.TEXT -> msg
                ChatMessage.MessageType.VOICE -> {
                    // 语音消息：识别成功的才作为文本回传
                    if (msg.asrStatus == ChatMessage.AsrStatus.SUCCESS && msg.content.isNotBlank()) {
                        ChatMessage(msg.role, msg.content)
                    } else null
                }
            }
        }
    }

    fun lastMessage(): ChatMessage? = items.lastOrNull()

    /** 释放播放器（Activity onDestroy 时调用） */
    fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        if (playingPosition >= 0) {
            val oldPos = playingPosition
            playingPosition = -1
            notifyItemChanged(oldPos)
        }
    }

    // ---- Adapter 基础方法 ----

    override fun getItemViewType(position: Int): Int {
        val msg = items[position]
        return when {
            msg.role == ChatMessage.Role.USER && msg.type == ChatMessage.MessageType.TEXT -> TYPE_USER_TEXT
            msg.role == ChatMessage.Role.ASSISTANT && msg.type == ChatMessage.MessageType.TEXT -> TYPE_ASSISTANT_TEXT
            msg.role == ChatMessage.Role.USER && msg.type == ChatMessage.MessageType.VOICE -> TYPE_USER_VOICE
            else -> TYPE_ASSISTANT_VOICE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER_TEXT -> {
                val view = inflater.inflate(R.layout.item_message_user, parent, false)
                TextHolder(view)
            }
            TYPE_ASSISTANT_TEXT -> {
                val view = inflater.inflate(R.layout.item_message_assistant, parent, false)
                TextHolder(view)
            }
            TYPE_USER_VOICE -> {
                val view = inflater.inflate(R.layout.item_message_user_voice, parent, false)
                VoiceHolder(view)
            }
            else -> {
                // 助手语音（暂用用户语音布局占位，后续可单独做）
                val view = inflater.inflate(R.layout.item_message_assistant, parent, false)
                TextHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = items[position]
        when (holder) {
            is TextHolder -> holder.bind(msg)
            is VoiceHolder -> holder.bind(msg, position == playingPosition)
        }
    }

    override fun getItemCount(): Int = items.size

    // ---- ViewHolder ----

    /** 纯文本消息 Holder */
    class TextHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text = view.findViewById<TextView>(R.id.text_message)

        fun bind(message: ChatMessage) {
            text.text = message.content
        }
    }

    /** 语音消息 Holder */
    inner class VoiceHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val playBtn = view.findViewById<ImageView>(R.id.btn_play)
        private val durationText = view.findViewById<TextView>(R.id.text_duration)
        private val asrText = view.findViewById<TextView>(R.id.text_asr)

        fun bind(message: ChatMessage, isPlaying: Boolean) {
            // 时长
            durationText.text = "${message.audioDurationSec}\""

            // 播放图标
            playBtn.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            // 识别文字
            when (message.asrStatus) {
                ChatMessage.AsrStatus.LOADING -> {
                    asrText.visibility = View.VISIBLE
                    asrText.text = "识别中…"
                }
                ChatMessage.AsrStatus.SUCCESS -> {
                    asrText.visibility = View.VISIBLE
                    asrText.text = message.content.ifBlank { "（无内容）" }
                }
                ChatMessage.AsrStatus.FAILED -> {
                    asrText.visibility = View.VISIBLE
                    asrText.text = "识别失败"
                }
                ChatMessage.AsrStatus.NONE -> {
                    asrText.visibility = View.GONE
                }
            }

            // 点击气泡播放/暂停
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                togglePlay(pos)
            }
        }
    }

    // ---- 播放控制 ----

    private fun togglePlay(position: Int) {
        val msg = items.getOrNull(position) ?: return
        val audioPath = msg.audioPath ?: return

        if (playingPosition == position) {
            // 正在播放这条 → 暂停
            mediaPlayer?.pause()
            mediaPlayer = null
            val oldPos = playingPosition
            playingPosition = -1
            notifyItemChanged(oldPos)
            return
        }

        // 停止之前播放的
        val oldPos = playingPosition
        mediaPlayer?.release()
        mediaPlayer = null
        playingPosition = -1
        if (oldPos >= 0) notifyItemChanged(oldPos)

        // 开始播放新的
        try {
            val mp = MediaPlayer()
            mp.setDataSource(audioPath)
            mp.setOnCompletionListener {
                mp.release()
                mediaPlayer = null
                val finishedPos = playingPosition
                playingPosition = -1
                if (finishedPos >= 0) notifyItemChanged(finishedPos)
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            playingPosition = position
            notifyItemChanged(position)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val TYPE_USER_TEXT = 0
        private const val TYPE_ASSISTANT_TEXT = 1
        private const val TYPE_USER_VOICE = 2
        private const val TYPE_ASSISTANT_VOICE = 3
    }
}
