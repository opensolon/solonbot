package org.noear.solon.codecli.portal.web.pipeline;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.ContextSizeEvent;
import org.noear.solon.ai.agent.react.intercept.HITLPendingEvent;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.*;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.TaskWrapEvent;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.SystemContextPayload;
import org.noear.solon.codecli.portal.web.event.payload.TaskDonePayload;
import org.noear.solon.codecli.portal.web.event.payload.ToolEndPayload;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将 ReAct Agent 底层事件映射为标准 SAEP 2.0 WebEvent 的纯函数映射器
 *
 * @author noear
 */
public class WebEventMapper {

    private final HarnessEngine engine;
    private final AgentSession session;
    private final ChatModel chatModel;

    public WebEventMapper(HarnessEngine engine, AgentSession session, ChatModel chatModel) {
        this.engine = engine;
        this.session = session;
        this.chatModel = chatModel;
    }

    public List<WebEvent<?>> mapEvent(AgentEvent event) {
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
                runId = twc.getParentRunId();
                taskId = twc.getTaskId();
                taskAgentName = twc.getTaskAgentName();
                taskDescription = twc.getTaskDescription();
                isMultitask = twc.isMultitask();
                event = twc.getRealEvent();
            }
        }

        List<WebEvent<?>> result = new ArrayList<>();

        if (event instanceof ContextSizeEvent) {
            WebEvent<?> evt = onContextSizeEvent(chatModel, (ContextSizeEvent) event);
            fillMeta(evt, session, runId, taskId, taskAgentName, event.getRunId());
            result.add(evt);
        } else if (event instanceof ReasonDeltaEvent) {
            WebEvent<?> evt = onReasonDeltaEvent((ReasonDeltaEvent) event, taskAgentName);
            fillMeta(evt, session, runId, taskId, taskAgentName, event.getRunId());
            result.add(evt);
        } else if (event instanceof HITLPendingEvent) {
            List<WebEvent<?>> hitlEvents = onHITLPendingEvent(session, (HITLPendingEvent) event);
            for (WebEvent<?> evt : hitlEvents) {
                fillMeta(evt, session, runId, taskId, taskAgentName, event.getRunId());
                result.add(evt);
            }
        } else if (event instanceof ToolCallStartEvent) {
            WebEvent<?> evt = onToolCallStartEvent((ToolCallStartEvent) event, taskAgentName);
            fillMeta(evt, session, runId, taskId, taskAgentName, event.getRunId());
            result.add(evt);
        } else if (event instanceof ToolCallEndEvent) {
            WebEvent<?> evt = onToolCallEndEvent((ToolCallEndEvent) event, taskAgentName);
            fillMeta(evt, session, runId, taskId, taskAgentName, event.getRunId());
            result.add(evt);
        } else if (event instanceof ReasonEndEvent) {
            WebEvent<?> evt = onReasonEndEvent(session, (ReasonEndEvent) event, taskAgentName, isMultitask);
            fillMeta(evt, session, runId, taskId, taskAgentName, event.getRunId());
            result.add(evt);
        } else if (event instanceof RunEndEvent) {
            if (taskId != null) {
                WebEvent<?> taskDone = onTaskDoneEvent((RunEndEvent) event, runId, taskId, taskAgentName, taskDescription, isMultitask);
                fillMeta(taskDone, session, runId, taskId, taskAgentName, event.getRunId());
                result.add(taskDone);
            }
            WebEvent<?> trace = onRunEndEvent(session, (RunEndEvent) event);
            fillMeta(trace, session, runId, taskId, taskAgentName, event.getRunId());
            result.add(trace);
        }

        return result;
    }

    private void fillMeta(WebEvent<?> evt, AgentSession session, String runId, String taskId, String agentName, String fallbackRunId) {
        if (evt == null) return;
        if (session != null && evt.getSessionId() == null) {
            evt.setSessionId(session.getSessionId());
        }
        if (runId != null) {
            evt.setTraceId(runId);
        } else if (fallbackRunId != null && evt.getTraceId() == null) {
            evt.setTraceId(fallbackRunId);
        }
        if (taskId != null && evt.getTaskId() == null) {
            evt.setTaskId(taskId);
        }
        if (agentName != null && evt.getAgentName() == null) {
            evt.setAgentName(agentName);
        }
    }

    private WebEvent<?> onContextSizeEvent(ChatModel chatModel, ContextSizeEvent chunk) {
        Integer limit = null;
        if (chatModel != null && chatModel.getConfig() != null && chatModel.getConfig().getContextLength() > 0) {
            limit = (int) chatModel.getConfig().getContextLength();
        }
        return WebEvent.of(WebEventNames.SYSTEM_CONTEXT, SystemContextPayload.builder()
                .tokens(chunk.getTokenCount())
                .count(chunk.getMessageCount())
                .contextLimit(limit)
                .build());
    }

    private WebEvent<?> onReasonDeltaEvent(ReasonDeltaEvent chunk, String taskAgentName) {
        if (chunk.isThinking()) {
            return WebEvent.ofReason(chunk.getReasonId(), chunk.getContent());
        } else {
            return WebEvent.ofText(chunk.getContent());
        }
    }

    private List<WebEvent<?>> onHITLPendingEvent(AgentSession session, HITLPendingEvent chunk) {
        List<WebEvent<?>> result = new ArrayList<>();
        if (chunk == null || chunk.getPendingTasks() == null || chunk.getPendingTasks().isEmpty()) {
            return result;
        }
        for (HITLTask task : chunk.getPendingTasks()) {
            if (task == null) continue;
            result.add(buildHitlEvent(session, task));
        }
        return result;
    }

    private WebEvent<?> buildHitlEvent(AgentSession session, HITLTask task) {
        String toolName = task.getToolName();
        Map<String, Object> args = task.getArgs();
        String command = null;
        if (args != null) {
            command = (String) args.get("command");
        }
        return WebEvent.ofHitl(toolName, toolName, args, command, task.getCallUuid(), task.getComment());
    }

    private WebEvent<?> onToolCallStartEvent(ToolCallStartEvent event, String taskAgentName) {
        if (event == null || Assert.isEmpty(event.getToolName())) {
            return WebEvent.EMPTY;
        }
        String toolName = event.getToolName();
        if (isInternalTool(toolName)) {
            return WebEvent.EMPTY;
        }

        String toolTitle = toolName;
        if (Assert.isNotEmpty(taskAgentName)) {
            toolTitle = taskAgentName + "/" + toolName;
        }

        Map<String, Object> args = event.getArgs();
        WebEvent<org.noear.solon.codecli.portal.web.event.payload.ToolStartPayload> evt = WebEvent.ofToolCallStart(toolName, toolTitle, args);
        evt.getPayload().setCallId(event.getCallId());
        evt.getPayload().setReasonId(event.getReasonId());
        return evt;
    }

    private WebEvent<?> onToolCallEndEvent(ToolCallEndEvent event, String taskAgentName) {
        if (event == null || Assert.isEmpty(event.getToolName())) {
            return WebEvent.EMPTY;
        }
        String toolName = event.getToolName();
        if (isInternalTool(toolName)) {
            return WebEvent.EMPTY;
        }

        String toolTitle = toolName;
        if (Assert.isNotEmpty(taskAgentName)) {
            toolTitle = taskAgentName + "/" + toolName;
        }

        return WebEvent.of(WebEventNames.TOOL_END, ToolEndPayload.builder()
                .callId(event.getCallId())
                .reasonId(event.getReasonId())
                .name(toolName)
                .title(toolTitle)
                .result(event.getContent())
                .isError(false)
                .args(event.getArgs())
                .build());
    }

    private WebEvent<?> onReasonEndEvent(AgentSession session, ReasonEndEvent event, String taskAgentName, boolean isMultitask) {
        if (isMultitask) {
            return WebEvent.ofText(event.getContent());
        }
        return WebEvent.EMPTY;
    }

    private WebEvent<?> onTaskDoneEvent(RunEndEvent event, String runId, String taskId,
                                       String taskAgentName, String taskDescription, boolean isMultitask) {
        boolean abnormal = (event != null && event.isAbnormal());
        return WebEvent.of(WebEventNames.TASK_DONE, TaskDonePayload.builder()
                .taskId(taskId)
                .parentTaskId(runId)
                .title(taskDescription)
                .status(abnormal ? "error" : "done")
                .isMultitask(isMultitask)
                .build());
    }

    private WebEvent<?> onRunEndEvent(AgentSession session, RunEndEvent event) {
        if (event == null || event.getTrace() == null) {
            return WebEvent.EMPTY;
        }
        ReActTrace trace = event.getTrace();
        String model = (chatModel != null && chatModel.getConfig() != null) ? chatModel.getConfig().getModel() : null;
        Long totalTokens = (trace.getMetrics() != null) ? trace.getMetrics().getTotalTokens() : 0L;
        long elapsedSeconds = 0L;
        if (trace.getBeginTimeMs() > 0) {
            elapsedSeconds = (System.currentTimeMillis() - trace.getBeginTimeMs()) / 1000;
        }
        String finalAnswer = (event.getTrace() != null) ? event.getTrace().getFinalAnswer() : null;
        return WebEvent.ofTrace(model, totalTokens, elapsedSeconds, finalAnswer);
    }

    private boolean isInternalTool(String toolName) {
        return Assert.isEmpty(toolName) ||
                "task".equals(toolName) ||
                "multitask".equals(toolName);
    }
}
