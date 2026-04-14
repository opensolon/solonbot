# AI Development — Solon AI 开发

> 适用场景：LLM 调用、Tool Calling、RAG、MCP 协议、智能体 Agent、AI UI、Harness 框架。

## ChatModel — LLM 调用

app.yml:
```yaml
solon.ai.chat:
  demo:
    apiUrl: "http://127.0.0.1:11434/api/chat"
    provider: "ollama"
    model: "llama3.2"
```

```java
@Configuration
public class AiConfig {
    @Bean
    public ChatModel chatModel(@Inject("${solon.ai.chat.demo}") ChatConfig config) {
        return ChatModel.of(config).build();
    }
}

// 同步调用
ChatResponse resp = chatModel.prompt("你好").call();
String content = resp.getMessage().getContent();

// 流式调用（需 solon-web-rx）
Flux<ChatResponse> stream = chatModel.prompt("你好").stream();
```

## Tool Calling

```java
@ToolMapping(description = "查询天气")
public String getWeather(@Param(description = "城市") String location) {
    return location + "：晴，14度";
}

@Bean
public ChatModel chatModel(ChatConfig config) {
    return ChatModel.of(config).defaultToolAdd(new WeatherTools()).build();
}
```

## RAG — 检索增强生成

Dependency: `solon-ai-rag`

```java
EmbeddingModel embeddingModel = EmbeddingModel.of("http://127.0.0.1:11434/api/embed")
    .provider("ollama").model("nomic-embed-text").build();

InMemoryRepository repository = new InMemoryRepository(embeddingModel);

// 文档加载与切分
List<Document> docs = new SplitterPipeline()
    .next(new RegexTextSplitter("\n\n"))
    .next(new TokenSizeTextSplitter(500))
    .split(new PdfLoader(new File("data.pdf")).load());
repository.save(docs);

// 检索并构造增强 Prompt
List<Document> context = repository.search("查询问题");
ChatMessage msg = ChatMessage.ofUserAugment("查询问题", context);
```

### RAG Document Loaders

| Artifact | Format | Loader 类 |
|---|---|---|
| `solon-ai-load-pdf` | PDF | `PdfLoader` |
| `solon-ai-load-word` | Word (.doc/.docx) | `WordLoader` |
| `solon-ai-load-excel` | Excel (.xls/.xlsx) | `ExcelLoader` |
| `solon-ai-load-html` | HTML | `HtmlSimpleLoader` |
| `solon-ai-load-markdown` | Markdown | `MarkdownLoader` |
| `solon-ai-load-ppt` | PowerPoint (.ppt/.pptx) | `PptLoader` |

```java
// 加载示例（各 Loader 用法一致）
PdfLoader loader = new PdfLoader(new File("data.pdf"));
List<Document> docs = loader.load();
repository.insert(docs);
```

### RAG Vector Repositories

| Artifact | Backend |
|---|---|
| `solon-ai-repo-milvus` | Milvus |
| `solon-ai-repo-pgvector` | PgVector |
| `solon-ai-repo-elasticsearch` | Elasticsearch |
| `solon-ai-repo-redis` | Redis |
| `solon-ai-repo-qdrant` | Qdrant |
| `solon-ai-repo-chroma` | Chroma |
| `solon-ai-repo-weaviate` | Weaviate |

### RAG WebSearch — 联网搜索

| Artifact | 搜索引擎 | Repository 类 |
|---|---|---|
| `solon-ai-search-baidu` | 百度搜索 | `BaiduWebSearchRepository` |
| `solon-ai-search-bocha` | Bocha 搜索 | `BochaWebSearchRepository` |
| `solon-ai-search-tavily` | Tavily 搜索 | `TavilyWebSearchRepository` |

## MCP — Model Context Protocol

Dependency: `solon-ai-mcp`

Server:
```java
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/mcp")
public class McpServerTool {
    @ToolMapping(description = "查询天气")
    public String getWeather(@Param(description = "城市") String location) {
        return location + "：晴，14度";
    }
}
```

Client:
```java
McpClientProvider client = McpClientProvider.builder()
    .channel(McpChannel.STREAMABLE)
    .url("http://localhost:8080/mcp").build();
ChatModel chatModel = ChatModel.of(config).defaultToolsAdd(client).build();
```

## Agent — 智能体

Dependency: `solon-ai-agent`

```java
// ReActAgent（自主推理 + 工具调用）
ReActAgent agent = ReActAgent.of(chatModel)
    .name("assistant")
    .defaultToolAdd(new SearchTools())
    .maxSteps(5)
    .build();
String answer = agent.prompt("搜索并总结...").call().getContent();

// TeamAgent（多 Agent 协作）
TeamAgent team = TeamAgent.of(chatModel)
    .name("DevTeam")
    .agentAdd(coder, reviewer)
    .maxTurns(5)
    .build();
String result = team.call(FlowContext.of(), "写一个单例模式");
```

## AI UI — 对接 Vercel AI SDK

Dependency: `solon-ai-ui-aisdk`

将 `ChatModel.prompt().stream()` 的 `Flux<ChatResponse>` 自动转换为 [UI Message Stream Protocol v1](https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol) 格式的 SSE 事件流，前端可直接使用 `@ai-sdk/vue` 或 `@ai-sdk/react` 的 `useChat`。

支持：文本流、深度思考(reasoning)、工具调用(tool-calls)、搜索结果引用(source-url)、文档引用(source-document)、文件(file)、自定义数据(data-*)、元数据(metadata)。

### 后端示例

```java
@Controller
public class AiChatController {
    @Inject ChatModel chatModel;
    private final AiSdkStreamWrapper wrapper = AiSdkStreamWrapper.of();

    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("/ai/chat/stream")
    public Flux<SseEvent> stream(String prompt, Context ctx) {
        ctx.headerSet("x-vercel-ai-ui-message-stream", "v1");
        return wrapper.toAiSdkStream(chatModel.prompt(prompt).stream());
    }
}
```

### 带会话记忆 + 元数据

```java
@Controller
public class AiChatController {
    @Inject ChatModel chatModel;
    private final AiSdkStreamWrapper wrapper = AiSdkStreamWrapper.of();
    private final Map<String, ChatSession> sessionMap = new ConcurrentHashMap<>();

    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("/ai/chat/stream")
    public Flux<SseEvent> stream(@Header("sessionId") String sessionId,
                                 String prompt, Context ctx) {
        ctx.headerSet("x-vercel-ai-ui-message-stream", "v1");
        ChatSession session = sessionMap.computeIfAbsent(sessionId,
                k -> InMemoryChatSession.builder().sessionId(k).build());
        Map<String, Object> metadata = Map.of("sessionId", sessionId);
        return wrapper.toAiSdkStream(
                chatModel.prompt(prompt).session(session).stream(), metadata);
    }
}
```

### 前端对接（Vue 3 + @ai-sdk/vue）

```vue
<script setup lang="ts">
import { useChat } from '@ai-sdk/vue'
const { messages, input, handleSubmit, status } = useChat({
  api: '/ai/chat/stream'
})
</script>
```

### 核心 Part 类

| Part 类 | type 值 | 说明 |
|---|---|---|
| `StartPart` | `start` | 流开始（含 messageId） |
| `TextStartPart` / `TextDeltaPart` / `TextEndPart` | `text-start` / `text-delta` / `text-end` | 正文流 |
| `ReasoningStartPart` / `ReasoningDeltaPart` / `ReasoningEndPart` | `reasoning-*` | 深度思考流 |
| `ToolInputStartPart` / `ToolInputDeltaPart` / `ToolInputAvailablePart` / `ToolOutputAvailablePart` | `tool-*` | 工具调用流 |
| `SourceUrlPart` / `SourceDocumentPart` | `source-url` / `source-document` | 引用来源 |
| `FilePart` | `file` | 文件附件 |
| `DataPart` | `data-*` | 自定义数据 |
| `FinishPart` | `finish` | 流结束（含 usage） |
| `ErrorPart` | `error` | 错误 |

### 自定义 Data Part

```java
DataPart weatherPart = DataPart.of("weather", Map.of("location", "SF", "temperature", 100));
// → {"type":"data-weather","data":{"location":"SF","temperature":100}}
```

## ACP — Agent Client Protocol

Dependency: `solon-ai-acp`

提供 ACP 协议支持（stdio、websocket），支持完整的 ACP 能力开发。

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-acp</artifactId>
</dependency>
```

## A2A — Agent to Agent

Dependency: `solon-ai-a2a`

提供智能体间通信协议支持。

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-a2a</artifactId>
</dependency>
```

## Harness — 智能体马具框架

Dependency: `solon-ai-harness`

v3.10.1 后支持。通过 `solon-ai-skill-*` 插件组合并定制而成的高性能智能体执行框架。

### 核心职责

- **工具使用 (Tool Steering)**: 动态挂载、MCP 协议、安全拦截
- **记忆与会话 (Memory & Session)**: 持久化、短期/长期记忆、快照恢复
- **上下文工程 (Context Engineering)**: 窗口滑窗、语义压缩、意图聚焦
- **环境隔离 (Sandbox)**: 影子空间、自愈循环

### Helloworld

```java
public class DemoApp {
    public static void main(String[] arg) throws Throwable {
        HarnessProperties harnessProps = new HarnessProperties(".tmp/");
        harnessProps.addTools(ToolPermission.TOOL_PI);
        harnessProps.addModel(null);

        ChatModel chatModel = ChatModel.of(harnessProps.getChatModel()).build();
        AgentSessionProvider sessionProvider = new AgentSessionProvider() {
            private Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();
            @Override
            public AgentSession getSession(String instanceId) {
                return sessionMap.computeIfAbsent(instanceId,
                        k -> InMemoryAgentSession.of(k));
            }
        };

        HarnessEngine engine = HarnessEngine.builder()
                .properties(harnessProps)
                .chatModel(chatModel)
                .sessionProvider(sessionProvider)
                .build();

        // 主代理执行
        AgentSession session = engine.getSession(HarnessEngine.SESSION_DEFAULT);
        engine.getMainAgent().prompt("hello").session(session).call();

        // 动态创建子代理
        AgentDefinition definition = new AgentDefinition();
        definition.setSystemPrompt("xxx");
        definition.getMetadata().addTools(ToolPermission.TOOL_BASH);
        ReActAgent subagent = engine.createSubagent(definition).build();
        subagent.prompt("hello").session(session).call();
    }
}
```

### 工具权限配置

| 工具名 | 类型 | 描述 |
|---|---|---|
| `**` | - | 所有公域 + 私域工具 |
| `*` | - | 仅所有公域工具 |
| `pi` | - | piagent 权限（read, write, edit, bash） |
| `hitl` | 私域 | 人工介入审核 |
| `generate` | 私域 | 动态生成子代理 |
| `mcp` | 私域 | MCP 服务接入 |
| `bash` / `read` / `edit` / `ls` / `grep` / `glob` | 公域 | 文件与命令操作 |
| `websearch` / `webfetch` / `codesearch` | 公域 | 网络搜索 |
| `todo` / `skill` / `task` / `code` | 公域 | 任务管理 |

### 内置拦截器

- `summarizationInterceptor` — 上下文摘要处理
- `hitlInterceptor` — 人工介入处理

```java
HarnessEngine engine = HarnessEngine.builder()
        .properties(harnessProps)
        .sessionProvider(sessionProvider)
        .summarizationInterceptor(new SummarizationInterceptor())
        .hitlInterceptor(new HITLInterceptor())
        .build();
```

---

## AI 注解参考

| Annotation | Target | Description |
|---|---|---|
| `@ToolMapping` | Method | 声明 AI 工具方法 |
| `@ToolMapping(name="...")` | Method | 指定工具名称 |
| `@McpServerEndpoint` | Class | 声明 MCP 服务端点 |
| `@Param(description="...")` | Parameter | 工具参数描述 |

## AI 核心 API 参考

| Class/Interface | Description |
|---|---|
| `ChatModel` | LLM 调用核心接口，支持 call/stream |
| `ChatConfig` | ChatModel 配置类，可从 yml 注入 |
| `ChatResponse` | 聊天响应 |
| `ChatMessage` | 消息构建，支持 ofUserAugment |
| `ChatSession` | 会话管理，支持多轮对话 |
| `InMemoryChatSession` | 内存会话实现 |
| `EmbeddingModel` | 嵌入模型接口 |
| `InMemoryRepository` | 内存向量知识库 |
| `SplitterPipeline` | 文档分割管道 |
| `ReActAgent` | 推理行动 Agent |
| `TeamAgent` | 多 Agent 协作 |
| `McpClientProvider` | MCP 客户端 |
| `McpChannel` | MCP 通道类型（STREAMABLE/SSE/STDIO） |
| `AiSdkStreamWrapper` | AI SDK 协议流包装器 |
| `HarnessEngine` | 智能体马具引擎 |

## AI 核心依赖

| Artifact | Description |
|---|---|
| `solon-ai` | 核心 AI 模块（ChatModel/ToolCall） |
| `solon-ai-rag` | RAG 检索增强生成 |
| `solon-ai-mcp` | MCP 协议支持 |
| `solon-ai-agent` | Agent 框架（ReAct/Team） |
| `solon-ai-flow` | AI + Flow 集成 |
| `solon-ai-ui-aisdk` | AI UI — 对接 Vercel AI SDK 协议 |
| `solon-ai-acp` | ACP 协议支持（stdio/websocket） |
| `solon-ai-a2a` | A2A 智能体间通信 |
| `solon-ai-harness` | 智能体马具框架 |

## LLM Dialects

| Artifact | Provider |
|---|---|
| `solon-ai-dialect-openai` | OpenAI / compatible APIs (DeepSeek, etc.) |
| `solon-ai-dialect-ollama` | Ollama |
| `solon-ai-dialect-gemini` | Google Gemini |
| `solon-ai-dialect-claude` | Anthropic Claude |
| `solon-ai-dialect-dashscope` | Alibaba DashScope |
