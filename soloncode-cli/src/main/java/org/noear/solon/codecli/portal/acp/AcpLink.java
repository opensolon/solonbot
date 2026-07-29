package org.noear.solon.codecli.portal.acp;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.task.PlanEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.core.util.Assert;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AcpLink implements Runnable {
    private final HarnessEngine agentRuntime;
    private final AcpAgentTransport agentTransport;
    private final AgentSettings agentSettings;

    public AcpLink(HarnessEngine agentRuntime, AcpAgentTransport agentTransport, AgentSettings agentSettings) {
        this.agentRuntime = agentRuntime;
        this.agentTransport = agentTransport;
        this.agentSettings = agentSettings;
    }

    private final Map<String, AcpSessionContext> sessionStates = new ConcurrentHashMap<>();

    public void run() {
        AcpAsyncAgent acpAgent = createAgent(agentTransport);
        acpAgent.start().subscribe();
    }

    public AcpAsyncAgent createAgent(AcpAgentTransport transport) {
        return AcpAgent.async(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .initializeHandler(req -> {
                    // SessionCapabilities: 参数为 Object，非 null 表示"支持"，null 表示"不支持"。
                    // 声明 list=false(null), close=true, resume=false(null)。
                    // 只有声明了 close 能力，客户端才会主动调用 session/close。
                    AcpSchema.SessionCapabilities sessionCaps =
                            new AcpSchema.SessionCapabilities(null, Boolean.TRUE, null);

                    return Mono.just(new AcpSchema.InitializeResponse(
                            1,
                            new AcpSchema.AgentCapabilities(true,
                                    sessionCaps,
                                    new AcpSchema.McpCapabilities(true, true),
                                    new AcpSchema.PromptCapabilities(true, true, true),
                                    null),
                            Arrays.asList()
                    ));
                })
                .newSessionHandler(req -> {
                    String sessionId = "acp-" + UUID.randomUUID().toString().substring(0, 8);
                    String cwd = req.cwd();

                    sessionStates.put(sessionId, new AcpSessionContext(cwd, req.mcpServers()));

                    return Mono.just(new AcpSchema.NewSessionResponse(sessionId, null, null));
                })
                .loadSessionHandler(req -> {
                    String sessionId = req.sessionId();
                    String cwd = req.cwd();

                    sessionStates.put(sessionId, new AcpSessionContext(cwd, req.mcpServers()));

                    return Mono.just(new AcpSchema.LoadSessionResponse(null, null));
                })
                .cancelHandler(req -> {
                    String sessionId = req.sessionId();
                    AcpSessionContext context = sessionStates.get(sessionId);
                    if (context != null) {
                        // 设置取消标志，prompt 流中的 takeWhile(!cancelled) 会在下一个 chunk 时中断后端任务
                        context.setCancelled(true);
                    }
                    return Mono.empty();
                })
                .closeSessionHandler(req -> {
                    // 客户端显式关闭 session：正确的清理时机
                    String sessionId = req.sessionId();
                    AcpSessionContext context = sessionStates.remove(sessionId);
                    if (context != null) {
                        context.setCancelled(true);
                    }
                    // 同步清理后端 AgentSession，避免 SessionManager.sessionMap 内存泄漏。
                    // 注意：仅从内存 map 移除，磁盘上的 FileAgentSession 历史保留，供后续 loadSession 恢复。
                    removeBackendSession(sessionId);
                    return Mono.just(new AcpSchema.CloseSessionResponse());
                })
                .promptHandler((request, acpContext) -> {
                    String sessionId = acpContext.getSessionId();
                    final AcpSessionContext context = sessionStates.get(sessionId);

                    // session 不存在时抛出协议异常，SDK 会保留 SESSION_NOT_FOUND(-32002) 错误码，
                    // 客户端收到语义明确的错误而非 NPE 兵底的 -32603。
                    if (context == null) {
                        return Mono.error(new AcpProtocolException(
                                AcpErrorCodes.SESSION_NOT_FOUND,
                                "Session not found: " + sessionId));
                    }

                    Prompt userInput = toPrompt(request);
                    AgentSession session = agentRuntime.getSession(sessionId);

                    final long startTime = System.currentTimeMillis();
                    final AtomicInteger toolCallCounter = new AtomicInteger(0);

                    return agentRuntime.prompt(userInput)
                            .session(session)
                            .options(o -> {
                                if (Assert.isNotEmpty(context.getCwd())) {
                                    o.toolContextPut(HarnessEngine.ATTR_CWD, context.getCwd());
                                }
                            })
                            .stream()
                            .takeWhile(chunk -> !context.isCancelled())
                            .concatMap(chunk -> {
                                // === 规划阶段：映射到 ACP Plan 结构化输出 ===
                                if (chunk instanceof PlanEvent) {
                                    String content = chunk.getContent();
                                    AcpSchema.PlanEntry entry = new AcpSchema.PlanEntry(
                                            content != null ? content : "Planning...",
                                            AcpSchema.PlanEntryPriority.HIGH,
                                            AcpSchema.PlanEntryStatus.IN_PROGRESS
                                    );
                                    AcpSchema.Plan plan = new AcpSchema.Plan("plan", Collections.singletonList(entry));
                                    return acpContext.sendUpdate(sessionId, plan)
                                            .thenReturn(chunk);
                                }
                                // === 思考阶段 ===
                                else if (chunk instanceof ReasonDeltaEvent) {
                                    ReasonDeltaEvent reasonChunk = (ReasonDeltaEvent) chunk;
                                    if (chunk.hasContent() && !reasonChunk.isToolCalls()) {
                                        if (agentSettings.getGeneral().isCliThinkPrinted()) {
                                            return acpContext.sendThought(chunk.getContent())
                                                    .thenReturn(chunk);
                                        }
                                    }
                                }
                                // === ThoughtChunk（多任务并行） ===
                                else if (chunk instanceof ReasonEndEvent) {
                                    ReasonEndEvent thoughtChunk = (ReasonEndEvent) chunk;
                                    if (thoughtChunk.hasMeta(TaskTalent.TOOL_MULTITASK)) {
                                        String content = thoughtChunk.getAssistantMessage().getResultContent();
                                        if (Assert.isNotEmpty(content)) {
                                            return acpContext.sendThought(content)
                                                    .thenReturn(chunk);
                                        }
                                    }
                                }
                                // === 工具执行阶段：映射到 ACP ToolCall 结构化输出 ===
                                else if (chunk instanceof ToolCallEndEvent) {
                                    ToolCallEndEvent observationChunk = (ToolCallEndEvent) chunk;
                                    String toolName = observationChunk.getToolName();

                                    // 跳过内部任务分发工具（不向客户端展示）
                                    if (TaskTalent.TOOL_MULTITASK.equals(toolName) || TaskTalent.TOOL_TASK.equals(toolName)) {
                                        return Mono.just(chunk);
                                    }

                                    String toolCallId = "tc-" + toolCallCounter.incrementAndGet();
                                    String content = chunk.getContent();

                                    // 使用 ACP ToolCall 构建结构化工具调用通知
                                    AcpSchema.ToolCall toolCall = new AcpSchema.ToolCall(
                                            "tool_call",
                                            toolCallId,
                                            buildToolTitle(toolName, observationChunk.getArgs(), content),
                                            AcpSchema.ToolKind.EXECUTE,
                                            AcpSchema.ToolCallStatus.COMPLETED,
                                            Collections.emptyList(),
                                            Collections.emptyList(),
                                            observationChunk.getArgs(),   // rawInput
                                            content,                 // rawOutput
                                            null                     // meta
                                    );
                                    return acpContext.sendUpdate(sessionId, toolCall)
                                            .thenReturn(chunk);
                                }
                                // === 最终回复阶段 ===
                                else if (chunk instanceof RunEndEvent) {
                                    String traceInfo = buildTraceInfo(((RunEndEvent) chunk).getTrace(), startTime);

                                    String finalContent = chunk.getContent() + traceInfo;

                                    // 发送最终文本内容
                                    return acpContext.sendMessage(finalContent)
                                            .thenReturn(chunk);
                                }

                                return Mono.just(chunk);
                            })
                            .onErrorResume(e -> {
                                // 协议异常透传，保留原错误码；其余异常以消息形式反馈
                                if (e instanceof AcpProtocolException) {
                                    return Mono.error(e);
                                }
                                return acpContext.sendMessage("Error: " + e.getMessage())
                                        .then(Mono.empty());
                            })
                            .doFinally(signal -> {
                                // 不再删除 sessionStates（session 生命周期由 close/cancel 控制），
                                // 仅重置本轮取消标志，为下一轮 prompt 准备
                                context.setCancelled(false);
                            })
                            .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
                })
                .build();
    }

    /**
     * 同步移除后端 AgentSession（仅内存 map，不删除磁盘历史）。
     *
     * <p>HarnessEngine.getSessionProvider() 返回 AgentSessionProvider 接口，
     * removeSession 仅定义在具体实现 SessionManager 上，故需 instanceof 判断。</p>
     */
    private void removeBackendSession(String sessionId) {
        AgentSessionProvider provider = agentRuntime.getSessionProvider();
        if (provider instanceof SessionManager) {
            ((SessionManager) provider).removeSession(sessionId);
        }
    }

    /**
     * 构建工具调用的显示标题
     */
    private String buildToolTitle(String toolName, Map<String, Object> args, String content) {
        if (Assert.isEmpty(toolName)) {
            return content;
        }

        String argsStr = buildArgsStr(args);

        if (agentSettings.getGeneral().isCliPrintSimplified()) {
            // 简化模式：只显示工具名 + 结果摘要
            String summary;
            if (Assert.isEmpty(content)) {
                summary = "completed";
            } else {
                String[] lines = content.split("\n");
                if (lines.length > 1) {
                    summary = "returned " + lines.length + " lines";
                } else {
                    summary = content.length() > 40 ? content.substring(0, 37) + "..." : content;
                }
            }
            return toolName + ": " + summary;
        } else {
            // 全量模式：显示工具名 + 参数
            if (argsStr.length() > 100) {
                return toolName + "(" + argsStr.substring(0, 97) + "...)";
            }
            return toolName + "(" + argsStr + ")";
        }
    }

    /**
     * 构建 trace 统计信息（参考 WebStreamBuilder.getTraceInfo）
     */
    private String buildTraceInfo(ReActTrace trace, long startTime) {
        StringBuilder buf = new StringBuilder();
        buf.append("(");

        if (trace != null) {
            if (trace.getOptions() != null && trace.getOptions().getChatModel() != null) {
                buf.append(trace.getOptions().getChatModel().getNameOrModel());
            }
            if (trace.getMetrics() != null) {
                if (buf.length() > 1) buf.append(", ");
                buf.append(trace.getMetrics().getTotalTokens()).append("tk");
            }
        }

        long seconds = Duration.ofMillis(System.currentTimeMillis() - startTime).getSeconds();
        if (buf.length() > 1) buf.append(", ");
        buf.append(seconds).append("s");

        buf.append(")");
        return buf.toString();
    }

    private String buildArgsStr(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        args.forEach((k, v) -> {
            if (sb.length() > 0) sb.append(" ");
            sb.append(k).append("=").append(v);
        });
        return sb.toString().replace("\n", " ");
    }

    public Prompt toPrompt(AcpSchema.PromptRequest promptRequest) {
        Prompt prompt = Prompt.of();

        Contents contents = new Contents();

        for (AcpSchema.ContentBlock cp : promptRequest.prompt()) {
            if (cp instanceof AcpSchema.TextContent) {
                AcpSchema.TextContent text = (AcpSchema.TextContent) cp;
                contents.addBlock(TextBlock.of(text.text()));
            } else if (cp instanceof AcpSchema.ImageContent) {
                AcpSchema.ImageContent image = (AcpSchema.ImageContent) cp;
                if (Assert.isEmpty(image.uri())) {
                    contents.addBlock(ImageBlock.ofBase64(image.data(), image.mimeType()));
                } else {
                    contents.addBlock(ImageBlock.ofUrl(image.uri(), image.mimeType()));
                }
            }
        }

        return prompt.addMessage(ChatMessage.ofUser(contents));
    }

    public static class AcpSessionContext {
        private final String cwd;
        private final List<AcpSchema.McpServer> mcpServers;
        private final Instant createdAt;
        private volatile boolean cancelled;

        public AcpSessionContext(String cwd, List<AcpSchema.McpServer> mcpServers) {
            this.cwd = cwd;
            this.mcpServers = mcpServers;
            this.createdAt = Instant.now();
        }

        public String getCwd() {
            return cwd;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public List<AcpSchema.McpServer> getMcpServers() {
            return mcpServers;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
