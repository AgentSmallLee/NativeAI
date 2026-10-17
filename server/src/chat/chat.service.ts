import { Injectable } from '@nestjs/common';
import { ChatMessageDto } from './dto';

/** 上游（DeepSeek 兼容接口）返回的非 2xx 错误 */
export class UpstreamError extends Error {
  constructor(
    readonly status: number,
    readonly body: string,
  ) {
    super(`LLM 上游返回 HTTP ${status}: ${body.slice(0, 300)}`);
  }
}

export interface UpstreamStream {
  /** 原始 fetch Response，body 为 SSE 流 */
  response: Response;
  /** 中止上游请求（客户端断开时调用） */
  abort: AbortController;
}

/**
 * 对接 DeepSeek API（OpenAI 兼容的 /chat/completions 格式）。
 * 官方地址 https://api.deepseek.com；也兼容其他 OpenAI 格式的中转服务（如火山方舟），
 * 通过 LLM_BASE_URL 切换。
 */
@Injectable()
export class ChatService {
  private readonly baseUrl: string;
  private readonly apiKey: string;
  private readonly model: string;
  private readonly maxTokens: number | undefined;
  private readonly systemPrompt: string | undefined;
  private readonly thinking: 'enabled' | 'disabled';
  private readonly reasoningEffort: string;

  constructor() {
    const apiKey = process.env.LLM_API_KEY;
    if (!apiKey) {
      // 不在构造时抛错，避免没有密钥时整个服务无法启动（健康检查仍可用）
      console.warn(
        '[ChatService] LLM_API_KEY 未设置，调用 /chat/stream 会返回错误',
      );
    }
    this.apiKey = apiKey ?? '';
    // 去掉末尾斜杠，拼接路径时不再处理
    this.baseUrl = (process.env.LLM_BASE_URL ?? 'https://api.deepseek.com')
      .trim()
      .replace(/\/+$/, '');
    this.model = process.env.LLM_MODEL ?? 'deepseek-flash';
    const maxTokens = Number(process.env.LLM_MAX_TOKENS ?? 0);
    this.maxTokens = maxTokens > 0 ? maxTokens : undefined;
    const sys = process.env.LLM_SYSTEM_PROMPT;
    this.systemPrompt = sys?.trim() ? sys : undefined;
    this.thinking =
      (process.env.LLM_THINKING ?? 'enabled') === 'disabled'
        ? 'disabled'
        : 'enabled';
    this.reasoningEffort = process.env.LLM_REASONING_EFFORT ?? 'high';
  }

  /**
   * 发起上游流式请求（stream: true 的 chat/completions）。
   * 返回 fetch Response，由 Controller 解析 SSE 并转发给客户端。
   */
  async createStream(
    history: ChatMessageDto[],
    question: string,
  ): Promise<UpstreamStream> {
    const messages: Array<{ role: string; content: string }> = [];
    if (this.systemPrompt) {
      messages.push({ role: 'system', content: this.systemPrompt });
    }
    for (const m of history) {
      messages.push({ role: m.role, content: m.content });
    }
    messages.push({ role: 'user', content: question });

    const body: Record<string, unknown> = {
      model: this.model,
      messages,
      stream: true,
      thinking: { type: this.thinking },
      reasoning_effort: this.reasoningEffort,
    };
    if (this.maxTokens) {
      body.max_tokens = this.maxTokens;
    }

    const abort = new AbortController();
    const response = await fetch(`${this.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${this.apiKey}`,
      },
      body: JSON.stringify(body),
      signal: abort.signal,
    });

    if (!response.ok) {
      const text = await response.text().catch(() => '');
      throw new UpstreamError(response.status, text);
    }
    if (!response.body) {
      throw new UpstreamError(response.status, '上游返回空 body');
    }
    return { response, abort };
  }
}
