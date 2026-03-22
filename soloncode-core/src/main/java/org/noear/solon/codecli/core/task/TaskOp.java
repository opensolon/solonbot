package org.noear.solon.codecli.core.task;

import org.noear.solon.annotation.Param;

/**
 * 任务定义
 */
public class TaskOp {
    @Param(name = "name", description = "子代理名称")
    private String name;
    @Param(name = "prompt", description = "具体指令。子代理看不见当前历史，必须包含任务目标、关键类名或必要的背景上下文。")
    private String prompt;
    @Param(name = "description", required = false, description = "简短的任务描述")
    private String description;
    @Param(name = "taskId", required = false, description = "可选。若要继续之前的任务会话，请传入对应的 task_id")
    private String taskId;

    public String getName() {
        return name;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getDescription() {
        return description;
    }

    public String getTaskId() {
        return taskId;
    }

    @Override
    public String toString() {
        return "TaskOp{" +
                "name='" + name + '\'' +
                ", taskId='" + taskId + '\'' +
                ", desc='" + description + '\'' +
                '}';
    }
}