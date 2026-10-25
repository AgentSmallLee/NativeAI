# NativeAI · 端侧 AI 聊天应用

一个基于 **Android（Kotlin）+ NestJS 后端** 的 AI 对话应用，支持**流式文字聊天**与**语音输入（ASR）**。
后端代理 LLM / ASR API，API Key 仅存于服务端，客户端不接触密钥。

> 应用名：`端侧AI`（包名 `com.example.nativeai`）
> 默认对接 DeepSeek 大模型 + OpenAI Whisper 格式语音识别，可通过环境变量切换任意 OpenAI 兼容服务（火山方舟、硅基流动等）。

---

## ✨ 功能

- **流式文字对话**：SSE 增量输出 + 打字机效果，思考过程（reasoning）与正文分离
- **语音输入**：按住说话 → 自动上传识别 → 识别文字直接发给 AI
- **语音消息回放**：用户侧语音气泡可播放/暂停，显示录音时长
- **多轮对话**：自动回传历史消息，支持上翻浏览
- **Edge-to-Edge**：适配 Android 15+ 边到边显示，键盘弹出自动顶起输入栏
- **多模型接入**：一行配置切换 DeepSeek / 火山方舟 / 硅基流动 等 OpenAI 兼容服务
- **安全代理**：API Key 仅保存在后端，客户端通过本服务间接调用

---

## 🏗️ 项目结构

```
NativeAI/
├── app/                     # Android 客户端（Kotlin）
│   └── src/main/java/com/example/nativeai/
│       ├── MainActivity.kt       # 主界面：聊天列表 + 输入栏 + 语音
│       ├── ChatRepository.kt     # 聊天仓库：SSE 流式请求与解析
│       ├── AudioRepository.kt    # 音频仓库：语音识别上传
│       ├── AudioRecorder.kt      # 录音封装（MediaRecorder）
│       ├── ChatAdapter.kt        # RecyclerView 适配器（文字/语音气泡）
│       └── ChatMessage.kt        # 消息数据模型
├── server/                  # NestJS 后端（TypeScript）
│   └── src/
│       ├── chat/                 # 聊天模块：流式问答代理
│       ├── audio/                # 音频模块：语音识别代理
│       ├── app.module.ts
│       └── main.ts
├── docs/                    # 开发文档
│   ├── 客户端接入大模型指南.md
│   ├── Android15-EdgeToEdge适配指南.md
│   ├── 接入MNN步骤.txt
│   ├── 流式输出步骤.txt
│   ├── 实现录音上传识别成文字步骤.txt
│   └── ...
└── gradle/                  # Gradle 版本目录
```

---

## 🚀 快速开始

### 1. 启动后端服务

```bash
cd server
cp .env.example .env          # 填入 LLM_API_KEY（ASR_API_KEY 可选）
npm install
npm run start:dev             # 监听 0.0.0.0:3000
```

健康检查：

```bash
curl http://localhost:3000/health
# {"ok":true}
```

> 后端详细文档见 [server/README.md](server/README.md)。

### 2. 运行 Android 客户端

使用 **Android Studio** 打开项目根目录，等待 Gradle 同步完成后运行。

#### 地址配置

客户端在 `ChatRepository.kt` 中自动判断运行环境：

| 环境 | 后端地址 | 说明 |
|------|---------|------|
| 模拟器 | `http://10.0.2.2:3000` | 自动检测，无需修改 |
| 真机 | `http://192.168.1.25:3000` | 修改 `HOST_COMPUTER` 为电脑局域网 IP，手机与电脑连同一 Wi-Fi |

```kotlin
// app/src/main/java/com/example/nativeai/ChatRepository.kt
internal const val HOST_COMPUTER = "192.168.1.25"  // ← 改成你的电脑 IP
```

#### 权限

- **录音权限**：首次使用语音输入时自动申请
- **本地网络权限**（Android 16+ / targetSdk 37+）：真机调试访问局域网后端需要
  `ACCESS_LOCAL_NETWORK` 权限，可通过 adb 快速授权：

  ```bash
  adb shell pm grant com.example.nativeai android.permission.ACCESS_LOCAL_NETWORK
  ```

---

## 📡 接口速览

### 流式聊天 `POST /chat/stream`

```bash
curl -N -X POST http://localhost:3000/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"messages":[],"question":"用一句话介绍杭州"}'
```

SSE 响应：

```
data: {"delta":"你"}
data: {"delta":"好"}
...
data: [DONE]
```

### 语音识别 `POST /audio/transcribe`

```bash
curl -X POST http://localhost:3000/audio/transcribe \
  -F "file=@/path/to/voice.m4a"
```

响应：`{"text":"识别出的文字"}`

### 健康检查 `GET /health`

响应：`{"ok":true}`

---

## ⚙️ 配置项（server/.env）

### LLM 配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `LLM_API_KEY` | API 密钥（**必填**） | - |
| `LLM_BASE_URL` | API 地址 | `https://api.deepseek.com` |
| `LLM_MODEL` | 模型 ID | `deepseek-flash` |
| `LLM_MAX_TOKENS` | 单次回答上限（0 = 不限制） | `0` |
| `LLM_SYSTEM_PROMPT` | 系统提示词 | 无 |
| `LLM_THINKING` | 思考模式 `enabled` / `disabled` | `enabled` |
| `LLM_REASONING_EFFORT` | 推理深度 | `high` |

### ASR 语音识别配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `ASR_API_KEY` | API 密钥（未配置时调用语音识别会报错） | - |
| `ASR_BASE_URL` | API 地址（兼容 Whisper 格式即可） | `https://api.openai.com/v1` |
| `ASR_MODEL` | 模型 ID | `whisper-1` |
| `ASR_LANGUAGE` | 识别语言（留空自动检测） | 自动检测 |

### 服务配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `PORT` | 监听端口 | `3000` |

---

## 🛠️ 技术栈

### 客户端
- **Kotlin** + Android SDK（minSdk 24 / targetSdk 37）
- **OkHttp 4** — HTTP / SSE 客户端
- **RecyclerView** — 聊天列表
- **MediaRecorder** — 音频录制
- **MediaPlayer** — 语音回放
- **Edge-to-Edge** — 沉浸式界面适配

### 服务端
- **NestJS 11** + **TypeScript**
- **SSE (Server-Sent Events)** — 流式输出
- **Multer** — 文件上传处理
- 原生 `fetch` — 上游 API 调用

---

## 📚 开发文档

- [客户端接入大模型指南](docs/客户端接入大模型指南.md) — 架构选型、流式输出、会话管理、安全与成本控制
- [Android 15 Edge-to-Edge 适配指南](docs/Android15-EdgeToEdge适配指南.md)
- [后端服务文档](server/README.md) — 接口详情与配置说明

---

## 📝 License

仅供学习与研究使用。
