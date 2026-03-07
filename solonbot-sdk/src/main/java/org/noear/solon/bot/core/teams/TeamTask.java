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
package org.noear.solon.bot.core.teams;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.ChatModel;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 团队任务（Team Task）
 *
 * Agent Teams 中的任务对象，支持：
 * - 任务分配和认领
 * - 优先级管理
 * - 依赖关系
 * - 状态跟踪
 * - 协作信息
 *
 * @author bai
 * @since 3.9.5
 */
public class TeamTask {
    private final String id;                      // 任务ID
    private String title;                          // 任务标题
    private String description;                    // 详细描述
    private int priority;                         // 优先级（1-10，10最高）
    private Status status;                         // 任务状态
    private TaskType type;                         // 任务类型

    // 认领信息
    private String claimedBy;                       // 认领者 Agent ID
    private long claimTime;                         // 认领时间

    // 执行信息
    private Object result;                         // 执行结果
    private String errorMessage;                   // 错误信息
    private long completedTime;                     // 完成时间

    // 依赖和协作
    private List<String> dependencies;              // 依赖的任务ID列表
    private Map<String, String> metadata;           // 元数据

    /**
     * 任务状态枚举
     */
    public enum Status {
        PENDING,       // 待认领
        IN_PROGRESS,  // 进行中
        COMPLETED,    // 已完成
        FAILED,       // 失败
        CANCELLED      // 已取消
    }

    /**
     * 任务类型枚举
     */
    public enum TaskType {
        EXPLORATION,   // 探索类任务
        DEVELOPMENT,   // 开发类任务
        ANALYSIS,      // 分析类任务
        DOCUMENTATION, // 文档类任务
        TESTING,      // 测试类任务
        REVIEW        // 审查类任务
    }

    /**
     * 构造函数
     *
     * @param title 任务标题
     */
    public TeamTask(String title) {
        this(UUID.randomUUID().toString(), title);
    }

    /**
     * 完整构造函数
     */
    public TeamTask(String id, String title) {
        this.id = id;
        this.title = title;
        this.priority = 5;  // 默认中等优先级
        this.status = Status.PENDING;
        this.type = TaskType.DEVELOPMENT;
        this.dependencies = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    /**
     * 创建任务
     *
     * @param title 任务标题
     * @return Builder
     */
    public static Builder of(String title) {
        return new Builder(title);
    }

    /**
     * 设置 Prompt 并执行
     *
     * @param chatModel LLM 模型
     * @return 异步结果
     */
    public CompletableFuture<Object> executeWith(ChatModel chatModel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 创建 Prompt
                Prompt prompt = Prompt.of(description != null ? description : title);

                // 调用 LLM
                return chatModel.chat(prompt).getText();

            } catch (Exception e) {
                throw new RuntimeException("Task execution failed: " + title, e);
            }
        });
    }

    // ========== Getter/Setter ==========

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = Math.max(1, Math.min(10, priority));
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public TaskType getType() {
        return type;
    }

    public void setType(TaskType type) {
        this.type = type;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public long getClaimTime() {
        return claimTime;
    }

    public void setClaimTime(long claimTime) {
        this.claimTime = claimTime;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getCompletedTime() {
        return completedTime;
    }

    public void setCompletedTime(long completedTime) {
        this.completedTime = completedTime;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies != null ? new ArrayList<>(dependencies) : new ArrayList<>();
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    /**
     * 添加元数据
     */
    public void putMetadata(String key, String value) {
        this.metadata.put(key, value);
    }

    /**
     * 获取元数据
     */
    public String getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * 获取元数据（带默认值）
     */
    public String getMetadata(String key, String defaultValue) {
        return metadata.getOrDefault(key, defaultValue);
    }

    /**
     * 检查是否已完成
     */
    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    /**
     * 检查是否失败
     */
    public boolean isFailed() {
        return status == Status.FAILED;
    }

    /**
     * 检查是否可认领
     */
    public boolean isClaimable() {
        return status == Status.PENDING;
    }

    /**
     * 获取耗时
     */
    public long getDuration() {
        if (completedTime > 0 && claimTime > 0) {
            return completedTime - claimTime;
        }
        return 0;
    }

    @Override
    public String toString() {
        return "TeamTask{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", priority=" + priority +
                ", status=" + status +
                ", type=" + type +
                ", claimedBy='" + claimedBy + '\'' +
                (result != null ? ", hasResult=true" : "") +
                (errorMessage != null ? ", error='" + errorMessage + '\'' : "") +
                '}';
    }

    /**
     * Builder 类
     */
    public static class Builder {
        private final TeamTask task;

        private Builder(String title) {
            this.task = new TeamTask(title);
        }

        public Builder id(String id) {
            this.task.id = id;
            return this;
        }

        public Builder description(String description) {
            task.setDescription(description);
            return this;
        }

        public Builder priority(int priority) {
            task.setPriority(priority);
            return this;
        }

        public Builder type(TaskType type) {
            task.setType(type);
            return this;
        }

        public Builder dependencies(List<String> dependencies) {
            task.setDependencies(dependencies);
            return this;
        }

        public Builder metadata(String key, String value) {
            task.putMetadata(key, value);
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            task.setMetadata(metadata);
            return this;
        }

        public TeamTask build() {
            return task;
        }
    }
}
