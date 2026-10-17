# NativeAI Server

NestJS 后端：对接 DeepSeek API（OpenAI 兼容的 `/chat/completions` 格式），对外提供 SSE 流式问答接口。API Key 只存在于服务端，客户端不接触。

> 任何 OpenAI 兼容格式的服务（DeepSeek 官方、火山方舟、各类中转）都可通过 `LLM_BASE_URL` + `LLM_MODEL` 接入。

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

### `GET /health`

健康检查，返回 `{"ok":true}`。

## 运行

```bash
cd server
cp .env.example .env    # 填入 LLM_API_KEY
npm install
npm run start:dev       # 开发模式，监听 0.0.0.0:3000
```

## 配置项（.env，统一 LLM_ 前缀）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `LLM_API_KEY` | API 密钥（必填），以 `Bearer` 方式发送 | - |
| `LLM_BASE_URL` | API 地址（DeepSeek 官方 / 火山方舟 / 中转服务） | `https://api.deepseek.com` |
| `LLM_MODEL` | 模型 ID | `deepseek-flash` |
| `LLM_MAX_TOKENS` | 单次回答上限（0 = 不限制） | `0` |
| `LLM_SYSTEM_PROMPT` | 系统提示词 | 无 |
| `LLM_THINKING` | 思考模式 `enabled` / `disabled` | `enabled` |
| `LLM_REASONING_EFFORT` | 推理深度 `low` / `medium` / `high` | `high` |
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

```bash
curl -N -X POST http://localhost:3000/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"messages":[],"question":"用一句话介绍杭州"}'
```

## 模拟器访问

Android 模拟器里 `localhost` 指向模拟器自身，访问宿主机服务用 `http://10.0.2.2:3000`；
真机调试时把客户端的 `BASE_URL` 换成电脑的局域网 IP（如 `http://192.168.1.5:3000`）。

## 备注

- 思考过程（`reasoning` 事件）与正文（`delta` 事件）分开转发，客户端可只显示正文
- 火山方舟的模型需要在控制台开通后才能调用；模型名与官方不同（如 `deepseek-v4-1-flash-260910`），以方舟模型列表为准
