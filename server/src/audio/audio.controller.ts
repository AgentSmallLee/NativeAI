import {
  BadRequestException,
  Controller,
  HttpCode,
  Post,
  UploadedFile,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { diskStorage } from 'multer';
import { tmpdir } from 'os';
import { unlinkSync } from 'fs';
import { join } from 'path';
import { randomUUID } from 'crypto';
import { AsrService, AsrUpstreamError } from './asr.service';

// 文件大小限制：25MB
const MAX_FILE_SIZE = 25 * 1024 * 1024;

@Controller('audio')
export class AudioController {
  constructor(private readonly asrService: AsrService) {}

  /**
   * 语音识别接口：上传音频文件，返回识别出的文字。
   *
   * 请求：POST /audio/transcribe  (multipart/form-data, field: file)
   * 响应：{ "text": "识别出的文字" }  或  { "error": "错误信息" }
   */
  @Post('transcribe')
  @HttpCode(200)
  @UseInterceptors(
    FileInterceptor('file', {
      storage: diskStorage({
        destination: tmpdir(),
        filename: (_req, _file, cb) => {
          // 用 UUID 命名，避免文件名冲突和路径遍历
          cb(null, `nativeai-asr-${randomUUID()}.tmp`);
        },
      }),
      limits: { fileSize: MAX_FILE_SIZE, files: 1 },
    }),
  )
  async transcribe(@UploadedFile() file: Express.Multer.File) {
    if (!file) {
      throw new BadRequestException('缺少 file 字段');
    }

    const filePath = join(tmpdir(), file.filename);

    try {
      const text = await this.asrService.transcribe(filePath);
      if (!text.trim()) {
        return { error: '未识别到语音内容' };
      }
      return { text };
    } catch (err: unknown) {
         console.log(err)
      const message =
        err instanceof AsrUpstreamError
          ? `ASR 上游错误 (HTTP ${err.status})`
          : err instanceof Error
            ? err.message
            : '语音识别失败';

      return { error: message };
    } finally {
      // 无论成功失败，清理临时文件
      try { unlinkSync(filePath); } catch { /* 已被上游清理或不存在，忽略 */ }
    }
  }
}
