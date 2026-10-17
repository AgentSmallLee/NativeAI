import 'dotenv/config';
import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);

  // 允许局域网内的 Android 客户端跨域访问
  app.enableCors({ origin: '*' });

  const port = Number(process.env.PORT ?? 3000);
  await app.listen(port, '0.0.0.0');
  console.log(`[NativeAI server] listening on http://0.0.0.0:${port}`);
}
bootstrap();
