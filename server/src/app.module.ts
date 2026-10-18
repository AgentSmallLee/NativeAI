import { Module } from '@nestjs/common';
import { ChatModule } from './chat/chat.module';
import { AudioModule } from './audio/audio.module';

@Module({
  imports: [ChatModule, AudioModule],
})
export class AppModule {}
