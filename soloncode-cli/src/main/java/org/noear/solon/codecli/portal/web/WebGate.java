/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.web;

import org.noear.snack4.ONode;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.util.CmdUtil;
import org.noear.solon.codecli.command.WebCommandContext;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.SystemTracePayload;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionMeta;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebGate - 前端统一 WebSocket 网关
 *
 * <p>作为后端的统一输出调度 + 统一输入入口，消除双通道问题。
 * 前端整个生命周期只维护一个 WebSocket 连接，不跟任何特定 sessionId 绑定。
 * 后端推送的所有消息包都携带 sessionId 字段，前端根据此字段分发到对应会话进行渲染。</p>
 *
 * @author noear 2026/5/8 created
 */
public class WebGate extends SimpleWebSocketListener {
    private static final Logger LOG = LoggerFactory.getLogger(WebGate.class);
    /** HITL 审批时前端回传的 callUuid（通过 session context 透传） */
    public static final String CTX_HITL_CALL_ID = "hitl.callId";

    /** 会话属性：本轮 agent 流是否已向客户端发送过 done（防 interrupt + doFinally 双发） */
    private static final String ATTR_STREAM_DONE_SENT = "streamDoneSent";

    /** AI 引擎实例，提供会话管理、模型获取、命令注册等核心能力 */
    private final HarnessEngine engine;

    /** 流式响应构建器，负责组装 ReAct Agent 的流式输出并通过本网关推送 */
    private final WebStreamBuilder streamBuilder;

    /**
     * WebSocket 连接池（与所属 {@link org.noear.solon.codecli.workspace.WorkspaceContext} 共享同一引用）。
     *
     * <p>每个浏览器 Tab 建立一个独立的 WebSocket 连接。连接在握手时由入口网关
     * （注册于 /web/gate 的单例）按 workspaceId 分发到<b>目标工作区上下文</b>的连接池，
     * 因此本实例的 {@code connections} 恒等于其所属工作区上下文的连接集合。</p>
     *
     * <p>出站消息（AI 响应、命令输出、系统事件）只遍历本工作区连接池推送，
     * 从而实现「推送严格按 socket 所属工作区分组」，不依赖请求线程的 {@link Context#current()}，
     * 消除 AI 流式响应异步线程（boundedElastic）下的跨工作区串流风险。</p>
     *
     * <p>使用 {@link CopyOnWriteArrayList} 保证并发读写安全。</p>
     */
    private final List<WebSocket> connections;


    /**
     * 构造网关实例。
     *
     * @param engine      AI 引擎，提供会话、模型、Agent、命令等核心服务
     * @param settings    工作区配置
     * @param connections 所属工作区上下文的连接池（共享引用；推送只作用于此集合）
     */
    private final AgentSettings settings;

    public WebGate(HarnessEngine engine, AgentSettings settings, List<WebSocket> connections) {
        this.engine = engine;
        this.settings = settings;
        this.connections = connections;
        this.streamBuilder = new WebStreamBuilder(engine);
    }

    /**
     * 获取流式响应构建器。
     *
     * <p>供 WeChatLink 等外部组件引用，用于构建与 WebSocket 网关共享的流式输出管道。</p>
     *
     * @return 当前网关关联的 {@link WebStreamBuilder} 实例
     */
    public WebStreamBuilder getStreamBuilder() {
        return streamBuilder;
    }

    // ═══════════════════════════════════════════════════════════════
    //  WebSocket 生命周期管理
    // ═══════════════════════════════════════════════════════════════

    /**
     * WebSocket 连接建立时回调。
     *
     * <p>将新连接加入 {@link #connections} 连接池，后续出站消息将自动广播至此连接。</p>
     *
     * @param socket 新建立的 WebSocket 连接
     */
    @Override
    public void onOpen(WebSocket socket) {
        String wsId = socket.param("workspaceId"); // 统一：只认 workspaceId；旧参数 workspace 已废弃
        // 入口单例：按 workspaceId 将连接分发到目标工作区上下文的连接池（即该工作区 WebGate 实例的 connections）。
        // 本实例可能就是默认工作区的 WebGate，也可能是入口单例（两者共享默认工作区的 connections）。
        List<WebSocket> target = resolveConnections(wsId);
        target.add(socket);
        LOG.info("[WebGate] WebSocket opened: {}, workspace: {}", socket.id(), wsId);
    }

    /**
     * 按 workspaceId 解析目标连接池：命中工作区上下文则用其共享连接池，否则回退本实例 connections。
     */
    private List<WebSocket> resolveConnections(String wsId) {
        org.noear.solon.codecli.workspace.WorkspaceManager manager = org.noear.solon.Solon.context().getBean(org.noear.solon.codecli.workspace.WorkspaceManager.class);
        if (manager != null) {
            org.noear.solon.codecli.workspace.WorkspaceContext wctx = manager.getOrCreate(wsId);
            if (wctx != null) {
                return wctx.getConnections();
            }
        }
        return connections;
    }

    /**
     * WebSocket 连接关闭时回调。
     *
     * <p>从 {@link #connections} 连接池中移除已断开的连接，停止向其推送消息。</p>
     *
     * @param socket 已关闭的 WebSocket 连接
     */
    @Override
    public void onClose(WebSocket socket) {
        String wsId = socket.param("workspaceId");
        // 与 onOpen 对称：从目标工作区连接池中移除；兵底同时从本实例 connections 移除（共享引用时为同一列表，重复 remove 无害）。
        resolveConnections(wsId).remove(socket);
        connections.remove(socket);
        LOG.info("[WebGate] WebSocket closed: {}", socket.id());
    }

    /**
     * WebSocket 文本消息接收回调。
     *
     * <p>当前仅处理心跳检测（ping/pong），业务消息通过 HTTP 接口入口进入。</p>
     *
     * @param socket 来源 WebSocket 连接
     * @param text   接收到的文本消息
     */
    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        // 心跳处理
        if ("ping".equals(text)) {
            socket.send("pong");
        }
    }


    // ═══════════════════════════════════════════════════════════════
    //  输出端口 —— 向前端推送消息
    // ═══════════════════════════════════════════════════════════════

    /**
     * 统一输出：将消息块通过 WebSocket 推送至前端。
     *
     * <p>将 sessionId 注入到消息块中，然后序列化为 JSON 广播给所有已连接的前端。
     * 前端根据消息中的 sessionId 字段路由到对应的会话面板进行渲染。</p>
     *
     * @param sessionId 会话标识，用于前端路由消息到正确的会话面板
     * @param jsonChunk 待推送的消息块（可为文本流、错误、完成信号等多种类型）
     */
    public void emitToClient(String sessionId, WebEvent<?> event) {
        if (event == null) {
            return;
        } else {
            event.setSessionId(sessionId);
        }

        // 确保消息中包含 sessionId
        String enriched = ONode.serialize(event);

        if (LOG.isDebugEnabled()) {
            LOG.debug("emit: " + enriched);
        }

        // 推送严格按 socket 所属工作区分组：直接遍历本实例（= 所属工作区上下文）的连接池，
        // 不再依赖 Context.current() 猜测（异步流线程下为 null 会回退到默认工作区而串流）。
        for (WebSocket socket : connections) {
            if (socket != null) {
                try {
                    socket.send(enriched);
                } catch (Throwable e) {
                    LOG.warn("[WebGate] Failed to send to socket {}: {}", socket.id(), e.getMessage());
                }
            }
        }
    }

    /**
     * 流级 done 只发一次；返回 true 表示本次真正发出。
     *
     * <p>覆盖正常完成、异常、用户 interrupt 等路径，避免 dispose + doFinally 与
     * interrupt 显式 ofDone 造成双 done。</p>
     */
    private boolean emitDoneOnce(AgentSession session) {
        if (session == null) {
            return false;
        }
        AtomicBoolean doneSent = (AtomicBoolean) session.attrs()
                .computeIfAbsent(ATTR_STREAM_DONE_SENT, k -> new AtomicBoolean(false));
        if (!doneSent.compareAndSet(false, true)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[WebGate] skip duplicate done for session {}", session.getSessionId());
            }
            return false;
        }
        emitToClient(session.getSessionId(), WebEvent.ofDone());
        return true;
    }

    /**
     * 新开流前重置 done 标记，避免上一轮 streamDoneSent 挡住本轮 done。
     */
    private void resetStreamDoneSent(AgentSession session) {
        if (session == null) {
            return;
        }
        session.attrs().put(ATTR_STREAM_DONE_SENT, new AtomicBoolean(false));
    }

    /**
     * 广播原始 JSON 字符串到所有 WebSocket 连接。
     *
     * <p>与 {@link #emitToClient} 不同，此方法不注入 sessionId，
     * 适用于系统级事件（如文件变化通知）等需要全局广播的场景。</p>
     *
     * @param json 待广播的原始 JSON 字符串
     */
    public void broadcastRaw(String json) {
        // 推送严格按 socket 所属工作区分组：只广播到本工作区连接池，不依赖 Context.current()。
        for (WebSocket socket : connections) {
            if (socket != null) {
                try {
                    socket.send(json);
                } catch (Throwable e) {
                    LOG.warn("[WebGate] broadcastRaw failed for {}: {}", socket.id(), e.getMessage());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  输入端口 —— 接收并处理用户请求
    // ═══════════════════════════════════════════════════════════════

    /**
     * 用户聊天输入入口（含推理选项）。
     */
    public void onChatInput(String sessionId,
                            String sessionCwd,
                            String input, String selectedModel,
                            UploadedFile[] attachments, String[] attachmentTypes,
                            String hitlAction, String source) {
        onChatInput(sessionId, sessionCwd, input, selectedModel, attachments, attachmentTypes,
                hitlAction, source, null, null, null);
    }

    /**
     * 用户聊天输入入口（由 WebController HTTP 接口调用）。
     *
     * <p>核心处理流程：</p>
     * <ol>
     *   <li>解析 Agent 指定前缀（如 "@agentName 消息内容"）</li>
     *   <li>处理 HITL（Human-in-the-Loop）审批/拒绝操作</li>
     *   <li>处理文件附件上传（图片走 Base64 编码，其他走文件路径引用）</li>
     *   <li>判断是否为斜杠命令（/command），若是则走命令分发</li>
     *   <li>构建 Prompt 并启动 Agent 流式任务</li>
     * </ol>
     *
     * @param sessionId       会话标识
     * @param sessionCwd      会话当前工作目录，用于 Agent 执行文件操作的基准路径
     * @param input           用户输入的文本内容
     * @param selectedModel   用户选择的 AI 模型标识（可为 null，表示使用默认模型）
     * @param attachments     上传的文件附件数组（可为 null）
     * @param attachmentTypes 附件类型数组，与 attachments 一一对应（如 "image"）
     * @param hitlAction      HITL 操作类型，取值 "approve" 或 "reject"（可为 null）
     * @param source          消息来源通道标识
     * @param reasoningEffort 请求级推理水平（可选，写入会话后由 StreamBuilder 注入）
     * @param thinkingMode    请求级思考模式 on|off（可选，独立于推理强度，写入会话后由 StreamBuilder 注入）
     * @param selectedAgent   选择器指定的子代理（可选；空值时使用主 Agent）
     */
    public void onChatInput(String sessionId,
                            String sessionCwd,
                            String input, String selectedModel,
                            UploadedFile[] attachments, String[] attachmentTypes,
                            String hitlAction, String source,
                            String reasoningEffort, String thinkingMode, String selectedAgent) {
        AgentSession session = null;
        try {
            // 本 WebGate 实例已绑定所属工作区的引擎（与 connections 同一上下文），
            // 无需再从 Context.current()/sessionId 猜测引擎，避免异步线程下回退默认引擎。
            HarnessEngine currentEngine = engine;
            session = currentEngine.getSession(sessionId);

            // 写入会话级模型 / 推理（后续 StreamBuilder 与旁路任务均可读取）
            if (Assert.isNotEmpty(selectedModel)) {
                session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
            }
            // 写入会话级子代理选择（与模型一样的持久化逻辑）
            session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED,
                    selectedAgent != null ? selectedAgent : "");
            boolean effortProvided = reasoningEffort != null;
            ReasoningSupportUtil.putSessionEffort(session, reasoningEffort, effortProvided);
            boolean modeProvided = thinkingMode != null;
            ReasoningSupportUtil.putSessionThinkingMode(session, thinkingMode, modeProvided);

            String agentName = null;
            String currentInput = input;

            if (currentInput != null && currentInput.startsWith("@")) {
                int agentNameIdx = currentInput.indexOf(" ");
                if (agentNameIdx > 0) {
                    String explicitAgent = currentInput.substring(1, agentNameIdx);
                    if (engine.getAgentManager().hasAgent(explicitAgent)) {
                        agentName = explicitAgent;
                        currentInput = currentInput.substring(agentNameIdx + 1);
                    }
                }
            }

            // 输入开头的有效 @子代理 优先；否则使用选择器传入的有效子代理；都没有时使用主 Agent。
            if (agentName == null && Assert.isNotEmpty(selectedAgent)
                    && engine.getAgentManager().hasAgent(selectedAgent)) {
                agentName = selectedAgent;
            }


            // HITL approve/reject handling（按 callUuid 精确决策，支持批量逐卡审批）
            if (Assert.isNotEmpty(hitlAction)) {
                // callId 通过 session context 透传（前端 POST hitlCallId），避免全链路改签名
                String hitlCallId = session.getContext().getAs(CTX_HITL_CALL_ID);
                session.getContext().remove(CTX_HITL_CALL_ID);

                HITLTask task = Assert.isNotEmpty(hitlCallId)
                        ? HITL.getPendingTaskByCallUuid(session, hitlCallId)
                        : HITL.getPendingTask(session); // 无 callId 时回退单任务（兼容旧前端）

                if (task != null) {
                    if ("approve".equals(hitlAction)) {
                        HITL.approve(session, task);
                    } else {
                        HITL.reject(session, task);
                    }
                }

                // 恢复时机：批量场景下前端逐卡点击会发多次决策，
                // 仅当本批所有挂起任务都已有决策时才恢复流，否则只写决策不 resume。
                if (allHitlDecided(session)) {
                    performAgentTaskAsync(session, sessionCwd, null, selectedModel, agentName);
                }
                return;
            }

            // Handle file upload
            List<ImageBlock> imageBlocks = new ArrayList<>();
            List<String> imageFileNames = new ArrayList<>();
            List<String> fileAttachments = new ArrayList<>();

            if (attachments != null) {
                for (int i = 0; i < attachments.length; i++) {
                    UploadedFile attachment = attachments[i];
                    String fileName = attachment.getName();
                    if (fileName != null && !fileName.contains("..") && !fileName.contains("/") && !fileName.contains("\\")) {
                        String ext = "." + attachment.getExtension();
                        Path uploadDir = Paths.get(engine.getWorkspace(), ".uploads").toAbsolutePath().normalize();
                        Files.createDirectories(uploadDir);
                        Path savePath = uploadDir.resolve(fileName).toAbsolutePath().normalize();
                        fileName = ".uploads/" + fileName;

                        if (savePath.startsWith(Paths.get(engine.getWorkspace()).toAbsolutePath().normalize())) {
                            Files.copy(attachment.getContent(), savePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                            if (isImageAttachment(ext, attachmentTypes != null && i < attachmentTypes.length ? attachmentTypes[i] : null)) {
                                byte[] bytes = Files.readAllBytes(savePath);
                                String base64 = Base64.getEncoder().encodeToString(bytes);
                                String mime = extensionToMime(ext);
                                imageBlocks.add(ImageBlock.ofBase64(base64, mime));
                                imageFileNames.add(fileName);
                            } else {
                                fileAttachments.add(fileName);
                            }
                        }
                    }
                }
            }

            // Build input text with file attachment prefix
            if (!fileAttachments.isEmpty()) {
                String filePrefix = fileAttachments.stream()
                        .map(f -> "[附件: " + f + "]")
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (currentInput == null || currentInput.isEmpty()) {
                    currentInput = filePrefix + "\n请帮我处理这些附件";
                } else {
                    currentInput = filePrefix + "\n" + currentInput;
                }
            }

            if (Assert.isNotEmpty(currentInput) || !imageBlocks.isEmpty()) {
                if (currentInput == null || currentInput.isEmpty()) {
                    currentInput = imageBlocks.size() > 1 ? "请描述这些图片" : "请描述这张图片";
                }

                // 命令分发
                if (currentInput.startsWith("/") && imageBlocks.isEmpty()) {
                    if (isCommand(session, sessionCwd, currentInput, selectedModel, agentName)) {
                        return;
                    }
                }

                Prompt prompt;
                if (!imageBlocks.isEmpty()) {
                    Contents contents = new Contents();
                    contents.addBlock(TextBlock.of(currentInput));
                    for (ImageBlock block : imageBlocks) {
                        contents.addBlock(block);
                    }
                    // 构建附件元数据（含图片文件名），供历史消息恢复时前端渲染文件名标签
                    String attachMeta = buildAttachmentMeta(imageFileNames);
                    UserMessage userMsg = new UserMessage(contents).addMetadata("source", source);
                    if (attachMeta != null) {
                        userMsg.addMetadata("attachments", attachMeta);
                    }
                    prompt = Prompt.of(userMsg);
                } else {
                    // 文件附件已在 currentInput 前缀写入文件名（[附件: xxx]），ndjson 有记录
                    prompt = Prompt.of(ChatMessage.ofUser(currentInput).addMetadata("source", source));
                }

                // 流式处理：输出通过 WebSocket 推送
                performAgentTaskAsync(session, sessionCwd, prompt, selectedModel, agentName);
            }
        } catch (Exception e) {
            LOG.error("Task fail: {}", e.getMessage(), e);
            emitToClient(sessionId, WebEvent.ofError(e));
            // 流可能尚未建立：有 session 走去重出口，否则直接发 done
            if (session != null) {
                emitDoneOnce(session);
            } else {
                emitToClient(sessionId, WebEvent.ofDone());
            }
        } finally {
            if (session != null) {
                if (session.isEmpty() && Assert.isNotEmpty(input)) {
                    //如果是空，可能发的是 command（还没有对话记录）
                    try {
                        Path sessionPath = Paths.get(engine.getWorkspace(), engine.getHarnessSessions(), sessionId).toAbsolutePath().normalize();
                        SessionMeta meta = SessionMeta.load(sessionPath);
                        if (Assert.isEmpty(meta.getLabel())) {
                            // 从用户输入生成 label（空会话场景，如纯命令输入）
                            String label = input.trim();
                            if (label.length() > 50) {
                                label = label.substring(0, 50);
                            }
                            meta.setLabel(label);
                            meta.save(sessionPath);
                        }
                    } catch (Throwable e) {
                        LOG.warn("[WebGate] Failed to generate label for session {}: {}", sessionId, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 执行 Agent 流式任务。
     *
     * <p>通过 {@link WebStreamBuilder} 构建 ReAct Agent 的响应流，
     * 订阅流数据并通过 {@link #emitToClient} 逐条推送至前端。
     * 同时将 RxJava {@link Disposable} 保存到会话属性中，以支持 {@link #interruptSession} 中断。</p>
     *
     * @param session      Agent 会话实例
     * @param sessionCwd   会话当前工作目录
     * @param prompt       用户输入的 Prompt（为 null 时表示 HITL 恢复等无需新 Prompt 的场景）
     * @param selectedModel 用户选择的 AI 模型标识
     * @param agentName    指定 Agent 名称（可为 null，表示使用默认 Agent）
     */
    /**
     * 判断本批所有 HITL 挂起任务是否均已有决策。
     *
     * <p>批量场景下前端逐卡点击会发多次决策 POST，仅当无任何未决策项时
     * 才应恢复流，避免过早 resume 将未决策任务带走。</p>
     *
     * @param session 当前会话
     * @return 均已决策（或无挂起任务）返回 true
     */
    private boolean allHitlDecided(AgentSession session) {
        List<HITLTask> pending = HITL.getPendingTasks(session);
        if (pending == null || pending.isEmpty()) {
            return true;
        }
        for (HITLTask t : pending) {
            HITLDecision decision = HITL.getDecision(session, t);
            if (decision == null) {
                return false;
            }
        }
        return true;
    }

    private void performAgentTaskAsync(AgentSession session, String sessionCwd, Prompt prompt, String selectedModel, String agentName) {
        String sessionId = session.getSessionId();

        if (selectedModel != null) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
        } else {
            selectedModel = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
        }

        ChatModel chatModel = engine.getModelOrDefInstance(selectedModel);
        ReActAgent agent = engine.getAgentOrMain(agentName);

        // 新开流前重置，避免上一轮 streamDoneSent 挡住本轮 done
        resetStreamDoneSent(session);

        // 提前注册 CompositeDisposable：interruptSession 在 subscribe 返回前到达时
        // composite.dispose() 会在 composite.add(disposable) 时立即 dispose 新成员，消除注册窗口竞态
        Disposable.Composite composite = (Disposable.Composite)session.attrs().computeIfAbsent("disposable", k->Disposables.composite());

        Disposable disposable = streamBuilder.buildStreamFlux(session, agent, chatModel, sessionCwd, prompt)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(line -> {
                    emitToClient(sessionId, line);
                })
                .doOnError(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    emitToClient(sessionId, WebEvent.ofError(e));
                })
                .doFinally(s -> {
                    session.attrs().remove("disposable");  // 正常完成时清理

                    // 流级终态只发一次（含 dispose / 正常 complete / error）
                    emitDoneOnce(session);
                })
                .subscribe();

        // add 到 composite：若 composite 已被 dispose()（interrupt 先到达），会立即 dispose 该 disposable
        composite.add(disposable);

    }

    /**
     * 执行 Agent 流式任务。
     *
     * <p>通过 {@link WebStreamBuilder} 构建 ReAct Agent 的响应流，
     * 订阅流数据并通过 {@link #emitToClient} 逐条推送至前端。
     * 同时将 RxJava {@link Disposable} 保存到会话属性中，以支持 {@link #interruptSession} 中断。</p>
     *
     * @param session      Agent 会话实例
     * @param sessionCwd   会话当前工作目录
     * @param prompt       用户输入的 Prompt（为 null 时表示 HITL 恢复等无需新 Prompt 的场景）
     * @param selectedModel 用户选择的 AI 模型标识
     * @param agentName    指定 Agent 名称（可为 null，表示使用默认 Agent）
     */
    private String performAgentTaskSync(AgentSession session, String sessionCwd, Prompt prompt, String selectedModel, String agentName) {
        String sessionId = session.getSessionId();

        if (selectedModel != null) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
        } else {
            selectedModel = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
        }

        ChatModel chatModel = engine.getModelOrDefInstance(selectedModel);
        ReActAgent agent = engine.getAgentOrMain(agentName);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        AtomicReference<String> finalAnswerRef = new AtomicReference<>("");

        // 新开流前重置，避免上一轮 streamDoneSent 挡住本轮 done
        resetStreamDoneSent(session);

        // 提前注册 CompositeDisposable，消除注册窗口竞态（同 performAgentTaskAsync）
        Disposable.Composite composite = (Disposable.Composite)session.attrs().computeIfAbsent("disposable", k->Disposables.composite());

        Disposable disposable = streamBuilder.buildStreamFlux(session, agent, chatModel, sessionCwd, prompt)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(line -> {
                    emitToClient(sessionId, line);

                    if (WebEventNames.SYSTEM_TRACE.equals(line.getEvent()) && line.getPayload() instanceof SystemTracePayload) {
                        SystemTracePayload tracePayload = (SystemTracePayload) line.getPayload();
                        if (Assert.isNotEmpty(tracePayload.getFinalAnswer())) {
                            finalAnswerRef.set(tracePayload.getFinalAnswer());
                        }
                    }
                })
                .doOnError(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    emitToClient(sessionId, WebEvent.ofError(e));
                })
                .doFinally(s -> {
                    session.attrs().remove("disposable");

                    // 流级终态只发一次（含 dispose / 正常 complete / error）
                    emitDoneOnce(session);
                    countDownLatch.countDown();
                })
                .subscribe();

        composite.add(disposable);
        RunUtil.runAndTry(countDownLatch::await);
        return finalAnswerRef.get();
    }

    /**
     * 尝试将用户输入解析为斜杠命令并执行。
     *
     * <p>解析输入字符串中的命令名和参数，查找已注册的 {@link Command} 并执行。
     * 若命令执行后产生非 Agent 任务结果，会通过 WebSocket 推送命令输出；
     * 若为 rewind 命令，会发送特殊的回退事件通知前端删除历史 DOM。</p>
     *
     * @param session      Agent 会话实例
     * @param sessionCwd   会话当前工作目录
     * @param input        用户输入的完整文本（以 "/" 开头）
     * @param selectedModel 用户选择的 AI 模型标识
     * @param agentName    指定 Agent 名称
     * @return true 表示输入已被识别为命令并执行，false 表示非命令输入
     * @throws Exception 命令执行过程中可能抛出的异常
     */
    private boolean isCommand(AgentSession session, String sessionCwd, String input, String selectedModel, String agentName) throws Exception {
        if (!input.startsWith("/")) {
            return false;
        }

        // 解析命令名和参数
        List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
        String cmdName = parts.get(0).toLowerCase();
        List<String> args = parts.size() > 1
                ? parts.subList(1, parts.size())
                : Collections.emptyList();

        // 查找命令
        Command command = engine.getCommandRegistry().find(cmdName);
        if (command == null) {
            return false;
        }

        // 构建 context（注入 agentTaskRunner 回调）
        WebCommandContext ctx = new WebCommandContext(session, engine, input, cmdName, args,
                (prompt, model) -> {
                    try {
                        if (model == null) {
                            model = selectedModel;
                        }

                        performAgentTaskAsync(session, sessionCwd, Prompt.of(prompt), model, agentName);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        // 执行命令
        command.execute(ctx);


        if (ctx.isAgentTask() == false) {
            // rewind 命令走特殊通道：发送 rewind 事件让前端同步删除 DOM
            if ("rewind".equals(cmdName)) {
                int rewindCount = 1;
                if (!args.isEmpty()) {
                    try {
                        rewindCount = Integer.parseInt(args.get(0));
                    } catch (NumberFormatException ignored) {
                    }
                }

                //加一条删掉自己发出的一条
        emitToClient(session.getSessionId(), WebEvent.ofRewind(rewindCount + 1));
            } else {
                final String text;
                if (ctx.getOutputBuffer().length() > 0) {
                    text = ctx.getOutputBuffer().toString();
                } else {
                    text = "命令执行完成";
                }

        emitToClient(session.getSessionId(), WebEvent.ofCommand(text));

                // 命令执行后通知所有绑定的 IM 通道（微信/飞书/钉钉等）
                streamBuilder.replyToBoundChannel(session.getSessionId(), text, true);
            }

        emitToClient(session.getSessionId(), WebEvent.ofDone());
        }

        return true;
    }


    /**
     * 判断指定会话是否有 AI 任务正在执行。
     *
     * <p>通过检查会话属性中保存的 {@link Disposable} 对象是否仍处于活跃状态来判断。</p>
     *
     * @param session Agent 会话实例
     * @return true 表示会话有正在执行的 AI 任务
     */
    private boolean isSessionBusy(AgentSession session) {
        Object slot = session.attrs().get("disposable");
        if (slot instanceof Disposable.Composite) {
            return !((Disposable.Composite) slot).isDisposed();
        }
        return false;
    }

    /**
     * 判断指定会话是否有 AI 任务正在执行（按 sessionId 查询）。
     *
     * <p>供 LoopScheduler 等外部组件在定时触发前判断会话是否繁忙，繁忙则跳过本次执行。
     * 会话不存在或查询异常时按非繁忙处理。</p>
     *
     * @param sessionId 会话标识
     * @return true 表示会话有正在执行的 AI 任务
     */
    public boolean isSessionBusy(String sessionId) {
        try {
            return isSessionBusy(engine.getSession(sessionId));
        } catch (Exception e) {
            LOG.warn("[WebGate] busy check failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 安全聊天输入入口。
     *
     * <p>在调用 {@link #onChatInput} 之前先检查会话是否繁忙（有 AI 任务正在执行），
     * 若繁忙则跳过本次输入并记录警告日志。用于 IM 回调等需要避免并发冲突的场景。</p>
     *
     * @param sessionId 会话标识
     * @param input     用户输入文本
     * @param source    调用来源标识（用于日志记录，如 "Feishu"），同时用于标记消息来源通道
     * @return true 表示输入已接受并进入处理流程；false 表示会话繁忙已跳过
     */
    public boolean safeChatInput(String sessionId, String input, String source) {
        try {
            AgentSession session = engine.getSession(sessionId);
            if (isSessionBusy(session)) {
                // 检查是否为暂停/中断命令：允许在任务执行中穿过忙碌检查
                if (input != null && input.startsWith("/")) {
                    List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
                    String cmdName = parts.get(0).toLowerCase();
                    if ("interrupt".equals(cmdName) || "exit".equals(cmdName)) {
        emitToClient(sessionId, WebEvent.ofUserInput(input, source));
                        onChatInput(sessionId, null, input, null, null, null, null, source);
                        return true;
                    }
                }

                LOG.warn("[WebGate] {} event skipped for session {}: task in progress", source, sessionId);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("[WebGate] {} event check failed for session {}: {}", source, sessionId, e.getMessage());
            return false;
        }

        // 先推送用户消息到前端，确保对话记录中显示用户侧消息
        emitToClient(sessionId, WebEvent.ofUserInput(input, source));

        onChatInput(sessionId, null, input, null, null, null, null, source);
        return true;
    }


    /**
     * Loop 专用：安全聊天输入入口，无限等待捕获本轮响应文本。
     *
     * <p>
     * 适用于可能长时间执行的 Loop goal 任务。
     * 该方法仍会向前端推送完整流式消息，同时等待响应流结束。
     *
     * @param sessionId  会话标识
     * @param input      用户输入文本
     * @param source     调用来源标识
     * @return 捕获到的 AI 文本；会话繁忙或无文本时返回 null
     */
    public String safeChatInputAndCaptureLoop(String sessionId, String input, String source) {
        AgentSession session;
        try {
            session = engine.getSession(sessionId);
            if (isSessionBusy(session)) {
                LOG.warn("[WebGate] {} event skipped for session {}: task in progress", source, sessionId);
                return null;
            }
        } catch (Throwable e) {
            LOG.warn("[WebGate] {} event check failed for session {}: {}", source, sessionId, e.getMessage());
            return null;
        }

        // Loop/Goal 异步 agent 流开始前重置前端的流状态（_streamClosed → false）
        emitToClient(sessionId, WebEvent.ofResetStream());
        emitToClient(sessionId, WebEvent.ofUserInput(input, source));

        String agentName = null;
        String currentInput = input;
        if (currentInput != null && currentInput.startsWith("@")) {
            int agentNameIdx = currentInput.indexOf(" ");
            if (agentNameIdx > 0) {
                agentName = currentInput.substring(1, agentNameIdx);
                if (engine.getAgentManager().hasAgent(agentName)) {
                    currentInput = currentInput.substring(agentNameIdx + 1);
                }
            }
        }

        ChatMessage chatMessage = ChatMessage.ofUser(currentInput).addMetadata("source", source);
        return performAgentTaskSync(session, null, Prompt.of(chatMessage), null, agentName);
    }


    // ═══════════════════════════════════════════════════════════════
    //  工具方法 —— 附件类型判断与 MIME 映射
    // ═══════════════════════════════════════════════════════════════

    /** 支持的图片扩展名集合 */
    private static final Set<String> IMAGE_EXTENSIONS = org.noear.solon.Utils.asSet(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg");

    /**
     * 判断附件是否为图片类型。
     *
     * @param ext             文件扩展名（含点号，如 ".png"）
     * @param attachmentsType 前端传递的附件类型标识（如 "image"）
     * @return true 表示该附件应作为图片处理
     */
    private static boolean isImageAttachment(String ext, String attachmentsType) {
        return "image".equals(attachmentsType) && IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 将文件扩展名映射为 MIME 类型。
     *
     * @param ext 文件扩展名（含点号，如 ".jpg"）
     * @return 对应的 MIME 类型字符串，未匹配时默认返回 "image/png"
     */
    private static String extensionToMime(String ext) {
        switch (ext) {
            case ".jpg":
            case ".jpeg":
                return "image/jpeg";
            case ".png":
                return "image/png";
            case ".gif":
                return "image/gif";
            case ".webp":
                return "image/webp";
            case ".bmp":
                return "image/bmp";
            case ".svg":
                return "image/svg+xml";
            default:
                return "image/png";
        }
    }

    /**
     * 构建附件元数据 JSON 数组字符串（用于存入 ndjson metadata.attachments）。
     *
     * @param imageFileNames 图片文件名列表（已校验安全的文件名）
     * @return JSON 数组字符串，如 [{"name":"photo.jpg","type":"image"}]；列表为空则返回 null
     */
    private static String buildAttachmentMeta(List<String> imageFileNames) {
        if (imageFileNames == null || imageFileNames.isEmpty()) {
            return null;
        }
        // 手动构建 JSON 避免依赖 ONode 序列化细节
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < imageFileNames.size(); i++) {
            if (i > 0) sb.append(",");
            String name = imageFileNames.get(i);
            // 简单转义双引号和反斜杠（文件名已校验无 / \ ..）
            String escaped = name.replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("{\"name\":\"").append(escaped).append("\",\"type\":\"image\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  会话中断支持
    // ═══════════════════════════════════════════════════════════════

    /**
     * 中断指定会话的当前 AI 任务。
     *
     * <p>仅当会话存在活跃流（disposable 未 dispose）时发出取消语义；
     * 无活跃流时不写历史、不推送取消 error/trace，仅在尚未发过 done 时兜底
     * {@link #emitDoneOnce}，避免双击 Stop 或自然结束后仍刷「用户已取消任务」。</p>
     *
     * <p>有活跃流时同线程保证包序：error → 可选 trace → done → dispose。
     * dispose 触发的 {@code doFinally} 中 {@link #emitDoneOnce} 因 CAS 跳过，不会双发 done。</p>
     *
     * @param sessionId 待中断的会话标识
     */
    public void interruptSession(String sessionId) {
        try {
            AgentSession session = engine.getSession(sessionId);
            Object slot = session.attrs().remove("disposable");

            // 无活跃流：不发取消语义；若尚未发 done 则兜底，便于前端收尾
            if (!(slot instanceof Disposable.Composite)) {
                boolean sent = emitDoneOnce(session);
                if (sent) {
                    LOG.info("[WebGate] Session {} interrupt ignored (no active stream), emitted fallback done", sessionId);
                } else {
                    LOG.info("[WebGate] Session {} interrupt ignored (no active stream)", sessionId);
                }
                return;
            }

            Disposable.Composite composite = (Disposable.Composite) slot;
            if (composite.isDisposed()) {
                boolean sent = emitDoneOnce(session);
                LOG.info("[WebGate] Session {} interrupt ignored (already disposed), fallback done={}", sessionId, sent);
                return;
            }

            // 1) 取消语义：error + 可选 final/trace
            session.addMessage(ChatMessage.ofAssistant("用户已取消任务."));
        emitToClient(sessionId, WebEvent.ofError("用户已取消任务."));

            ReActTrace trace = session.getContext().getAs("__main");
            if (trace != null) {
                Long totalTokens = (trace.getMetrics() != null) ? trace.getMetrics().getTotalTokens() : 0L;
            long elapsedSeconds = 0L;
            if (trace.getBeginTimeMs() > 0) {
                elapsedSeconds = (System.currentTimeMillis() - trace.getBeginTimeMs()) / 1000;
            }
            emitToClient(sessionId, WebEvent.ofTrace(null, totalTokens, elapsedSeconds, "用户已取消任务."));
            }

            // 2) 同线程先发 done，再 dispose；doFinally 中 emitDoneOnce 因 CAS 跳过
            emitDoneOnce(session);
            composite.dispose();
            LOG.info("[WebGate] Session {} interrupted", sessionId);
        } catch (Exception e) {
            LOG.error("[WebGate] Interrupt failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}