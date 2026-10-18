package com.example.nativeai

/**
 * 聊天消息（用于界面展示与历史回传）。
 *
 * 支持两种消息类型：
 * - TEXT  : 纯文本消息，content 即文字内容
 * - VOICE : 语音消息，音频文件在本地，content 为识别结果（可能为空）
 */
data class ChatMessage(
    val role: Role,
    var content: String,
    val type: MessageType = MessageType.TEXT,
    /** 语音文件本地路径（仅语音消息有） */
    val audioPath: String? = null,
    /** 语音时长（秒，仅语音消息有） */
    val audioDurationSec: Int = 0,
    /** 语音识别状态（仅语音消息有） */
    var asrStatus: AsrStatus = AsrStatus.NONE,
) {
    enum class Role { USER, ASSISTANT }
    enum class MessageType { TEXT, VOICE }
    enum class AsrStatus {
        NONE,    // 无（非语音消息或未开始识别）
        LOADING, // 识别中
        SUCCESS, // 识别成功
        FAILED,  // 识别失败
    }

    /** 回传后端时的 role 字符串 */
    val apiRole: String get() = if (role == Role.USER) "user" else "assistant"
}
