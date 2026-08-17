# SolonCode Agent Event Protocol 2.0 (SAEP 2.0) 技术规范说明书

## 1. 规范概述 (Overview)

SolonCode Agent Event Protocol (SAEP 2.0) 是 SolonCode 体系下用于 **Agent 运行时（Backend）与客户端展示层（Web / Desktop / Third-party Client）** 之间流式事件交互的开放通信规范。

本规范定义了标准事件信封结构、事件分类命名空间、各类事件的强类型 Payload 模型以及客户端分发与渲染建议，旨在提供统一、高扩展、易消费的 Agent 交互体验。

---

## 2. 传输与信封格式 (Protocol Envelope)

所有 Agent 事件均以 JSON 格式在传输层（如 WebSocket 文本帧、SSE、标准输出流）进行推送。每个事件必须包含统一的外层信封。

### 2.1 信封结构定义

```json
{
  "event": "message.delta",
  "traceId": "tr-63f82a9c",
  "sessionId": "sess-user-1001",
  "taskId": "task-abc-123",
  "agentName": "general",
  "timestamp": 1771123456789,
  "payload": { ... }
}
```

### 2.2 信封通用字段说明

| 字段名 | 类型 | 必填 | 说明 |
| :--- | :--- | :---: | :--- |
| `event` | String | **是** | 事件类型全称，采用 `namespace.action` 点分格式（见下表） |
| `traceId` | String | 否 | 单次交互/请求链路追踪唯一标识 |
| `sessionId` | String | 否 | 会话唯一标识 |
| `taskId` | String | 否 | 任务唯一标识（多 Agent 或子任务场景下使用） |
| `agentName` | String | 否 | 当前产生事件的 Agent 名称（如 `general`, `explore`, `plan` 等） |
| `timestamp` | Long | **是** | 事件生成时的毫秒级时间戳 (Unix Epoch ms) |
| `payload` | Object | **是** | 与特定 `event` 绑定的业务载荷数据 |

---

## 3. 事件命名空间与 Payload 字典 (Event Catalog)

SAEP 2.0 将事件清晰划分为 4 大核心领域：

```
SAEP 2.0 Event System
 ├── message.*   (模型消息与流式输出)
 ├── thought.*   (深度推理与思考过程)
 ├── tool.*      (工具调用与执行反馈)
 ├── task.*      (任务生命周期)
 ├── hitl.*      (人机交互干预)
 └── system.*    (系统级事件与流控制)
```

---

### 3.1 消息类 (Message)

#### `message.delta` (正文流式增量增量)
LLM 生成正文时的实时增量文本片段。
```json
{
  "event": "message.delta",
  "payload": {
    "text": "你好，这是 Agent 的流式回复增量..."
  }
}
```

#### `message.full` (完整消息落盘)
当一条完整消息生成完毕或从历史加载时推送。
```json
{
  "event": "message.full",
  "payload": {
    "text": "你好，这是 Agent 生成的完整文本内容。"
  }
}
```

---

### 3.2 思考推理类 (Thought)

#### `thought.delta` (思考增量)
支持 DeepSeek-R1 / OpenAI Reasoning 等思考模型的流式增量推理内容。
```json
{
  "event": "thought.delta",
  "payload": {
    "text": "正在分析当前工程目录结构..."
  }
}
```

#### `thought.full` (完整思考过程)
推理完成后的完整思考总结文本。
```json
{
  "event": "thought.full",
  "payload": {
    "text": "思考完成，接下来将通过 grep 工具定位关键字。"
  }
}
```

---

### 3.3 工具调用类 (Tool)

#### `tool.start` (工具调用开始)
Agent 发起外部工具调用，包含入参。
```json
{
  "event": "tool.start",
  "payload": {
    "toolId": "call_12345",
    "name": "bash",
    "arguments": "{\"command\":\"git status\"}"
  }
}
```

#### `tool.end` (工具执行结束)
工具调用完成，返回执行结果、状态与执行耗时。
```json
{
  "event": "tool.end",
  "payload": {
    "toolId": "call_12345",
    "name": "bash",
    "result": "On branch main\nnothing to commit, working tree clean",
    "success": true,
    "durationMs": 85
  }
}
```

---

### 3.4 任务生命周期类 (Task)

#### `task.start` (任务启动)
Agent 开始执行特定任务。
```json
{
  "event": "task.start",
  "payload": {
    "taskId": "task-build-1",
    "parentTaskId": "task-root"
  }
}
```

#### `task.done` (任务执行完成)
当前任务或整个 Agent 流式轮次执行完毕，携带 Token 消耗与耗时统计。
```json
{
  "event": "task.done",
  "payload": {
    "parentTaskId": "task-root",
    "durationMs": 1420,
    "inputTokens": 1050,
    "outputTokens": 320,
    "totalTokens": 1370
  }
}
```

---

### 3.5 人机协同干预类 (HITL - Human in the Loop)

#### `hitl.request` (等待人工确认/输入)
Agent 遇到高危操作或需要用户决策，阻塞流并请求客户端干预。
```json
{
  "event": "hitl.request",
  "payload": {
    "prompt": "检测到危险指令：rm -rf ./dist，是否允许执行？",
    "options": ["confirm", "reject"]
  }
}
```

---

### 3.6 系统控制与状态类 (System)

#### `system.trace` (执行追踪节点)
内部执行阶段或状态提示（如：意图识别中、检索知识库中）。
```json
{
  "event": "system.trace",
  "payload": {
    "text": "正在检索工程上下文索引..."
  }
}
```

#### `system.context` (上下文感知注入)
注入与同步上下文信息（如当前工作区目录、选中的模型信息）。
```json
{
  "event": "system.context",
  "payload": {
    "text": "/Users/developer/workspace/project-a"
  }
}
```

#### `system.filer_change` (文件变动通知)
工具执行导致文件修改时触发，用于客户端刷新文件树或 Diff 视图。
```json
{
  "event": "system.filer_change",
  "payload": {
    "path": "src/main/resources/app.yml",
    "changeType": "modify"
  }
}
```

#### `system.user_input` (用户输入回显)
用于在流式通道中回显用户的问题输入。
```json
{
  "event": "system.user_input",
  "payload": {
    "text": "帮我优化一下数据库查询"
  }
}
```

#### `system.command` (客户端指令触发)
后端向前端发出的 UI 控制指令（如清理控制台、重置视图）。
```json
{
  "event": "system.command",
  "payload": {
    "command": "clear_console"
  }
}
```

#### `system.rewind` (会话回滚指令)
告知客户端将状态回滚到指定的消息或检查点。
```json
{
  "event": "system.rewind",
  "payload": {
    "point": "msg-seq-12"
  }
}
```

#### `system.error` (错误/异常事件)
执行过程中发生的错误，提供机器可读的 `code` 与友好提示 `message`。
```json
{
  "event": "system.error",
  "payload": {
    "code": "AUTH_FAILED",
    "message": "模型 API Key 无效或已欠费，请检查配置"
  }
}
```

**常见错误码定义 (Error Codes)：**
- `AUTH_FAILED`: API Key 鉴权失败或配额耗尽
- `RATE_LIMIT`: 触发模型提供方频率限制
- `TIMEOUT`: 工具或网络请求超时
- `BAD_REQUEST`: 参数格式不合法或上下文超长
- `EXEC_FAILED`: 本地工具执行失败
- `INTERNAL_ERROR`: 未知系统异常

---

## 4. 架构与管道处理模型 (Backend Pipeline)

在 Solon 框架与 Reactor 响应式体系下，后端通过清晰的 Pipeline 处理 Agent 事件流：

```
[Agent Engine Raw Events]
         │
         ▼
 1. WebEventMapper (纯事件映射，转换为 WebEvent<T>)
         │
         ▼
 2. ToolPresentationFilter (工具展示层修饰，如 Git Diff、Todo 高亮)
         │
         ▼
 3. ChannelBroadcastSink (多渠道可选广播，如飞书/钉钉 IM 同步)
         │
         ▼
 4. SessionMetricsRecorder (Token 消耗统计与会话审计)
         │
         ▼
[WebSocket / SSE 客户端推送]
```

### 4.1 管道装配参考实现 (Java)

```java
public Flux<WebEvent<?>> build(Flux<AgentEvent> engineEventFlux, AgentSession session, WebEngine engine) {
    WebEventMapper mapper = new WebEventMapper(session);
    ToolPresentationFilter toolFilter = new ToolPresentationFilter(session);
    ChannelBroadcastSink broadcastSink = new ChannelBroadcastSink(session);
    SessionMetricsRecorder metricsRecorder = new SessionMetricsRecorder(session);

    return engineEventFlux
        .flatMap(mapper::mapEvent)
        .filter(Objects::nonNull)
        .map(toolFilter::decorate)
        .doOnNext(broadcastSink::broadcast)
        .doOnNext(metricsRecorder::record)
        .onErrorResume(err -> Flux.just(WebEvent.ofError(err)));
}
```

---

## 5. 客户端集成指南 (Client Integration Guide)

### 5.1 事件分发器推荐实现 (JavaScript/TypeScript)

客户端推荐通过注册表模式实现解耦分发：

```javascript
class AgentEventDispatcher {
    constructor() {
        this.handlers = new Map();
    }

    on(event, handler) {
        this.handlers.set(event, handler);
        return this;
    }

    dispatch(eventObj) {
        if (!eventObj || !eventObj.event) return;
        const handler = this.handlers.get(eventObj.event);
        if (handler) {
            handler(eventObj.payload, eventObj);
        } else {
            console.debug(`[SAEP] Unhandled event: ${eventObj.event}`, eventObj);
        }
    }
}
```

### 5.2 消费示例

```javascript
const dispatcher = new AgentEventDispatcher();

// 注册消息增量
dispatcher.on("message.delta", (payload) => {
    appendBotMessageDelta(payload.text);
});

// 注册思考增量
dispatcher.on("thought.delta", (payload) => {
    appendThinkingDelta(payload.text);
});

// 注册工具调用开始
dispatcher.on("tool.start", (payload) => {
    renderToolRunningCard(payload.toolId, payload.name, payload.arguments);
});

// 注册工具调用结束
dispatcher.on("tool.end", (payload) => {
    updateToolResult(payload.toolId, payload.result, payload.success);
});

// 注册任务完成
dispatcher.on("task.done", (payload) => {
    renderSessionStats(payload.durationMs, payload.totalTokens);
});

// 注册系统错误
dispatcher.on("system.error", (payload) => {
    showErrorToast(`[${payload.code || 'ERROR'}] ${payload.message}`);
});

// WebSocket 消息接收
ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    dispatcher.dispatch(data);
};
```

### 5.3 性能优化建议（UI 帧排空机制）
对于高频推送的 `message.delta` 与 `thought.delta`，建议客户端使用队列缓冲，并通过 `requestAnimationFrame` 批量排空更新 DOM，避免高频字符流导致浏览器重排卡顿。
