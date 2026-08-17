package org.noear.solon.codecli.portal.web.event;

/**
 * SAEP 2.0 (Solon Agent Event Protocol) 事件点分命名常量
 *
 * @author noear
 */
public interface WebEventNames {
    // message 命名空间
    String MESSAGE_DELTA = "message.delta";
    String MESSAGE_COMPLETE = "message.complete";

    // thought 命名空间
    String THOUGHT_DELTA = "thought.delta";
    String THOUGHT_END = "thought.end";

    // tool 命名空间
    String TOOL_START = "tool.start";
    String TOOL_END = "tool.end";

    // hitl 命名空间
    String HITL_PENDING = "hitl.pending";
    String HITL_RESOLVED = "hitl.resolved";

    // task 命名空间
    String TASK_START = "task.start";
    String TASK_DONE = "task.done";

    // system 命名空间
    String SYSTEM_TRACE = "system.trace";
    String SYSTEM_CONTEXT = "system.context";
    String SYSTEM_FILER_CHANGE = "system.filer_change";
    String SYSTEM_USER_INPUT = "system.user_input";
    String SYSTEM_COMMAND = "system.command";
    String SYSTEM_REWIND = "system.rewind";
    String SYSTEM_RESET = "system.reset";
    String SYSTEM_DONE = "system.done";
    String SYSTEM_ERROR = "system.error";
}
