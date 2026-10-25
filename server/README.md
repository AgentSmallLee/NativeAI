# NativeAI Server

NestJS 后端：对接大模型 API 与语音识别 API，为 Android 客户端提供 SSE 流式问答和语音转文字服务。API Key 只存在于服务端，客户端不接触。

> 任何 OpenAI 兼容格式的服务（DeepSeek 官方、火山方舟、硅基流动、各类中转）都可通过 `LLM_BASE_URL` / `ASR_BASE_URL` 接入。

## 功能

- **流式聊天**：SSE 增量输出，支持思考过程（reasoning）与正文分离转发
- **语音识别**：上传音频文件，返回识别文字（兼容 OpenAI Whisper API 格式）
- **多模型接入**：通过环境变量切换模型、接口地址、推理参数
- **安全代理**：API Key 仅保存在服务端，客户端通过本服务间接调用

## 接口

### `POST /chat/stream`（SSE 流式）

请求体：

```json
{
  "messages": [
    { "role": "user", "content": "你好" },
    { "role": "assistant", "content": "你好！有什么可以帮你？" }
  ],
  "question": "用一句话介绍你自己"
}
```

- `messages`：之前的对话历史（可为空数组）
- `question`：本次新提问

响应（`text/event-stream`）：

```
data: {"reasoning":"..."}   <- 增量思考过程（模型开启 thinking 时才有，可忽略）
data: {"delta":"你"}        <- 增量正文
data: {"delta":"好"}
...
data: [DONE]                <- 结束
```

出错时：`data: {"error":"错误信息"}`

### `POST /audio/transcribe`（语音识别）

上传音频文件，返回识别出的文字。

- 请求：`multipart/form-data`，字段名 `file`
- 支持格式：mp3、wav、ogg、webm、m4a 等
- 文件大小限制：25 MB

响应：

```json
{ "text": "识别出的文字" }
```

出错时：

```json
{ "error": "错误信息" }
```

示例：

```bash
curl -X POST http://localhost:3000/audio/transcribe \
  -F "file=@/path/to/audio.m4a"
```

### `GET /health`

健康检查，返回 `{"ok":true}`。

## 运行

```bash
cd server
cp .env.example .env    # 填入 LLM_API_KEY（ASR_API_KEY 可选）
npm install
npm run start:dev       # 开发模式，监听 0.0.0.0:3000
```

生产构建：

```bash
npm run build
npm run start:prod
```

## 配置项（.env）

### LLM 配置（统一 `LLM_` 前缀）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `LLM_API_KEY` | API 密钥（**必填**），以 `Bearer` 方式发送 | - |
| `LLM_BASE_URL` | API 地址（DeepSeek / 火山方舟 / 中转服务） | `https://api.deepseek.com` |
| `LLM_MODEL` | 模型 ID | `deepseek-flash` |
| `LLM_MAX_TOKENS` | 单次回答上限（0 = 不限制） | `0` |
| `LLM_SYSTEM_PROMPT` | 系统提示词 | 无 |
| `LLM_THINKING` | 思考模式 `enabled` / `disabled` | `enabled` |
| `LLM_REASONING_EFFORT` | 推理深度 `low` / `medium` / `high` | `high` |

### ASR 语音识别配置（统一 `ASR_` 前缀）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `ASR_API_KEY` | API 密钥（未配置时调用语音识别会报错） | - |
| `ASR_BASE_URL` | API 地址（兼容 Whisper 格式即可） | `https://api.openai.com/v1` |
| `ASR_MODEL` | 模型 ID | `whisper-1` |
| `ASR_LANGUAGE` | 识别语言（`zh` / `en`，留空自动检测） | 自动检测 |

### 服务配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `PORT` | 监听端口 | `3000` |

上游请求体格式（DeepSeek 兼容）：

```json
{
  "model": "deepseek-flash",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "..."}
  ],
  "thinking": {"type": "enabled"},
  "reasoning_effort": "high",
  "stream": true
}
```

## 快速验证

### 聊天接口

```bash
curl -N -X POST http://localhost:3000/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"messages":[],"question":"用一句话介绍杭州"}'
```

### 语音识别接口

```bash
curl -X POST http://localhost:3000/audio/transcribe \
  -F "file=@/path/to/voice.m4a"
```

## 客户端接入

### Android 模拟器

模拟器里 `localhost` 指向模拟器自身，访问宿主机服务用 `http://10.0.2.2:3000`。

### 真机调试

把客户端的 `BASE_URL` 换成电脑的局域网 IP（如 `http://192.168.1.5:3000`），确保手机和电脑在同一 WiFi 下。

### 注意事项

- 思考过程（`reasoning` 事件）与正文（`delta` 事件）分开转发，客户端可只显示正文
- 火山方舟的模型需要在控制台开通后才能调用；模型名与官方不同（如 `deepseek-v4-1-flash-260910`），以方舟模型列表为准
- 硅基流动的语音识别模型推荐使用 `FunAudioLLM/SenseVoiceSmall`，设置 `ASR_BASE_URL=https://api.siliconflow.cn/v1`

## 项目结构

```
src/
├── main.ts              # 入口文件
├── app.module.ts        # 根模块
├── chat/                # 聊天模块
│   ├── chat.controller.ts
│   ├── chat.service.ts
│   ├── chat.module.ts
│   └── dto.ts
└── audio/               # 音频模块
    ├── audio.controller.ts
    ├── asr.service.ts
    └── audio.module.ts
```

## 技术栈

- [NestJS 11](https://nestjs.com/) — 后端框架
- TypeScript — 类型安全
- SSE (Server-Sent Events) — 流式输出
- Multer — 文件上传处理
