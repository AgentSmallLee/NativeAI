import { Module } from '@nestjs/common';
import { AudioController } from './audio.controller';
import { AsrService } from './asr.service';

@Module({
  controllers: [AudioController],
  providers: [AsrService],
})
export class AudioModule {}
