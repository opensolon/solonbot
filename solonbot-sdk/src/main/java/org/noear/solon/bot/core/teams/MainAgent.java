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

import lombok.Getter;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.CliSkillProvider;
import org.noear.solon.bot.core.PoolManager;
import org.noear.solon.bot.core.SystemPrompt;
import org.noear.solon.bot.core.event.AgentEvent;
import org.noear.solon.bot.core.event.AgentEventType;
import org.noear.solon.bot.core.event.EventBus;
import org.noear.solon.bot.core.event.EventHandler;
import org.noear.solon.bot.core.event.EventMetadata;
import org.noear.solon.bot.core.goalker.GoalKeeperIntegration;
import org.noear.solon.bot.core.memory.SharedMemoryManager;
import org.noear.solon.bot.core.memory.ShortTermMemory;
import org.noear.solon.bot.core.message.AgentMessage;
import org.noear.solon.bot.core.message.MessageAck;
import org.noear.solon.bot.core.message.MessageChannel;
import org.noear.solon.bot.core.subagent.SubAgentMetadata;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.noear.solon.bot.core.subagent.TaskSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主代理（Team Lead）- Agent Teams 模式协调器
 *
 * 负责任务：
 * - 创建和管理共享任务列表
 * - 分配初始任务到共享任务池
 * - 协调多个子代理协作
 * - 监控任务执行状态
 * - 汇总最终结果
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
public class MainAgent {
    private static final Logger LOG = LoggerFactory.getLogger(MainAgent.class);

    private final SubAgentMetadata config;
    private final AgentSessionProvider sessionProvider;
    private final SharedMemoryManager sharedMemoryManager;
    private final EventBus eventBus;
    private final MessageChannel messageChannel;
    private final SharedTaskList taskList;
    private final String workDir;
    private final PoolManager poolManager;

    // 目标守护者
    private GoalKeeperIntegration goalKeeper;

    // 新增：用于访问 subagent 功能
    private final AgentKernel kernel;
    private final SubagentManager subagentManager;

    private ReActAgent agent;
    private AgentSession session;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // 任务事件监听器
    private String taskEventSubscriptionId;
    private String taskFailedSubscriptionId;  //  新增：保存失败事件订阅ID
    private String messageHandlerId;

    // 性能优化：使用 CountDownLatch 替代轮询
    private volatile CountDownLatch taskCompletionLatch;

    // 超时配置（单位：毫秒）
    private static final long EXECUTION_TIMEOUT_MS = 300_000; // 5分钟超时
    private static final long LLM_CALL_TIMEOUT_MS = 120_000;  // LLM调用超时2分钟

    public MainAgent(SubAgentMetadata config,
                     AgentSessionProvider sessionProvider,
                     SharedMemoryManager sharedMemoryManager,
                     EventBus eventBus,
                     MessageChannel messageChannel,
                     SharedTaskList taskList,
                     String workDir,
                     PoolManager poolManager) {
        this(config, sessionProvider, sharedMemoryManager, eventBus, messageChannel,
             taskList, workDir, poolManager, null, null);
    }

    /**
     * 完整构造函数（支持 subagent 功能）
     */
    public MainAgent(SubAgentMetadata config,
                     AgentSessionProvider sessionProvider,
                     SharedMemoryManager sharedMemoryManager,
                     EventBus eventBus,
                     MessageChannel messageChannel,
                     SharedTaskList taskList,
                     String workDir,
                     PoolManager poolManager,
                     AgentKernel kernel,
                     SubagentManager subagentManager) {
        this.config = config;
        this.sessionProvider = sessionProvider;
        this.sharedMemoryManager = sharedMemoryManager;
        this.eventBus = eventBus;
        this.messageChannel = messageChannel;
        this.taskList = taskList;
        this.workDir = workDir;
        this.poolManager = poolManager;
        this.kernel = kernel;
        this.subagentManager = subagentManager;

        // 注册任务事件监听器
        registerTaskEventListeners();

        // 注册消息处理器
        registerMessageHandler();
    }

    /**
     * 初始化主代理
     */
    public synchronized void initialize(ChatModel chatModel) {
        if (agent == null) {
            ReActAgent.Builder builder = ReActAgent.of(chatModel);

            // 设置系统提示词
            builder.systemPrompt(SystemPrompt.builder()
                    .instruction(getSystemPrompt())
                    .build());

            // 添加技能
            CliSkillProvider skillProvider = new CliSkillProvider(workDir);
            if (poolManager != null) {
                poolManager.getPoolMap().forEach((alias, path) -> {
                    skillProvider.skillPool(alias, path);
                });
            }

            // 基础技能
            builder.defaultSkillAdd(skillProvider.getTerminalSkill());
            builder.defaultSkillAdd(skillProvider.getExpertSkill());

            // Agent Teams 工具集（记忆、事件、消息）
            AgentTeamsTools teamsTools = new AgentTeamsTools(
                    sharedMemoryManager,
                    eventBus
            );
            builder.defaultSkillAdd(teamsTools);

            // 子代理调用工具（如果有 kernel 和 subagentManager）
            if (kernel != null && subagentManager != null) {
                TaskSkill taskSkill = new TaskSkill(kernel, subagentManager);
                builder.defaultSkillAdd(taskSkill);
                LOG.debug("MainAgent: TaskSkill 已添加");
            } else {
                LOG.debug("MainAgent: 无 kernel 或 subagentManager，跳过 TaskSkill");
            }

            // 设置较大的步数（主代理需要协调多个任务）
            builder.maxSteps(50);
            builder.sessionWindowSize(10);

            this.agent = builder.build();
            this.session = sessionProvider.getSession("main_agent");

            LOG.info("MainAgent '{}' 初始化完成", config.getCode());
        }
    }

    /**
     * 流式执行主任务（实时输出）
     *
     * @param prompt 用户提示
     * @param __cwd 工作目录
     * @return 响应流
     */
    public Flux<AgentChunk> executeStream(Prompt prompt, String __cwd) throws Throwable {
        if (agent == null) {
            throw new IllegalStateException("MainAgent 尚未初始化");
        }

        running.set(true);

        // 0. 启动目标守护（防止在多轮循环中偏离目标）
        String goalId = null;
        try {
            if (kernel != null) {
                goalId = this.startGoalGuarding(prompt.getUserContent());
                LOG.info("目标守护已启动: goalId={}, 目标={}", goalId, prompt.getUserContent());
            }
        } catch (Exception e) {
            LOG.warn("启动目标守护失败（继续执行）: {}", e.getMessage());
        }

        try {
            // 1. 发布主代理任务开始事件
            publishEvent(AgentEventType.MAIN_TASK_STARTED, prompt.getUserContent(), null);

            // 2. 分析任务并创建子任务
            List<TeamTask> subTasks = analyzeAndCreateTasks(prompt);

            // 3. 将任务添加到共享任务列表（带错误处理和验证）
            if (!subTasks.isEmpty()) {
                try {
                    // 打印依赖图
                    LOG.debug("任务依赖关系图:\n{}", taskList.getDependencyGraph());

                    // 检测循环依赖
                    List<TeamTask> cyclicTasks = taskList.detectCyclicDependencies();
                    if (!cyclicTasks.isEmpty()) {
                        LOG.error("检测到循环依赖，任务将被拒绝:");
                        for (TeamTask task : cyclicTasks) {
                            LOG.error("  - {}", task.getTitle());
                        }
                        throw new IllegalStateException("存在循环依赖，无法添加任务");
                    }

                    List<TeamTask> added = taskList.addTasks(subTasks).join();
                    LOG.info("主代理已添加 {} 个子任务到共享任务列表", added.size());

                    // 4. 广播任务可用通知
                    broadcastTaskNotification(added);

                    // 5. 记录可认领任务数
                    List<TeamTask> claimableTasks = taskList.getClaimableTasks();
                    LOG.info("当前可认领任务数: {} / {}", claimableTasks.size(), added.size());

                } catch (IllegalStateException e) {
                    LOG.error("添加任务失败: {}", e.getMessage());
                    // 继续执行主代理自身任务
                } catch (Exception e) {
                    LOG.error("添加任务时发生异常: {}", e.getMessage(), e);
                    // 继续执行主代理自身任务
                }
            }

            // 6. 执行主代理内部的协调逻辑（流式输出）
            reactor.core.publisher.Flux<AgentChunk> responseStream = agent.prompt(prompt)
                    .session(session)
                    .options(o -> {
                        // 传递工作目录给工具（ls、bash 等需要）
                        if (__cwd != null && !__cwd.isEmpty()) {
                            o.toolContextPut("__cwd", __cwd);
                        }
                    })
                    .stream();

            // 7. 在流完成后等待所有子任务完成
            reactor.core.publisher.Flux<AgentChunk> resultStream = responseStream
                    .doOnComplete(() -> {
                        try {
                            // 等待所有子任务完成
                            waitForAllTasksCompleted();

                            // 汇总结果
                            String summary = summarizeResults();

                            // 发布主代理任务完成事件
                            publishEvent(AgentEventType.MAIN_TASK_COMPLETED, summary, null);

                            LOG.info("MainAgent 流式执行完成");

                        } catch (Exception e) {
                            LOG.error("MainAgent 后处理失败", e);
                        } finally {
                            // 停止目标守护
                            try {
                                this.stopGoalGuarding();
                                LOG.info("目标守护已停止");
                            } catch (Exception e) {
                                LOG.warn("停止目标守护失败: {}", e.getMessage());
                            }
                        }
                    })
                    .doOnError(error -> {
                        LOG.error("MainAgent 流式执行出错", error);
                        running.set(false);
                        // 出错时也要停止目标守护
                        try {
                            this.stopGoalGuarding();
                            LOG.info("目标守护已停止（错误）");
                        } catch (Exception e) {
                            LOG.warn("停止目标守护失败: {}", e.getMessage());
                        }
                    })
                    .doOnCancel(() -> {
                        LOG.warn("MainAgent 流式执行被取消");
                        running.set(false);
                        // 取消时也要停止目标守护
                        try {
                            this.stopGoalGuarding();
                            LOG.info("目标守护已停止（取消）");
                        } catch (Exception e) {
                            LOG.warn("停止目标守护失败: {}", e.getMessage());
                        }
                    });

            return resultStream;

        } finally {
            running.set(false);
            // 确保目标守护被停止（即使发生异常）
            try {
                this.stopGoalGuarding();
                LOG.info("目标守护已停止（finally块）");
            } catch (Exception e) {
                LOG.warn("停止目标守护失败（finally块）: {}", e.getMessage());
            }
        }
    }

    /**
     * 分析任务并创建子任务
     */
    private List<TeamTask> analyzeAndCreateTasks(Prompt prompt) {
        List<TeamTask> tasks = new ArrayList<>();
        String userPrompt = prompt.getUserContent().toLowerCase();

        // 从共享内存中读取相关信息（如果有历史任务结果）
        StringBuilder contextBuilder = new StringBuilder();
        if (sharedMemoryManager != null) {
            try {
                // 查询相关的历史任务结果
                List<org.noear.solon.bot.core.memory.Memory> recentMemories =
                        sharedMemoryManager.search("task-result", 10);

                if (!recentMemories.isEmpty()) {
                    contextBuilder.append("\n\n# 历史任务上下文\n\n");
                    for (org.noear.solon.bot.core.memory.Memory memory : recentMemories) {
                        if (memory instanceof ShortTermMemory) {
                            ShortTermMemory stm = (ShortTermMemory) memory;
                            String taskTitle = (String) stm.getMetadata("taskTitle");
                            contextBuilder.append(String.format("- **%s**: %s\n",
                                    taskTitle != null ? taskTitle : "Unknown",
                                    stm.getContext()));
                        }
                    }
                    LOG.debug("从共享内存加载了 {} 条历史任务记录", recentMemories.size());
                }
            } catch (Exception e) {
                LOG.warn("从共享内存读取历史任务失败", e);
            }
        }

        // 根据提示内容智能创建任务
        // 这里是简化版本，实际可以使用 LLM 来分析任务
        // 可以将 contextBuilder 中的内容添加到任务的描述中

        if (userPrompt.contains("探索") || userPrompt.contains("explore") || userPrompt.contains("分析")) {
            // 创建探索任务链：探索 -> 搜索
            String exploreId = "task-explore-" + System.currentTimeMillis();
            String searchId = "task-search-" + System.currentTimeMillis();

            TeamTask exploreTask = TeamTask.builder()
                    .id(exploreId)
                    .title("探索代码库")
                    .description("探索和分析代码库结构")
                    .type(TeamTask.TaskType.EXPLORATION)
                    .priority(8)
                    .build();

            TeamTask searchTask = TeamTask.builder()
                    .id(searchId)
                    .title("搜索关键文件")
                    .description("搜索与任务相关的关键文件")
                    .type(TeamTask.TaskType.ANALYSIS)
                    .priority(7)
                    .dependencies(Collections.singletonList(exploreId))
                    .build();

            tasks.add(exploreTask);
            tasks.add(searchTask);
        }

        if (userPrompt.contains("实现") || userPrompt.contains("开发") || userPrompt.contains("implement")) {
            // 创建开发任务链：计划 -> 实现 -> 测试
            String planId = "task-plan-" + System.currentTimeMillis();
            String implId = "task-impl-" + System.currentTimeMillis();
            String testId = "task-test-" + System.currentTimeMillis();

            TeamTask planTask = TeamTask.builder()
                    .id(planId)
                    .title("制定实现计划")
                    .description("制定详细的实现计划")
                    .type(TeamTask.TaskType.DEVELOPMENT)
                    .priority(9)
                    .build();

            TeamTask implTask = TeamTask.builder()
                    .id(implId)
                    .title("实现功能")
                    .description("根据计划实现功能")
                    .type(TeamTask.TaskType.DEVELOPMENT)
                    .priority(8)
                    .dependencies(Collections.singletonList(planId))
                    .build();

            TeamTask testTask = TeamTask.builder()
                    .id(testId)
                    .title("编写测试")
                    .description("为实现的代码编写测试")
                    .type(TeamTask.TaskType.TESTING)
                    .priority(6)
                    .dependencies(Collections.singletonList(implId))
                    .build();

            tasks.add(planTask);
            tasks.add(implTask);
            tasks.add(testTask);
        }

        if (userPrompt.contains("文档") || userPrompt.contains("document")) {
            // 创建文档任务
            tasks.add(TeamTask.builder()
                    .id("task-doc-" + System.currentTimeMillis())
                    .title("生成文档")
                    .description("生成项目文档")
                    .type(TeamTask.TaskType.DOCUMENTATION)
                    .priority(5)
                    .build());
        }

        // 默认任务（如果没有匹配到特定类型）
        if (tasks.isEmpty()) {
            tasks.add(TeamTask.builder()
                    .id("task-default-" + System.currentTimeMillis())
                    .title("处理用户请求")
                    .description(prompt.getUserContent())
                    .type(TeamTask.TaskType.DEVELOPMENT)
                    .priority(7)
                    .build());
        }

        // 验证任务依赖关系
        logTaskDependencies(tasks);

        return tasks;
    }

    /**
     * 记录任务依赖关系（用于调试）
     */
    private void logTaskDependencies(List<TeamTask> tasks) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("创建任务依赖关系:");
            for (TeamTask task : tasks) {
                if (task.getDependencies() != null && !task.getDependencies().isEmpty()) {
                    LOG.debug("  {} 依赖: {}", task.getTitle(), task.getDependencies());
                }
            }
        }
    }

    /**
     * 广播任务可用通知
     */
    private void broadcastTaskNotification(List<TeamTask> tasks) {
        if (messageChannel != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("action", "tasks_available");
            payload.put("count", tasks.size());
            payload.put("tasks", tasks);

            AgentMessage<Map<String, Object>> message = AgentMessage.<Map<String, Object>>of(payload)
                    .from(config.getCode())
                    .to("*")
                    .type("task_notification")
                    .build();

            try {
                List<MessageAck> acks = messageChannel.broadcast(message).join();
                LOG.info("任务通知已广播，收到 {} 个确认", acks.size());
            } catch (Exception e) {
                LOG.warn("广播任务通知失败", e);
            }
        }
    }

    /**
     * 等待所有任务完成（使用事件驱动机制，性能优化）
     */
    private void waitForAllTasksCompleted() {
        // 初始化 CountDownLatch（初始值为1，防止立即返回）
        taskCompletionLatch = new CountDownLatch(1);

        try {
            // 检查初始状态
            SharedTaskList.TaskStatistics stats = taskList.getStatistics();
            if (stats.inProgressTasks == 0 && stats.pendingTasks == 0) {
                LOG.info("所有子任务已完成（总计: {}, 完成: {}, 失败: {}）",
                        stats.totalTasks, stats.completedTasks, stats.failedTasks);
                return;
            }

            LOG.info("等待任务完成... (进行中: {}, 待认领: {})",
                    stats.inProgressTasks, stats.pendingTasks);

            // 每30秒打印一次统计信息（使用超时等待，避免阻塞太久）
            long remainingWaitMs = 300_000; // 最多等待5分钟
            long printIntervalMs = 30_000;   // 每30秒打印一次

            while (remainingWaitMs > 0) {
                long waitTime = Math.min(printIntervalMs, remainingWaitMs);

                // 等待任务完成或超时
                boolean completed = taskCompletionLatch.await(waitTime, TimeUnit.MILLISECONDS);

                stats = taskList.getStatistics();

                if (stats.inProgressTasks == 0 && stats.pendingTasks == 0) {
                    LOG.info("所有子任务已完成（总计: {}, 完成: {}, 失败: {}）",
                            stats.totalTasks, stats.completedTasks, stats.failedTasks);
                    break;
                }

                if (!completed) {
                    // 超时，打印进度
                    LOG.info("任务进度: {}", stats);

                    // 打印阻塞信息
                    if (stats.pendingTasks > 0) {
                        String blockingInfo = taskList.getBlockingInfo();
                        if (blockingInfo.length() > 50) {
                            LOG.debug("阻塞任务:\n{}", blockingInfo);
                        }
                    }
                }

                remainingWaitMs -= waitTime;
            }

            if (remainingWaitMs <= 0) {
                LOG.warn("等待任务完成超时，当前状态: {}", taskList.getStatistics());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("等待任务完成被中断");
        } finally {
            taskCompletionLatch = null;
        }
    }

    /**
     * 汇总结果
     */
    private String summarizeResults() {
        SharedTaskList.TaskStatistics stats = taskList.getStatistics();
        List<TeamTask> allTasks = taskList.getAllTasks();

        StringBuilder summary = new StringBuilder();
        summary.append("## 任务执行汇总\n\n");
        summary.append(stats.toString()).append("\n\n");

        // 列出完成的任务
        summary.append("### 已完成任务\n\n");
        for (TeamTask task : allTasks) {
            if (task.isCompleted()) {
                summary.append(String.format("- **%s**: %s\n",
                        task.getTitle(),
                        task.getResult() != null ? task.getResult().toString() : "完成"));
            }
        }

        // 列出失败的任务
        summary.append("\n### 失败任务\n\n");
        for (TeamTask task : allTasks) {
            if (task.isFailed()) {
                summary.append(String.format("- **%s**: %s\n",
                        task.getTitle(),
                        task.getErrorMessage()));
            }
        }

        return summary.toString();
    }

    /**
     * 注册任务事件监听器
     * 性能优化：添加事件驱动机制，在所有任务完成时触发 CountDownLatch
     */
    private void registerTaskEventListeners() {
        if (eventBus != null) {
            // 监听子任务完成事件
            taskEventSubscriptionId = eventBus.subscribe(AgentEventType.TASK_COMPLETED, event -> {
                String taskId = event.getMetadata().getTaskId();
                Map<String, Object> payload = (Map<String, Object>) event.getPayload();

                String taskTitle = payload != null ? (String) payload.get("taskTitle") : "Unknown";
                String agentId = payload != null ? (String) payload.get("agentId") : "Unknown";

                LOG.info("主代理收到子任务完成事件: {} by {}", taskTitle, agentId);

                // 将任务结果存储到共享内存（供后续任务使用）
                if (sharedMemoryManager != null && payload != null) {
                    try {
                        Object result = payload.get("result");
                        if (result != null) {
                            // 创建短期记忆存储任务结果
                            ShortTermMemory memory = new ShortTermMemory(
                                    agentId,
                                    result.toString(),
                                    taskId
                            );
                            memory.setId("task-result-" + taskId);

                            // 添加元数据
                            memory.putMetadata("taskTitle", taskTitle);
                            memory.putMetadata("taskType", "task-result");
                            memory.putMetadata("source", agentId);

                            sharedMemoryManager.store(memory);
                            LOG.debug("任务结果已存储到共享内存: taskId={}, source={}", taskId, agentId);
                        }
                    } catch (Exception e) {
                        LOG.warn("存储任务结果到共享内存失败", e);
                    }
                }

                // 检查是否有依赖此任务的其他任务可以开始执行
                checkAndNotifyDependentTasks(taskId);

                // 性能优化：检查是否所有任务都已完成，触发 CountDownLatch
                checkAllTasksCompleted();

                return CompletableFuture.completedFuture(EventHandler.Result.success());
            });

            // 监听子任务失败事件
            taskFailedSubscriptionId = eventBus.subscribe(AgentEventType.TASK_FAILED, event -> {
                String taskId = event.getMetadata().getTaskId();
                Map<String, Object> payload = (Map<String, Object>) event.getPayload();

                String taskTitle = payload != null ? (String) payload.get("taskTitle") : "Unknown";
                String agentId = payload != null ? (String) payload.get("agentId") : "Unknown";

                // 获取错误信息（从任务对象或payload）
                String errorMessage = null;
                if (payload != null && payload.containsKey("errorMessage")) {
                    errorMessage = (String) payload.get("errorMessage");
                } else {
                    // 从任务列表中获取错误信息
                    TeamTask failedTask = taskList.getTask(taskId);
                    if (failedTask != null) {
                        errorMessage = failedTask.getErrorMessage();
                    }
                }

                if (errorMessage == null) {
                    errorMessage = "Unknown error";
                }

                LOG.warn("主代理收到子任务失败事件: {} by {} - {}", taskTitle, agentId, errorMessage);

                // 处理任务失败：决定是否需要重试或标记依赖任务失败
                handleTaskFailure(taskId, errorMessage);

                // 性能优化：检查是否所有任务都已完成（包括失败的），触发 CountDownLatch
                checkAllTasksCompleted();

                return CompletableFuture.completedFuture(EventHandler.Result.success());
            });

            LOG.info("MainAgent 任务事件监听器已注册（TASK_COMPLETED, TASK_FAILED）");
        }
    }

    /**
     * 检查是否所有任务都已完成
     * 性能优化：如果所有任务完成，触发 CountDownLatch
     */
    private void checkAllTasksCompleted() {
        if (taskCompletionLatch != null) {
            SharedTaskList.TaskStatistics stats = taskList.getStatistics();
            if (stats.inProgressTasks == 0 && stats.pendingTasks == 0) {
                // 所有任务都已完成或失败，触发 CountDownLatch
                taskCompletionLatch.countDown();
                LOG.debug("所有任务完成，触发 CountDownLatch");
            }
        }
    }

    /**
     * 检查并通知依赖此任务的其他任务
     * 修复：实际广播任务可用通知，而不仅仅是记录日志
     */
    private void checkAndNotifyDependentTasks(String completedTaskId) {
        try {
            // 查找依赖此任务的所有待认领任务
            List<TeamTask> claimableTasks = taskList.getClaimableTasks();

            if (!claimableTasks.isEmpty()) {
                LOG.info("发现 {} 个可认领的任务（由于任务 {} 完成），广播通知...",
                    claimableTasks.size(), completedTaskId);

                // 修复：通过 MessageChannel 广播任务可用通知
                broadcastTaskNotification(claimableTasks);
            }
        } catch (Exception e) {
            LOG.error("检查依赖任务失败", e);
        }
    }

    /**
     * 处理任务失败
     */
    private void handleTaskFailure(String taskId, String errorMessage) {
        try {
            // 查找依赖此失败任务的其他任务
            List<TeamTask> allTasks = taskList.getAllTasks();
            List<TeamTask> dependentTasks = new ArrayList<>();

            for (TeamTask task : allTasks) {
                if (task.getStatus() == TeamTask.Status.PENDING &&
                    task.getDependencies().contains(taskId)) {
                    dependentTasks.add(task);
                }
            }

            if (!dependentTasks.isEmpty()) {
                LOG.warn("发现 {} 个任务受影响（依赖失败任务 {}）", dependentTasks.size(), taskId);

                // 策略1：标记依赖任务为失败
                // 策略2：等待人工干预
                // 当前：记录日志，保持待认领状态（由人工决策）
            }
        } catch (Exception e) {
            LOG.error("处理任务失败时发生异常", e);
        }
    }

    /**
     * 注册消息处理器
     */
    private void registerMessageHandler() {
        if (messageChannel != null) {
            messageHandlerId = messageChannel.registerHandler(
                    config.getCode(),
                    this::handleMessage
            );
            LOG.info("MainAgent 消息处理器已注册");
        }
    }

    /**
     * 处理子代理发送的消息
     */
    private <T> CompletableFuture<Object> handleMessage(AgentMessage<T> message) {
        LOG.debug("MainAgent 收到消息: from={}, type={}",
                message.getFrom(), message.getType());

        // 处理任务相关消息
        if ("task_query".equals(message.getType())) {
            // 子代理查询任务列表
            return CompletableFuture.completedFuture(getTaskListInfo());
        } else if ("task_help".equals(message.getType())) {
            // 子代理请求帮助
            return CompletableFuture.completedFuture(handleHelpRequest(message));
        }

        return CompletableFuture.completedFuture("ACK");
    }

    /**
     * 获取任务列表信息
     */
    private Map<String, Object> getTaskListInfo() {
        Map<String, Object> info = new HashMap<>();
        SharedTaskList.TaskStatistics stats = taskList.getStatistics();

        info.put("statistics", stats);
        info.put("pendingTasks", taskList.getPendingTasks().size());
        info.put("claimableTasks", taskList.getClaimableTasks().size());

        return info;
    }

    /**
     * 处理帮助请求
     */
    private <T> Object handleHelpRequest(AgentMessage<T> message) {
        LOG.info("收到来自 {} 的帮助请求", message.getFrom());

        // 这里可以实现协调逻辑，例如：
        // 1. 重新分配任务
        // 2. 提供额外的资源
        // 3. 协调多个代理协作

        return "Help request received";
    }

    /**
     * 发布事件
     */
    private void publishEvent(AgentEventType eventType, Object payload, String taskId) {
        if (eventBus != null) {
            EventMetadata metadata = EventMetadata.builder()
                    .sourceAgent(config.getCode())
                    .taskId(taskId)
                    .priority(5)
                    .build();

            AgentEvent event = new AgentEvent(eventType, payload, metadata);
            eventBus.publishAsync(event);
        }
    }

    /**
     * 获取系统提示词
     */
    private String getSystemPrompt() {
        return "## 主代理（Team Lead）- 强制执行模式\n\n" +
                "你是 Agent Teams 的团队领导，负责协调多个子代理协作完成任务。\n" +
                "\n" +
                "### ⚠️ 核心规则（违反即失败）\n" +
                "\n" +
                "#### 🚫 禁止行为（绝对不可违反）\n" +
                "1. **禁止模拟工作**：\n" +
                "   - 严禁使用 `update_working_memory`、`memory_store` 等工具声称工作已完成\n" +
                "   - 不断更新 step、currentAgent 字段而不实际工作是**严重违规**\n" +
                "   - 不得在记忆中存储虚假的\"已完成\"状态\n\n" +
                "2. **禁止虚假产出**：\n" +
                "   - 不得声称\"需求分析已完成\"、\"代码已编写\"等虚假结论\n" +
                "   - 没有实际文件产出前，不得宣称任务完成\n" +
                "   - 记忆存储只能存储真实已完成的工作结果\n\n" +
                "3. **禁止循环操作**：\n" +
                "   - 不得重复调用相同的工具而不产生新进展\n" +
                "   - 不得无限更新状态而无实际工作\n" +
                "   - 检测到循环时必须立即停止并改变策略\n\n" +
                "#### ✅ 必须行为（必须执行）\n" +
                "1. **必须使用 subagent 工具**：\n" +
                "   - 所有实际工作必须通过 `subagent(type, prompt)` 工具委派给专门的子代理\n" +
                "   - 可用的子代理类型：explore、plan、bash、general-purpose、solon-code-guide\n" +
                "   - 例如：`subagent(type='bash', prompt='创建项目目录并初始化')`\n\n" +
                "2. **必须有实际产出**：\n" +
                "   - **代码任务**必须生成 `.java`、`.py` 等代码文件\n" +
                "   - **文档任务**必须生成 `.md`、`.txt` 等文档文件\n" +
                "   - **测试任务**必须有测试报告或测试结果文件\n" +
                "   - **架构任务**必须有架构图或设计文档\n\n" +
                "3. **必须验证产出**：\n" +
                "   - 使用 `read` 或 `ls` 工具验证文件是否真实创建\n" +
                "   - 确认文件内容符合要求后才可宣称任务完成\n\n" +
                "\n" +
                "### 工作流程（强制执行）\n" +
                "\n" +
                "#### 步骤 1：任务分析（使用 subagent）\n" +
                "```\n" +
                "subagent(\n" +
                "    type='plan',\n" +
                "    prompt='分析任务需求：[用户任务]，提供详细的实现方案'\n" +
                ")\n" +
                "```\n" +
                "\n" +
                "#### 步骤 2：执行工作（使用 subagent）\n" +
                "```\n" +
                "# 开发任务\n" +
                "subagent(\n" +
                "    type='bash',\n" +
                "    prompt='创建文件 [文件名]，编写代码实现：[具体需求]'\n" +
                ")\n" +
                "\n" +
                "# 测试任务\n" +
                "subagent(\n" +
                "    type='bash',\n" +
                "    prompt='编写测试用例并运行测试，生成测试报告'\n" +
                ")\n" +
                "```\n" +
                "\n" +
                "#### 步骤 3：验证产出（使用 ls/read）\n" +
                "```\n" +
                "ls(path='.')  # 列出文件\n" +
                "read(file_path='xxx.java')  # 验证文件内容\n" +
                "```\n" +
                "\n" +
                "#### 步骤 4：总结结果（仅在真实完成后）\n" +
                "```\n" +
                "# 只有在确认文件真实创建后才可总结\n" +
                "Final Answer: [ANSWER]\n" +
                "已完成以下工作：\n" +
                "1. 创建文件：file1.java, file2.py\n" +
                "2. 文件内容：[简要描述]\n" +
                "3. 验证结果：所有文件已通过测试\n" +
                "```\n" +
                "\n" +
                "### ⚠️ 常见错误（必须避免）\n" +
                "\n" +
                "❌ **错误示例**：\n" +
                "```\n" +
                "# 错误1：虚假更新状态\n" +
                "update_working_memory(field='step', value='1')\n" +
                "update_working_memory(field='step', value='2')\n" +
                "memory_store(content='需求分析已完成')  # 虚假！\n" +
                "\n" +
                "# 错误2：声称完成但无产出\n" +
                "Final Answer: [ANSWER]\n" +
                "团队协作完成！  # 但没有创建任何文件\n" +
                "```\n" +
                "\n" +
                "✅ **正确示例**：\n" +
                "```\n" +
                "# 正确：实际调用子代理\n" +
                "subagent(type='bash', prompt='创建 UserController.java')\n" +
                "# 等待结果...\n" +
                "ls(path='src/main/java')  # 验证文件已创建\n" +
                "read(file_path='src/main/java/UserController.java')  # 验证内容\n" +
                "Final Answer: [ANSWER]\n" +
                "已创建 UserController.java，包含用户增删改查功能\n" +
                "```\n" +
                "\n" +
                "### 🎯 成功标准\n" +
                "\n" +
                "任务被认为完成，当且仅当：\n" +
                "1. **有实际文件产出**：代码、文档、测试报告等\n" +
                "2. **文件内容已验证**：使用 read 工具确认内容正确\n" +
                "3. **通过必要测试**：代码可编译、可运行\n" +
                "4. **无虚假声明**：所有声称的完成都是真实的\n" +
                "\n" +
                "### 📊 token 使用警告\n" +
                "\n" +
                "- 每个任务建议不超过 10,000 tokens\n" +
                "- 超过 5,000 tokens 时必须检查是否有实际产出\n" +
                "- 超过 10,000 tokens 无产出时立即终止任务\n" +
                "- 禁止循环调用工具而不产生进展\n" +
                "\n" +
                "### 🚀 立即开始\n" +
                "\n" +
                "接到任务后，必须：\n" +
                "1. 先使用 `subagent(type='plan', ...)` 分析需求\n" +
                "2. 再使用 `subagent(type='bash', ...)` 执行工作\n" +
                "3. 使用 `ls`、`read` 验证产出\n" +
                "4. 确认真实完成后才给出 Final Answer\n" +
                "\n" +
                "**记住：禁止模拟，必须实际产出！**\n";
    }

    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }


    /**
     * 清理资源
     */
    public void destroy() {
        // 注销事件监听器
        if (eventBus != null) {
            if (taskEventSubscriptionId != null) {
                eventBus.unsubscribe(taskEventSubscriptionId);
                LOG.info("MainAgent TASK_COMPLETED 事件监听器已注销");
            }
            if (taskFailedSubscriptionId != null) {
                eventBus.unsubscribe(taskFailedSubscriptionId);
                LOG.info("MainAgent TASK_FAILED 事件监听器已注销");
            }
        }

        // 注销消息处理器
        if (messageChannel != null && messageHandlerId != null) {
            messageChannel.unregisterHandler(config.getCode(), messageHandlerId);
            LOG.info("MainAgent 消息处理器已注销");
        }
    }

    /**
     * 启动目标守护
     *
     * @param userPrompt 用户的目标提示词
     * @return 目标 ID
     */
    public String startGoalGuarding(String userPrompt) {
        if (goalKeeper == null) {
            goalKeeper = new GoalKeeperIntegration(this);
        }
        return goalKeeper.startGoalGuarding(userPrompt);
    }

    /**
     * 停止目标守护
     */
    public void stopGoalGuarding() {
        if (goalKeeper != null) {
            goalKeeper.stopGoalGuarding();
        }
    }

    /**
     * 获取当前目标
     *
     * @return 当前目标描述
     */
    public String getCurrentGoal() {
        return goalKeeper != null ? goalKeeper.getCurrentGoal() : null;
    }

    /**
     * 获取当前目标 ID
     *
     * @return 目标 ID
     */
    public String getCurrentGoalId() {
        return goalKeeper != null ? goalKeeper.getCurrentGoalId() : null;
    }

    /**
     * 检查是否正在守护
     *
     * @return 是否正在守护
     */
    public boolean isGuardingGoal() {
        return goalKeeper != null && goalKeeper.isGuarding();
    }

    /**
     * 获取目标提醒次数
     *
     * @return 提醒次数
     */
    public int getGoalReminderCount() {
        return goalKeeper != null ? goalKeeper.getReminderCount() : 0;
    }

    /**
     * 为 Prompt 添加目标上下文
     *
     * @param prompt 原始提示词
     * @return 带目标的提示词
     */
    public Prompt enrichPromptWithGoal(Prompt prompt) {
        if (goalKeeper != null) {
            return goalKeeper.enrichPromptWithGoal(prompt);
        }
        return prompt;
    }
}
