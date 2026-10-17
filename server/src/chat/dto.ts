export interface ChatMessageDto {
  role: 'user' | 'assistant';
  content: string;
}

export class ChatStreamDto {
  /** 对话历史（不含本次新提问），可为空数组 */
  messages: ChatMessageDto[] = [];
  /** 本次新提问 */
  question = '';
}
