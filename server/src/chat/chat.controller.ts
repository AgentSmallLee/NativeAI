import { Body, Controller, Get, HttpCode, Post, Res } from '@nestjs/common';
import type { Response } from 'express';
import { ChatService, UpstreamError } from './chat.service';
import { ChatStreamDto } from './dto';

@Controller()
export class ChatController {
  constructor(private readonly chatService: ChatService) {}

  @Get('health')
  health() {
    return { ok: true };
  }

  /**
   * 流式问答接口（SSE）。
   *
   * 请求：POST /chat/stream
   *   { "messages": [{ "role": "user", "content": "..." }, ...], "question": "新问题" }
   *
   * 响应：text/event-stream
   *   data: {"delta":"你"}           <- 增量正文
   *   data: {"reasoning":"..."}      <- 增量思考过程（开启 thinking 时才有）
   *   data: {"error":"..."}          <- 出错
   *   data: [DONE]                   <- 结束
   */
  @Post('chat/stream')
  @HttpCode(200) // SSE 接口返回 200（Nest 对 POST 默认 201）
  async stream(@Body() dto: ChatStreamDto, @Res() res: Response) {
    const history = Array.isArray(dto?.messages) ? dto.messages : [];
    const question = (dto?.question ?? '').trim();

    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.setHeader('X-Accel-Buffering', 'no'); // nginx 等反代不缓冲
    res.flushHeaders();

    if (!question) {
      this.send(res, { error: 'question 不能为空' });
      res.end();
      return;
    }

    let upstream: Awaited<ReturnType<ChatService['createStream']>> | null =
      null;

    // 客户端断开时中止上游请求，避免浪费 token
    res.on('close', () => upstream?.abort.abort());

    try {
      upstream = await this.chatService.createStream(history, question);

      const write = this.writer(res);
      const reader = (
        upstream.response.body as ReadableStream<Uint8Array>
      ).getReader();
      const decoder = new TextDecoder();

      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        // SSE 事件以 \n\n 分隔；按行切，末行可能不完整，留到下一轮
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
            console.log(line)
          if (!line.startsWith('data:')) continue;
          const payload = line.slice(5).trim();
          if (!payload || payload === '[DONE]') continue; // 上游结束标记，由我们统一补 [DONE]

          try {
            const chunk = JSON.parse(payload);
            const delta = chunk?.choices?.[0]?.delta;
            if (!delta) continue;
            // 思考过程（deepseek-reasoner / thinking 模式）
            const reasoning = delta.reasoning_content ?? delta.reasoning;
            if (typeof reasoning === 'string' && reasoning) {
              write({ reasoning });
            }
            // 正文
            if (typeof delta.content === 'string' && delta.content) {
              write({ delta: delta.content });
            }
          } catch {
            // 忽略无法解析的帧
          }
        }
      }
      res.write('data: [DONE]\n\n');
    } catch (err: unknown) {
      const message =
        err instanceof UpstreamError
          ? `LLM 上游错误 (HTTP ${err.status})`
          : err instanceof Error
            ? err.message
            : 'LLM 调用失败';
      this.send(res, { error: message });
    } finally {
      res.end();
    }
  }

  /** 写一帧 SSE；客户端已断开时 res.write 会抛错，忽略即可 */
  private writer(res: Response) {
    return (data: Record<string, string>) => {
      try {
        res.write(`data: ${JSON.stringify(data)}\n\n`);
      } catch {
        /* client disconnected */
      }
    };
  }

  private send(res: Response, data: Record<string, string>) {
    try {
      res.write(`data: ${JSON.stringify(data)}\n\n`);
    } catch {
      /* client disconnected */
    }
  }
}
