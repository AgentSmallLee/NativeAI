import { Injectable } from '@nestjs/common';
import { readFileSync } from 'fs';
import { basename } from 'path';

/** ASR 上游返回的非 2xx 错误 */
export class AsrUpstreamError extends Error {
  constructor(
    readonly status: number,
    readonly body: string,
  ) {
    super(`ASR 上游返回 HTTP ${status}: ${body.slice(0, 300)}`);
  }
}

/**
 * 对接 OpenAI Whisper 兼容的语音识别 API（/audio/transcriptions）。
 * 默认 OpenAI 官方；通过 ASR_BASE_URL 可切换到其他兼容服务。
 */
@Injectable()
export class AsrService {
  private readonly baseUrl: string;
  private readonly apiKey: string;
  private readonly model: string;
  private readonly language: string | undefined;

  constructor() {
    const apiKey = process.env.ASR_API_KEY;
    if (!apiKey) {
      console.warn(
        '[AsrService] ASR_API_KEY 未设置，调用 /audio/transcribe 会返回错误',
      );
    }
    this.apiKey = apiKey ?? '';
    this.baseUrl = (process.env.ASR_BASE_URL ?? 'https://api.openai.com/v1')
      .trim()
      .replace(/\/+$/, '');
    this.model = process.env.ASR_MODEL ?? 'whisper-1';
    const lang = process.env.ASR_LANGUAGE?.trim();
    this.language = lang || undefined;
  }

  /**
   * 调用上游语音识别接口，返回识别出的文本。
   *
   * @param filePath 本地音频文件路径
   * @returns 识别出的纯文本
   */
  async transcribe(filePath: string): Promise<string> {
    if (!this.apiKey) {
      throw new Error('ASR_API_KEY 未配置');
    }

    const formData = new FormData();

    // 读取文件内容，包装成 Blob 上传（Node.js FormData 只接受 Blob/File）
    const fileBuffer = readFileSync(filePath);
    const fileName = basename(filePath);
    // 根据扩展名推断 MIME 类型
    const ext = fileName.split('.').pop()?.toLowerCase() ?? 'm4a';
    const mime = ext === 'mp3' ? 'audio/mpeg'
      : ext === 'wav' ? 'audio/wav'
      : ext === 'ogg' ? 'audio/ogg'
      : ext === 'webm' ? 'audio/webm'
      : 'audio/mp4';
    const blob = new Blob([fileBuffer], { type: mime });
    formData.append('file', blob, fileName);
    formData.append('model', this.model);
    formData.append('response_format', 'json');
    if (this.language) {
      formData.append('language', this.language);
    }

    const response = await fetch(`${this.baseUrl}/audio/transcriptions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${this.apiKey}`,
      },
      body: formData as unknown as BodyInit,
    });

    if (!response.ok) {
      const text = await response.text().catch(() => '');
      throw new AsrUpstreamError(response.status, text);
    }

    const data = (await response.json()) as { text?: string };
    return data?.text ?? '';
  }
}
