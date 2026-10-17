package com.example.nativeai

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * 问答列表适配器：用户消息靠右、助手消息靠左，两种 item 布局。
 * 助手消息随流式回调不断刷新同一条消息（notifyItemChanged）。
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {

    private val items = mutableListOf<ChatMessage>()

    fun add(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    /** 更新最后一条助手消息的内容（流式增量累积后调用） */
    fun updateLast(content: String) {
        if (items.isEmpty()) return
        val index = items.size - 1
        items[index].content = content
        notifyItemChanged(index)
    }

    /** 除最后一条外的历史（用于请求体回传） */
    fun historyForRequest(): List<ChatMessage> =
        if (items.isEmpty()) emptyList() else items.subList(0, items.size - 1)

    fun lastMessage(): ChatMessage? = items.lastOrNull()

    override fun getItemViewType(position: Int): Int =
        if (items[position].role == ChatMessage.Role.USER) TYPE_USER else TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == TYPE_USER) R.layout.item_message_user else R.layout.item_message_assistant
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val text = view.findViewById<android.widget.TextView>(R.id.text_message)

        fun bind(message: ChatMessage) {
            text.text = message.content
        }
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
    }
}
