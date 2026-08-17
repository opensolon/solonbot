package org.noear.solon.codecli.portal.web.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.noear.solon.codecli.portal.web.event.payload.*;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * SAEP 2.0 (Solon Agent Event Protocol) 统一事件信封
 *
 * @param <T> 专属载荷类型
 * @author noear
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebEvent<T> implements Serializable {

    public static final WebEvent<?> EMPTY = new WebEvent<>();

    /** 点分命名空间事件名，例如 "message.delta", "tool.start" */
    private String event;

    /** 单次任务运行的跟踪 ID */
    private String traceId;

    /** 会话 ID */
    private String sessionId;

    /** 子任务 ID (可选) */
    private String taskId;

    /** 触发该事件的代理名称 (可选) */
    private String agentName;

    /** 毫秒时间戳 */
    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    /** 强类型专属载荷 */
    private T payload;

    public static boolean isNotEmpty(WebEvent<?> event) {
        return event != null && event.event != null;
    }

    public static <T> WebEvent<T> of(String event, T payload) {
        WebEvent<T> evt = new WebEvent<>();
        evt.setEvent(event);
        evt.setPayload(payload);
        return evt;
    }

    public static WebEvent<Void> ofDone() {
        return of(WebEventNames.SYSTEM_DONE, null);
    }

    public static WebEvent<SystemErrorPayload> ofError(String message) {
        return ofError(null, message);
    }

    public static WebEvent<SystemErrorPayload> ofError(String code, String message) {
        return of(WebEventNames.SYSTEM_ERROR, SystemErrorPayload.builder()
                .code(code)
                .message(message)
                .build());
    }

    public static WebEvent<SystemErrorPayload> ofError(Throwable err) {
        String msg = (err != null && err.getMessage() != null) ? err.getMessage() : "未知错误";
        return ofError(null, msg);
    }

    public static WebEvent<MessagePayload> ofText(String text) {
        return of(WebEventNames.MESSAGE_DELTA, MessagePayload.builder().delta(text).build());
    }

    public static WebEvent<ThoughtPayload> ofReason(String reasonId, String text) {
        return of(WebEventNames.THOUGHT_DELTA, ThoughtPayload.builder().id(reasonId).delta(text).build());
    }

    public static WebEvent<ThoughtPayload> ofReason(String text) {
        return ofReason(null, text);
    }

    public static WebEvent<ToolStartPayload> ofToolCallStart(String toolName, String toolTitle, Map<String, Object> args) {
        return of(WebEventNames.TOOL_START, ToolStartPayload.builder()
                .name(toolName)
                .title(toolTitle)
                .args(args)
                .build());
    }

    public static WebEvent<ToolEndPayload> ofToolCallEnd(String text) {
        return of(WebEventNames.TOOL_END, ToolEndPayload.builder()
                .result(text)
                .build());
    }

    public static WebEvent<HitlPayload> ofHitl(String toolName, String toolTitle, Map<String, Object> args,
                                              String command, String callId, String comment) {
        return of(WebEventNames.HITL_PENDING, HitlPayload.builder()
                .toolName(toolName)
                .toolTitle(toolTitle)
                .args(args)
                .command(command)
                .callId(callId)
                .comment(comment)
                .build());
    }

    public static WebEvent<TaskDonePayload> ofTaskDone(String status) {
        return of(WebEventNames.TASK_DONE, TaskDonePayload.builder().status(status).build());
    }

    public static WebEvent<SystemTracePayload> ofTrace(String model, Long totalTokens, Long elapsedSeconds, String finalAnswer) {
        return of(WebEventNames.SYSTEM_TRACE, SystemTracePayload.builder()
                .model(model)
                .totalTokens(totalTokens)
                .elapsedSeconds(elapsedSeconds)
                .finalAnswer(finalAnswer)
                .build());
    }

    public static WebEvent<SystemRewindPayload> ofRewind(int count) {
        return of(WebEventNames.SYSTEM_REWIND, SystemRewindPayload.builder().count(count).build());
    }

    public static WebEvent<SystemCommandPayload> ofCommand(String text) {
        return of(WebEventNames.SYSTEM_COMMAND, SystemCommandPayload.builder().command(text).build());
    }

    public static WebEvent<Void> ofResetStream() {
        return of(WebEventNames.SYSTEM_RESET, null);
    }

    public static WebEvent<SystemUserInputPayload> ofUserInput(String text, String source) {
        return of(WebEventNames.SYSTEM_USER_INPUT, SystemUserInputPayload.builder()
                .text(text)
                .source(source)
                .build());
    }

    public static String toSourceLabel(String source) {
        if (source == null) return "Web";
        switch (source) {
            case "wechat": return "微信";
            case "feishu": return "飞书";
            case "dingtalk": return "钉钉";
            case "web": return "Web";
            default: return source;
        }
    }
}
