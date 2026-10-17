package com.example.nativeai

/** 聊天消息（用于界面展示与历史回传） */
data class ChatMessage(
    val role: Role,
    var content: String,
) {
    enum class Role { USER, ASSISTANT }

    /** 回传后端时的 role 字符串 */
    val apiRole: String get() = if (role == Role.USER) "user" else "assistant"
}
