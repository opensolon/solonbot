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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.agent.react.intercept.ContextSizeEvent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLPendingEvent;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.*;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.agent.TaskWrapEvent;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.cli.TodoTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.wechat.WeChatLink;
import org.noear.solon.codecli.command.builtin.GoalTalent;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

/**
 * Web 流式响应构建器
 *
 * <p><b>职责说明：</b>将 ReAct Agent 的流式输出（chunk）逐条映射为 {@link WebChunk}，
 * 构建可在 Web 端消费的响应式数据流（{@link reactor.core.publisher.Flux}）。</p>
 *
 * <p><b>核心机制：</b>
 * <ul>
 *   <li>基于 ReAct 流式 chunk 类型分发：ReasonDeltaEvent → 思维链/文本输出；
 *       ReasonEndEvent → 思考轮次输出 + IM 通道同步转发；
 *       ToolCallEndEvent → 工具调用结果；
 *       RunEndEvent → 最终汇总（含异常）。</li>
 *   <li>IM 通道同步转发：在处理 ReasonEndEvent 和 RunEndEvent 时，将内容同步推送到
 *       所有已绑定的 IM 通道（微信、飞书、钉钉等），实现 Web 端与 IM 端双路输出。</li>
 *   <li>HITL（人机交互循环）支持：流内消费 {@link HITLPendingEvent}，一批挂起任务逐个映射为
 *       独立的 HITL WebChunk（各带 callId），暂停流等待人工逐卡审批；
 *       并保留「未主动 push 过则按 pending 列表补发」的降级兜底。</li>
 * </ul></p>
 *
 * <p><b>架构位置：</b>位于 portal/web 层，是 Agent 后端与 Web 前端之间的流式适配器；
 * 上游对接 {@link org.noear.solon.ai.agent.react.ReActAgent} 的 stream 输出，
 * 下游输出面向 Web SSE / WebSocket 的 {@link WebChunk} 序列。</p>
 *
 * @author noear 2026/4/23 created
 */
public class WebStreamBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(WebStreamBuilder.class);

    /** 会话上下文标记：本流是否已主动 push 过 HITLPendingEvent，供尾部 concatWith 判断是否需要降级兜底补发。 */
    private static final String HITL_PENDING_PUSHED = "__web_hitl_pending_pushed";

    /**
     * 任务执行引擎，用于判断当前引擎名称与 chunk 中代理名称的归属关系
     */
    private final HarnessEngine engine;

    /**
     * IM 通道路由表：所有注册的 IM 通道（微信、飞书、钉钉等）
     */
    private final List<Channel> imLinks = new ArrayList<>();

    /**
     * 注册 IM 通道（向后兼容：支持 WeChatLink 直接注册）
     */
    public WebStreamBuilder bind(WeChatLink weChatLink) {
        this.imLinks.add(weChatLink);
        return this;
    }

    /**
     * 注册 IM 通道（通用接口）
     */
    public WebStreamBuilder bind(Channel link) {
        this.imLinks.add(link);
        return this;
    }

    /**
     * 获取微信通道（向后兼容）
     */
    public WeChatLink getWeChatLink() {
        for (Channel link : imLinks) {
            if (link instanceof WeChatLink) {
                return (WeChatLink) link;
            }
        }
        return null;
    }


    /**
     * 构造函数
     *
     * @param engine 任务执行引擎实例，用于后续判断 chunk 所属的引擎/代理层级
     */
    public WebStreamBuilder(HarnessEngine engine) {
        this.engine = engine;
    }

    /**
     * 构建流式响应管线
     *
     * @param session    Agent 会话，承载会话状态、属性及 HITL 上下文
     * @param agent      ReAct Agent 实例，提供流式推理能力
     * @param chatModel  聊天模型，用于配置 Agent 的底层模型调用
     * @param sessionCwd 当前会话的工作目录，作为工具上下文注入
     * @param prompt     用户提示词；为 null 时使用空提示，为 "/resume" 时重置为空提示
     * @return 映射后的 {@link WebChunk} 响应式流
     */
    public Flux<WebChunk> buildStreamFlux(AgentSession session, ReActAgent agent, ChatModel chatModel, String sessionCwd, Prompt prompt) {
        if (prompt == null) {
            prompt = Prompt.of();
        }

        if ("/resume".equals(prompt.getUserContent())) {
            prompt = Prompt.of();
        }

        //记录最新的选择
        session.attrs().put("_agent_selected_tmp", agent.name());

        // 会话级推理水平：从 context 读取并注入 Prompt + options
        // auto 时不注入 effort，交给模型 defaultOptions / Agent Builder / 供应商
        String sessionEffort = ReasoningSupportUtil.getSessionEffort(session);
        String sessionThinkingMode = ReasoningSupportUtil.getSessionThinkingMode(session);
        ReasoningSupportUtil.ModelCapability cap = null;
        try {
            ChatConfig fullConfig = null;
            if (chatModel != null && chatModel.getConfig() != null) {
                // 1) 引擎精确查找（含 defaultOptions）
                String key = chatModel.getConfig().getNameOrModel();
                if (Assert.isNotEmpty(key)) {
                    fullConfig = engine.getModelOrNil(key);
                    if (fullConfig == null) {
                        fullConfig = ReasoningSupportUtil.findEngineConfig(engine.getModels(), key);
                    }
                }
                if (fullConfig == null && Assert.isNotEmpty(chatModel.getConfig().getModel())) {
                    fullConfig = engine.getModelOrNil(chatModel.getConfig().getModel());
                    if (fullConfig == null) {
                        fullConfig = ReasoningSupportUtil.findEngineConfig(
                                engine.getModels(), chatModel.getConfig().getModel());
                    }
                }
                if (fullConfig == null && Assert.isNotEmpty(chatModel.getConfig().getName())) {
                    fullConfig = engine.getModelOrNil(chatModel.getConfig().getName());
                }
            }
            if (fullConfig != null) {
                cap = ReasoningSupportUtil.resolveCapability(fullConfig);
            } else if (chatModel != null && chatModel.getConfig() != null) {
                // 2) 回退：name/model/standard 启发式
                cap = ReasoningSupportUtil.resolveCapability(
                        chatModel.getConfig().getName(),
                        chatModel.getConfig().getModel(),
                        chatModel.getConfig().getStandardOrProvider(),
                        null);
            }
        } catch (Throwable ignored) {
        }
        final String effectiveEffort = ReasoningSupportUtil.resolveEffectiveEffort(
                null, sessionEffort, cap, false);
        ReasoningSupportUtil.applyToPrompt(prompt, sessionThinkingMode, effectiveEffort);

        return agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.chatModel(chatModel);
                    ReasoningSupportUtil.applyToOptions(o, sessionThinkingMode, effectiveEffort);

                    if (Assert.isNotEmpty(sessionCwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwd);
                    }
                })
                .stream()
                .flatMap(event -> {
                    // 子代理任务包装解包：TaskWrapEvent 携带 taskAgentName/isMultitask
                    String runId = null;
                    String taskAgentName = null;
                    String taskId = null;
                    String taskDescription = null;
                    boolean isMultitask = false;
                    if (event instanceof TaskWrapEvent) {
                        TaskWrapEvent twc = (TaskWrapEvent) event;
                        if (twc.getRealEvent() instanceof ContextSizeEvent ||
                                twc.getRealEvent() instanceof ToolCallStartEvent ||
                                twc.getRealEvent() instanceof ToolCallEndEvent ||
                                twc.getRealEvent() instanceof ReasonDeltaEvent ||
                                twc.getRealEvent() instanceof ReasonEndEvent ||
                                twc.getRealEvent() instanceof RunEndEvent) {
                            // 解包子代理包装：透传父 run / task 元信息
                            runId = twc.getParentRunId();
                            taskId = twc.getTaskId();
                            taskAgentName = twc.getTaskAgentName();
                            taskDescription = twc.getTaskDescription();
                            isMultitask = twc.isMultitask();
                            event = twc.getRealEvent();

                            if (event instanceof RunEndEvent) {
                                // 子代理 ReAct 结束：发 task_done，让前端立刻结算该 task-group
                                // （主流转 done 仍会 finalize 兜底，但并行任务不必互相等待）
                                WebChunk taskDoneEvent = onTaskDoneEvent((RunEndEvent) event, runId, taskId,
                                        taskAgentName, taskDescription);

                                return Flux.just(taskDoneEvent);
                            }

                        } else {
                            return Flux.empty();
                        }
                    }

                    WebChunk webChunk = null;
                    if (event instanceof RunStartEvent) {
                        //任务运行开始
                    } else if (event instanceof ContextSizeEvent) {
                        webChunk = onContextSizeEvent(chatModel, (ContextSizeEvent) event);
                    } else if (event instanceof ReasonStartEvent) {
                        //思考开始
                    } else if (event instanceof ReasonDeltaEvent) {
                        webChunk = onReasonDeltaEvent((ReasonDeltaEvent) event, taskAgentName);
                    } else if (event instanceof ReasonEndEvent) {
                        //思考结束
                        webChunk = onReasonEndEvent(session, (ReasonEndEvent) event, taskAgentName, isMultitask);
                    } else if (event instanceof HITLPendingEvent) {
                        // HITL 挂起：一个上游 chunk 携带整批挂起任务，逐个映射为 hitl WebChunk
                        // （批量多卡，前端按 callId 各自渲染审批卡片）
                        List<WebChunk> hitlChunks = onHITLPendingEvent(session, (HITLPendingEvent) event);
                        if (!hitlChunks.isEmpty()) {
                            // 标记：本流已主动 push 过 pending，尾部 concatWith 不再兜底补发
                            session.getContext().put(HITL_PENDING_PUSHED, Boolean.TRUE);
                        }
                        // 直接返回批量结果，绕开尾部单值收敛逻辑
                        return Flux.fromIterable(hitlChunks);
                    } else if (event instanceof ToolCallStartEvent) {
                        //工具调用开始
                        webChunk = onToolCallStartEvent((ToolCallStartEvent) event, taskAgentName);
                    } else if (event instanceof ToolCallEndEvent) {
                        //工具调用结束
                        webChunk = onToolCallEndEvent((ToolCallEndEvent) event, taskAgentName);
                    } else if (event instanceof RunEndEvent) {
                        //运行结束
                        webChunk = onRunEndEvent(session, (RunEndEvent) event);
                    }

                    if (webChunk == null || webChunk == WebChunk.EMPTY) {
                        return Flux.empty();
                    } else {
                        if (runId != null) {
                            webChunk.setRunId(runId);
                        } else {
                            webChunk.setRunId(event.getRunId());
                        }

                        if (taskAgentName != null) {
                            webChunk.setAgentName(taskAgentName);
                        }

                        if (taskId != null) {
                            webChunk.setTaskId(taskId);
                            webChunk.setTaskDescription(taskDescription);
                        }
                        return Flux.just(webChunk);
                    }
                })
                .filter(WebChunk::isNotEmpty)
                .onErrorResume(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    List<WebChunk> chunkList = new ArrayList<>();

                    WebChunk errorChunk = WebChunk.ofError(e);
                    ReActTrace trace = session.getContext().getAs("__main");
                    if (trace != null) {
                        this.onRunEndEvent(session, trace, true, errorChunk.getText());
                    }

                    return Flux.fromIterable(chunkList);
                })
                .concatWith(Flux.defer(() -> {
                    // 降级兜底：仅当本流从未主动 push 过 HITLPendingEvent（如 trace 无 streamSink 的边界），
                    // 且仍存在挂起任务时，才按 pending 列表补发，避免与主路径重复弹卡。
                    boolean pushed = Boolean.TRUE.equals(session.getContext().getAs(HITL_PENDING_PUSHED));
                    session.getContext().remove(HITL_PENDING_PUSHED);
                    if (!pushed && HITL.isHitl(session)) {
                        List<WebChunk> hitlChunks = new ArrayList<>();
                        for (HITLTask task : HITL.getPendingTasks(session)) {
                            hitlChunks.add(buildHitlChunk(session, task));
                        }
                        return Flux.fromIterable(hitlChunks);
                    }

                    return Flux.empty();
                }));
    }


    public WebChunk onContextSizeEvent(ChatModel chatModel, ContextSizeEvent chunk) {
        WebChunk wc = new WebChunk();
        wc.setType("context_size");
        wc.setSessionId(chunk.getSession().getSessionId());
        wc.setTotalTokens((long) chunk.getTokenCount());
        wc.setText(String.valueOf(chunk.getMessageCount()));

        long contextLength = chatModel.getConfig().getContextLength();
        if (contextLength == 0) {
            contextLength = 128_000; //默认
        }

        Map<String, Object> args = new HashMap<>();
        args.put("contextLength", contextLength);

        if (chunk.isCompressed()) {
            args.put("compressed", true);
            args.put("beforeTokenCount", chunk.getBeforeTokenCount());
            args.put("afterTokenCount", chunk.getAfterTokenCount());
            args.put("beforeMessageCount", chunk.getBeforeMessageCount());
            args.put("afterMessageCount", chunk.getAfterMessageCount());
        }
        wc.setArgs(args);
        wc.setCreatedAt(java.time.Instant.now().toEpochMilli());
        return wc;
    }

    /**
     * 处理推理阶段的 chunk
     *
     * @param chunk 推理阶段的 chunk 数据
     * @return 映射后的 WebChunk，或 {@link WebChunk#EMPTY}
     */
    private WebChunk onReasonDeltaEvent(ReasonDeltaEvent chunk, String taskAgentName) {
        if (!chunk.isToolCalls() && Assert.isNotEmpty(chunk.getContent())) {
            WebChunk wc;
            if (chunk.getMessage().isThinking()) {
                wc = WebChunk.ofReason(chunk.getContent());
            } else {
                wc = WebChunk.ofText(chunk.getContent());
            }

            wc.setReasonId(chunk.getReasonId());

            // 子代理标记：下游前端据此识别 chunk 归属
            if (taskAgentName != null) {
                wc.setAgentName(taskAgentName);
            }

            return wc;
        }

        return WebChunk.EMPTY;
    }


    /**
     * 处理 HITL 挂起 chunk（{@link HITLPendingEvent}）。
     *
     * <p>一个上游 chunk 携带整批挂起任务（多个敏感工具同批拦截时），此处逐个
     * 映射为 hitl {@link WebChunk}，每张卡携带独立的 callId（= HITLTask.callUuid），
     * 供前端各自渲染审批卡片并精确回传决策。</p>
     *
     * @param session 当前会话
     * @param chunk   HITL 挂起 chunk
     * @return 批量 hitl WebChunk（可能为空列表）
     */
    private List<WebChunk> onHITLPendingEvent(AgentSession session, HITLPendingEvent chunk) {
        List<WebChunk> result = new ArrayList<>();
        if (chunk.getPendingTasks() == null) {
            return result;
        }
        for (HITLTask task : chunk.getPendingTasks()) {
            result.add(buildHitlChunk(session, task));
        }
        return result;
    }

    /**
     * 将单个 {@link HITLTask} 构造为 hitl {@link WebChunk}。
     *
     * <p>携带 toolName / toolTitle / args / command / callId(callUuid) / comment，
     * command 仅对 bash 等携带 {@code command} 参数的工具提取，其余工具靠 args 展示。</p>
     *
     * @param session 当前会话
     * @param task    挂起任务
     * @return hitl WebChunk
     */
    private WebChunk buildHitlChunk(AgentSession session, HITLTask task) {
        String toolName = task.getToolName();
        Map<String, Object> args = task.getArgs() != null
                ? new LinkedHashMap<>(task.getArgs())
                : null;

        // command 仅当 args 中存在 command 字段时提取（不再硬编码限定 bash）
        String command = (args != null && args.get("command") != null)
                ? String.valueOf(args.get("command"))
                : null;

        WebChunk wc = WebChunk.ofHitl(toolName, toolName, args, command, task.getCallUuid(), task.getComment());
        wc.setSessionId(session.getSessionId());
        return wc;
    }


    /**
     * 处理工具调用开始阶段的 chunk（来源引擎 ToolCallStartEvent）
     *
     * <p>在工具实际执行前发送 action_start，让前端提前渲染 loading 状态的工具卡片骨架，
     * 待后续 {@link #onToolCallEndEvent} 的结果到达时复用同一卡片填充并转完成态。
     * 过滤规则与 {@link #onToolCallEndEvent} 保持一致，避免建卡后无对应结果填充。</p>
     *
     * @param event 工具调用开始的 chunk 数据
     * @return 映射后的 WebChunk（含工具名与参数），或 {@link WebChunk#EMPTY}（内部工具或无名称时）
     */
    private WebChunk onToolCallStartEvent(ToolCallStartEvent event, String taskAgentName) {
        if (Assert.isEmpty(event.getToolName())) {
            return WebChunk.EMPTY;
        }

        if (TaskTalent.TOOL_MULTITASK.equals(event.getToolName()) ||
                TaskTalent.TOOL_TASK.equals(event.getToolName()) ||
                MemoryTalent.isMemoryTool(event.getToolName()) ||
                GoalTalent.isGoalTool(event.getToolName())) {
            return WebChunk.EMPTY;
        }

        // todowrite 的展示走专用通道，由 ToolCallEndEvent 携带完整 todos 渲染，开始阶段不提前建卡
        if (TodoTalent.TOOL_TODOWRITE.equals(event.getToolName())) {
            return WebChunk.EMPTY;
        }

        // toolName 恒为裸名（供前端识别/查表）；toolTitle 为显示名（子代理时加 agentName 前缀）
        String toolName = event.getToolName();
        String toolTitle;
        if (engine.getName().equals(event.getAgentName())) {
            toolTitle = toolName;
        } else {
            toolTitle = event.getAgentName() + "/" + toolName;
        }

        Map<String, Object> args = event.getArgs() != null
                ? new LinkedHashMap<>(event.getArgs())
                : null;

        // edit 开始阶段即重建 diff，让 loading 骨架卡也能预览改动
        fillEditDiff(args);

        WebChunk wc = WebChunk.ofToolCallStart(toolName, toolTitle, args);
        wc.setReasonId(event.getReasonId());

        // 传入 callId 供前端精确配对工具卡片
        wc.setCallId(event.getCallId());

        // 子代理标记
        if (taskAgentName != null) {
            wc.setAgentName(taskAgentName);
        }

        return wc;
    }


    /**
     * 处理工具调用完成阶段的 chunk
     *
     * <p>过滤掉内部工具（多任务调度 task/multitask、记忆工具）后，
     * 将工具调用结果包装为 {@link WebChunk}，并附带工具名称和参数信息：
     * <ul>
     *   <li>工具名称：若属于当前引擎则使用短名，否则使用 {@code agentName/toolName} 全路径</li>
     *   <li>特殊处理 {@code todowrite} 工具：将 todos 参数内容设为文本</li>
     * </ul></p>
     *
     * @param event 工具调用结束的 chunk 数据
     * @return 映射后的 WebChunk（含工具信息），或 {@link WebChunk#EMPTY}（内部工具或无名称时）
     */
    private WebChunk onToolCallEndEvent(ToolCallEndEvent event, String taskAgentName) {
        if (event.getError() != null) {
            return WebChunk.EMPTY;
        }

        // todowrite 完成时，前端通过 action chunk 的 toolName='todowrite' 自动刷新任务面板

        if (Assert.isNotEmpty(event.getToolName())) {
            if (TaskTalent.TOOL_MULTITASK.equals(event.getToolName()) ||
                    TaskTalent.TOOL_TASK.equals(event.getToolName()) ||
                    MemoryTalent.isMemoryTool(event.getToolName()) ||
                    GoalTalent.isGoalTool(event.getToolName())) {
                return WebChunk.EMPTY;
            }

            WebChunk webChunk = WebChunk.ofToolCallEnd(event.getContent());

            if (Assert.isNotEmpty(event.getToolName())) {
                webChunk.setArgs(new LinkedHashMap<>(event.getArgs()));

                // toolName 恒为裸名（供前端识别/查表）；toolTitle 为显示名（子代理时加 agentName 前缀）
                webChunk.setToolName(event.getToolName());
                if (engine.getName().equals(event.getAgentName())) {
                    webChunk.setToolTitle(event.getToolName());
                } else {
                    webChunk.setToolTitle(event.getAgentName() + "/" + event.getToolName());
                }

                if (TodoTalent.TOOL_TODOWRITE.equals(event.getToolName())) {
                    String todos = (String) event.getArgs().get(TodoTalent.PARAM_TODOS);

                    if (Assert.isNotEmpty(todos)) {
                        webChunk.setText(todos);
                        webChunk.getArgs().remove(TodoTalent.PARAM_TODOS);
                    }
                }

                if (TerminalTalent.TOOL_WRITE.equals(event.getToolName())) {
                    String content = (String) event.getArgs().get(TerminalTalent.PARAM_CONTENT);

                    if (Assert.isNotEmpty(content)) {
                        webChunk.setText(content);
                        webChunk.getArgs().remove(TerminalTalent.PARAM_CONTENT);
                    }
                }

                // edit：入参为结构化 edits 列表（无 diff 字段），在此由结构化参数重建 git diff 文本写入 args.diff，
                // text 保留工具真实返回（成功提示/错误信息）作为「输出」，由前端 edit 渲染器两段式展示。
                fillEditDiff(webChunk.getArgs());
            }

            webChunk.setReasonId(event.getReasonId());

            // 传入 callId 供前端精确配对工具卡片
            webChunk.setCallId(event.getCallId());

            // 子代理标记
            if (taskAgentName != null) {
                webChunk.setAgentName(taskAgentName);
            }

            return webChunk;
        }

        return WebChunk.EMPTY;
    }

    /**
     * 将 edit 工具的结构化 edits 列表转换为标准 git diff 文本，写入 {@code args.diff}，供前端 edit 渲染器着色展示。
     *
     * <p>edit 工具入参为 edits 列表（每项含 old_str / old_StrStartLine / new_str / replace_all），本身不含 diff 文本。
     * 前端渲染器依赖 {@code args.diff} 渲染，故在此由结构化参数重建 git diff：每个编辑操作生成一个 hunk，
     * old_str 各行打 {@code -}、new_str 各行打 {@code +}，old_StrStartLine 提供 {@code @@} 行号锚点（缺失时退化为 0）。
     * 转换后移除原始 edits，避免工具卡头部回显冗余结构。</p>
     *
     * @param args 工具参数（可为 null）
     */
    @SuppressWarnings("unchecked")
    private void fillEditDiff(Map<String, Object> args) {
        if (args == null || !(args.get(TerminalTalent.PARAM_EDITS) instanceof List)) {
            return;
        }

        List<?> edits = (List<?>) args.get(TerminalTalent.PARAM_EDITS);
        if (edits.isEmpty()) {
            return;
        }

        StringBuilder diff = new StringBuilder();
        for (Object item : edits) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> edit = (Map<String, Object>) item;

            int startLine = asInt(edit.get("old_StrStartLine"), 0);
            List<String> oldLines = splitLines(asString(edit.get("old_str")));
            List<String> newLines = splitLines(asString(edit.get("new_str")));

            diff.append("@@ -").append(startLine).append(',').append(oldLines.size())
                    .append(" +").append(startLine).append(',').append(newLines.size())
                    .append(" @@\n");

            for (String line : oldLines) {
                diff.append('-').append(line).append('\n');
            }
            for (String line : newLines) {
                diff.append('+').append(line).append('\n');
            }
        }

        if (diff.length() > 0) {
            args.put("diff", diff.toString());
            args.remove(TerminalTalent.PARAM_EDITS);
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static List<String> splitLines(String s) {
        if (s == null || s.isEmpty()) {
            return Collections.emptyList();
        }
        // 统一换行符并去掉末尾换行，避免 split 产生多余空元素
        String normalized = s.replace("\r\n", "\n").replace('\r', '\n');
        while (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(normalized.split("\n", -1));
    }

    /**
     * 处理思考轮次（Thought）阶段的 chunk
     *
     * <p>核心职责：
     * <ol>
     *   <li><b>IM 通道转发</b>：根据本轮是否有工具调用、是否为源代理的最终结果，
     *       以不同的标记（isFinal）将内容推送到所有已绑定的 IM 通道。</li>
     *   <li><b>Web 输出</b>：仅在多任务并行（multitask）标记存在时，才向 Web 端输出文本 chunk；
     *       普通单轮 Thought 不输出到 Web（避免与 ReasonDeltaEvent 重复）。</li>
     * </ol></p>
     *
     * @param session Agent 会话，用于获取会话ID和已选择的代理名称
     * @param event   思考轮次的 chunk 数据，包含助手消息和追踪信息
     * @return 映射后的 WebChunk（多任务并行时有内容），或 {@link WebChunk#EMPTY}
     */
    private WebChunk onReasonEndEvent(AgentSession session, ReasonEndEvent event, String taskAgentName, boolean isMultitask) {
        ReActTrace trace = event.getTrace();
        String sessionId = session.getSessionId();
        String resultContent = event.getAssistantMessage().getResultContent();

        Metrics metrics = trace.getMetrics();

        if (Assert.isNotEmpty(resultContent)) {
            // 向所有已绑定的 IM 通道回复
            if (event.isToolCalls()) {
                // 说明是过程
                replyToBoundChannel(sessionId, resultContent, false);
            } else {
                // 说明是结果
                String agentSelectedTmp = (String) session.attrs().get("_agent_selected_tmp");

                if (event.getTrace().getAgentName().equals(agentSelectedTmp)) {
                    // 说明是源代理（说明是最终结果）
                    //StringBuilder traceInfo = getTraceInfo(thought.getTrace());
                    replyToBoundChannel(sessionId, resultContent, true);//+ traceInfo, true);
                } else {
                    // 说明是次代理
                    replyToBoundChannel(sessionId, resultContent, false);
                }
            }
        }

        // ★ 捕获真实 token 消耗，供 LoopScheduler 预算控制使用
        if (metrics != null) {
            session.attrs().put("_loop_last_total_tokens", metrics.getTotalTokens());

            int cacheRate = metrics.getCacheRate();
            if (cacheRate > 0) {
                WebChunk wc = new WebChunk();
                wc.setType("context_status");
                wc.setSessionId(sessionId);
                Map<String, Object> args = new HashMap<>();
                args.put("cacheRate", cacheRate);
                wc.setArgs(args);
                return wc;
            }
        }

        return WebChunk.EMPTY;
    }

    /**
     * 处理子代理任务结束（TaskWrapEvent 内层为 RunEndEvent）。
     *
     * <p>产出 {@code task_done} WebChunk，携带 taskId 与 status（done/error）。
     * 前端据此将对应 task-group 立即标为绿勾/红叉，不必等主会话整流转 done。
     * 异常时附带错误文本，供 task-group 内展示。</p>
     *
     * @param event           子代理最终 RunEndEvent
     * @param runId           父 runId（主会话 run）
     * @param taskId          子任务 id
     * @param taskAgentName   子代理名
     * @param taskDescription 子任务描述（task-group 标题）
     * @return task_done 类型 WebChunk
     */
    private WebChunk onTaskDoneEvent(RunEndEvent event, String runId, String taskId,
                                     String taskAgentName, String taskDescription) {
        boolean abnormal = event.isAbnormal();
        WebChunk wc = WebChunk.ofTaskDone(abnormal ? "error" : "done");

        if (runId != null) {
            wc.setRunId(runId);
        } else if (event.getRunId() != null) {
            wc.setRunId(event.getRunId());
        }
        if (taskId != null) {
            wc.setTaskId(taskId);
            wc.setTaskDescription(taskDescription);
        }
        if (taskAgentName != null) {
            wc.setAgentName(taskAgentName);
        }

        // 异常时把内容带给前端，写入 task-group 错误区；正常完成不重复推最终正文
        if (abnormal) {
            String errText = event.getContent();
            if (Assert.isNotEmpty(errText)) {
                errText = errText.replaceAll("(?s)<\\s*/?think\\s*>", "");
                wc.setText(errText);
            }
        }

        // 附带耗时，便于前端定格 task 总耗时（秒）
        try {
            ReActTrace trace = event.getTrace();
            if (trace != null) {
                long startMs = trace.getBeginTimeMs();
                if (startMs > 0) {
                    wc.setElapsedSeconds(Duration.ofMillis(System.currentTimeMillis() - startMs).getSeconds());
                }
            }
        } catch (Throwable ignored) {
        }

        return wc;
    }

    /**
     * 处理 ReAct 流的最终汇总 chunk
     *
     * <p>当 Agent 流结束时触发。若检测到异常终止，将异常内容连同追踪信息
     * 同步转发到所有已绑定的 IM 通道。无论是否异常，都将追踪信息
     * （模型名称、token 数、耗时）以结构化 trace 类型输出到 Web 端。</p>
     *
     * @param session Agent 会话，用于获取会话ID以进行 IM 通道转发
     * @param event   ReAct 最终汇总 chunk，包含追踪信息和可能的异常内容
     * @return 包含追踪信息的 trace 类型 WebChunk
     */
    private WebChunk onRunEndEvent(AgentSession session, RunEndEvent event) {
        return onRunEndEvent(session, event.getTrace(), event.isAbnormal(), event.getContent());
    }

    public WebChunk onRunEndEvent(AgentSession session, ReActTrace trace, boolean isAbnormal, String finalAnswer) {
        if (isAbnormal) {
            // 通知 IM 任务完成了
            replyToBoundChannel(session.getSessionId(), finalAnswer, true);
        }

        // 结构化 trace 数据，供前端独立渲染
        String model = trace.getOptions().getChatModel().getNameOrModel();
        Long totalTokens = trace.getMetrics() != null ? trace.getMetrics().getTotalTokens() : null;
        long startMs = trace.getBeginTimeMs();
        Long elapsedSeconds = startMs > 0 ? Duration.ofMillis(System.currentTimeMillis() - startMs).getSeconds() : null;

        // 最终答案全量文本（去除 think 标签，与正文输出保持一致），供前端复制使用
        if (finalAnswer != null) {
            finalAnswer = finalAnswer.replaceAll("(?s)<\\s*/?think\\s*>", "");
        }

        // ★ 捕获真实 token 消耗，供 LoopScheduler 预算控制使用
        if (totalTokens != null) {
            session.attrs().put("_loop_last_total_tokens", totalTokens);
        }

        return WebChunk.ofTrace(model, totalTokens, elapsedSeconds, finalAnswer);
    }

    /**
     * 向所有已绑定的 IM 通道发送回复
     */
    public void replyToBoundChannel(String sessionId, String text, boolean isFinal) {
        for (Channel link : imLinks) {
            if (link.isBound(sessionId)) {
                link.sendReply(sessionId, text, isFinal);
            }
        }
    }
}