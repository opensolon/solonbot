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

import org.noear.solon.ai.codecli.core.event.AgentEvent;
import org.noear.solon.ai.codecli.core.event.AgentEventType;
import org.noear.solon.ai.codecli.core.event.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 共享任务列表（Shared Task List）
 *
 * Agent Teams 模式中的共享任务池，支持：
 * - 任务添加和删除
 * - 任务认领和释放
 * - 优先级队列
 * - Agent 负载跟踪
 * - 任务生命周期事件
 *
 * @author bai
 * @since 3.9.5
 */
public class SharedTaskList {
    private static final Logger LOG = LoggerFactory.getLogger(SharedTaskList.class);

    private final Map<String, TeamTask> tasks;              // 所有任务 (taskId -> Task)
    private final Map<String, TeamTask> pendingTasks;       // 待认领任务
    private final Map<String, Set<String>> agentTasks;      // Agent 的任务集合 (agentId -> Set<taskId>)
    private final Map<String, Integer> agentLoad;           // Agent 负载计数
    private final ReadWriteLock lock;                        // 读写锁

    private final EventBus eventBus;                         // 事件总线
    private final int maxCompletedTasks;                    // 保留的最大已完成任务数
    private final Queue<String> completedTaskQueue;         // 已完成任务队列（FIFO清理）

    // 事件监听器
    private final List<TaskEventListener> eventListeners;

    /**
     * 任务事件监听器
     */
    public interface TaskEventListener {
        void onTaskAdded(TeamTask task);
        void onTaskClaimed(TeamTask task, String agentId);
        void onTaskReleased(TeamTask task, String agentId);
        void onTaskCompleted(TeamTask task, String agentId);
        void onTaskFailed(TeamTask task, String agentId, String error);
    }

    /**
     * 构造函数
     *
     * @param eventBus 事件总线
     */
    public SharedTaskList(EventBus eventBus) {
        this(eventBus, 100);
    }

    /**
     * 完整构造函数
     *
     * @param eventBus 事件总线
     * @param maxCompletedTasks 最大保留已完成任务数
     */
    public SharedTaskList(EventBus eventBus, int maxCompletedTasks) {
        this.tasks = new ConcurrentHashMap<>();
        this.pendingTasks = new ConcurrentHashMap<>();
        this.agentTasks = new ConcurrentHashMap<>();
        this.agentLoad = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.eventBus = eventBus;
        this.maxCompletedTasks = maxCompletedTasks;
        this.completedTaskQueue = new LinkedList<>();
        this.eventListeners = new ArrayList<>();
    }

    // ========== 任务管理 ==========

    /**
     * 添加任务
     *
     * @param task 任务
     * @return 异步结果
     */
    public CompletableFuture<TeamTask> addTask(TeamTask task) {
        return CompletableFuture.supplyAsync(() -> {
            lock.writeLock().lock();
            try {
                // 验证依赖任务存在
                for (String depId : task.getDependencies()) {
                    if (!tasks.containsKey(depId)) {
                        throw new IllegalArgumentException("依赖任务不存在: " + depId);
                    }
                }

                // 添加任务
                tasks.put(task.getId(), task);

                // 如果状态是 PENDING，加入待认领队列
                if (task.isClaimable()) {
                    pendingTasks.put(task.getId(), task);
                }

                LOG.debug("任务已添加: {} (优先级: {})", task.getTitle(), task.getPriority());

                // 触发事件
                notifyTaskAdded(task);
                publishTaskEvent(AgentEventType.TASK_CREATED, task, null);

                return task;

            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    /**
     * 批量添加任务
     *
     * @param tasks 任务列表
     * @return 异步结果
     */
    public CompletableFuture<List<TeamTask>> addTasks(List<TeamTask> tasks) {
        return CompletableFuture.supplyAsync(() -> {
            List<TeamTask> added = new ArrayList<>();
            for (TeamTask task : tasks) {
                added.add(addTask(task).join());
            }
            return added;
        });
    }

    /**
     * 获取任务
     *
     * @param taskId 任务ID
     * @return 任务，不存在返回 null
     */
    public TeamTask getTask(String taskId) {
        lock.readLock().lock();
        try {
            return tasks.get(taskId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 删除任务
     *
     * @param taskId 任务ID
     * @return 是否删除成功
     */
    public boolean removeTask(String taskId) {
        lock.writeLock().lock();
        try {
            TeamTask task = tasks.remove(taskId);
            if (task != null) {
                pendingTasks.remove(taskId);

                // 从 Agent 的任务集合中移除
                if (task.getClaimedBy() != null) {
                    Set<String> agentTaskSet = agentTasks.get(task.getClaimedBy());
                    if (agentTaskSet != null) {
                        agentTaskSet.remove(taskId);
                        updateAgentLoad(task.getClaimedBy());
                    }
                }

                LOG.debug("任务已删除: {}", task.getTitle());
                return true;
            }
            return false;

        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========== 任务认领 ==========

    /**
     * 认领任务
     *
     * @param taskId 任务ID
     * @param agentId Agent ID
     * @return 认领是否成功
     */
    public CompletableFuture<Boolean> claimTask(String taskId, String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            lock.writeLock().lock();
            try {
                TeamTask task = tasks.get(taskId);

                // 验证任务存在
                if (task == null) {
                    LOG.warn("认领失败: 任务不存在 {}", taskId);
                    return false;
                }

                // 验证任务可认领
                if (!task.isClaimable()) {
                    LOG.warn("认领失败: 任务不可认领 {} (状态: {})", task.getTitle(), task.getStatus());
                    return false;
                }

                // 验证依赖任务已完成
                for (String depId : task.getDependencies()) {
                    TeamTask dep = tasks.get(depId);
                    if (dep == null || !dep.isCompleted()) {
                        LOG.warn("认领失败: 依赖任务未完成 {}", depId);
                        return false;
                    }
                }

                // 认领任务
                task.setStatus(TeamTask.Status.IN_PROGRESS);
                task.setClaimedBy(agentId);
                task.setClaimTime(System.currentTimeMillis());

                // 从待认领队列移除
                pendingTasks.remove(taskId);

                // 添加到 Agent 的任务集合
                agentTasks.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet()).add(taskId);
                updateAgentLoad(agentId);

                LOG.info("任务已认领: {} by {}", task.getTitle(), agentId);

                // 触发事件
                notifyTaskClaimed(task, agentId);
                publishTaskEvent(AgentEventType.TASK_CLAIMED, task, agentId);

                return true;

            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    /**
     * 智能认领（自动选择最佳任务）
     *
     * @param agentId Agent ID
     * @return 认领的任务，无任务可认领返回 null
     */
    public CompletableFuture<TeamTask> smartClaim(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            lock.writeLock().lock();
            try {
                // 获取可认领的任务
                List<TeamTask> claimable = getClaimableTasks();

                if (claimable.isEmpty()) {
                    return null;
                }

                // 按优先级排序（高优先级优先）
                claimable.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

                // 选择最高优先级的任务
                TeamTask selected = claimable.get(0);

                // 认领任务
                Boolean claimed = claimTask(selected.getId(), agentId).join();
                return claimed ? selected : null;

            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    /**
     * 释放任务
     *
     * @param taskId 任务ID
     * @param agentId Agent ID
     * @return 是否释放成功
     */
    public boolean releaseTask(String taskId, String agentId) {
        lock.writeLock().lock();
        try {
            TeamTask task = tasks.get(taskId);

            if (task == null) {
                return false;
            }

            // 验证是认领者
            if (!agentId.equals(task.getClaimedBy())) {
                LOG.warn("释放失败: 不是任务的认领者 {}", agentId);
                return false;
            }

            // 重置状态
            task.setStatus(TeamTask.Status.PENDING);
            task.setClaimedBy(null);
            task.setClaimTime(0);

            // 加入待认领队列
            pendingTasks.put(taskId, task);

            // 从 Agent 的任务集合移除
            Set<String> agentTaskSet = agentTasks.get(agentId);
            if (agentTaskSet != null) {
                agentTaskSet.remove(taskId);
                updateAgentLoad(agentId);
            }

            LOG.info("任务已释放: {} by {}", task.getTitle(), agentId);

            // 触发事件
            notifyTaskReleased(task, agentId);
            publishTaskEvent(AgentEventType.TASK_RELEASED, task, agentId);

            return true;

        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========== 任务完成 ==========

    /**
     * 完成任务
     *
     * @param taskId 任务ID
     * @param result 执行结果
     * @return 是否完成成功
     */
    public boolean completeTask(String taskId, Object result) {
        lock.writeLock().lock();
        try {
            TeamTask task = tasks.get(taskId);

            if (task == null) {
                return false;
            }

            String agentId = task.getClaimedBy();

            // 更新任务状态
            task.setStatus(TeamTask.Status.COMPLETED);
            task.setResult(result);
            task.setCompletedTime(System.currentTimeMillis());

            // 从待认领队列移除（如果存在）
            pendingTasks.remove(taskId);

            // 从 Agent 的任务集合移除
            if (agentId != null) {
                Set<String> agentTaskSet = agentTasks.get(agentId);
                if (agentTaskSet != null) {
                    agentTaskSet.remove(taskId);
                    updateAgentLoad(agentId);
                }
            }

            // 添加到已完成队列
            completedTaskQueue.offer(taskId);
            cleanupCompletedTasks();

            LOG.info("任务已完成: {} by {}", task.getTitle(), agentId);

            // 触发事件
            notifyTaskCompleted(task, agentId);
            publishTaskEvent(AgentEventType.TASK_COMPLETED, task, agentId);

            return true;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 失败任务
     *
     * @param taskId 任务ID
     * @param errorMessage 错误信息
     * @return是否标记成功
     */
    public boolean failTask(String taskId, String errorMessage) {
        lock.writeLock().lock();
        try {
            TeamTask task = tasks.get(taskId);

            if (task == null) {
                return false;
            }

            String agentId = task.getClaimedBy();

            // 更新任务状态
            task.setStatus(TeamTask.Status.FAILED);
            task.setErrorMessage(errorMessage);
            task.setCompletedTime(System.currentTimeMillis());

            // 从待认领队列移除
            pendingTasks.remove(taskId);

            // 从 Agent 的任务集合移除
            if (agentId != null) {
                Set<String> agentTaskSet = agentTasks.get(agentId);
                if (agentTaskSet != null) {
                    agentTaskSet.remove(taskId);
                    updateAgentLoad(agentId);
                }
            }

            LOG.warn("任务已失败: {} - {}", task.getTitle(), errorMessage);

            // 触发事件
            notifyTaskFailed(task, agentId, errorMessage);
            publishTaskEvent(AgentEventType.TASK_FAILED, task, agentId);

            return true;

        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========== 查询方法 ==========

    /**
     * 获取所有任务
     *
     * @return 任务列表
     */
    public List<TeamTask> getAllTasks() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(tasks.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取待认领任务
     *
     * @return 任务列表
     */
    public List<TeamTask> getPendingTasks() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(pendingTasks.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取可认领任务（考虑依赖关系）
     *
     * @return 任务列表
     */
    public List<TeamTask> getClaimableTasks() {
        lock.readLock().lock();
        try {
            return pendingTasks.values().stream()
                    .filter(task -> {
                        // 检查依赖任务是否都已完成
                        for (String depId : task.getDependencies()) {
                            TeamTask dep = tasks.get(depId);
                            if (dep == null || !dep.isCompleted()) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取 Agent 的任务列表
     *
     * @param agentId Agent ID
     * @return 任务列表
     */
    public List<TeamTask> getAgentTasks(String agentId) {
        lock.readLock().lock();
        try {
            Set<String> taskIds = agentTasks.getOrDefault(agentId, Collections.emptySet());
            return taskIds.stream()
                    .map(tasks::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取 Agent 负载
     *
     * @param agentId Agent ID
     * @return 负载数（进行中的任务数）
     */
    public int getAgentLoad(String agentId) {
        lock.readLock().lock();
        try {
            return agentLoad.getOrDefault(agentId, 0);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取所有 Agent 负载
     *
     * @return Agent ID -> 负载数
     */
    public Map<String, Integer> getAllAgentLoads() {
        lock.readLock().lock();
        try {
            return new HashMap<>(agentLoad);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 按状态获取任务
     *
     * @param status 状态
     * @return 任务列表
     */
    public List<TeamTask> getTasksByStatus(TeamTask.Status status) {
        lock.readLock().lock();
        try {
            return tasks.values().stream()
                    .filter(task -> task.getStatus() == status)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 按类型获取任务
     *
     * @param type 类型
     * @return 任务列表
     */
    public List<TeamTask> getTasksByType(TeamTask.TaskType type) {
        lock.readLock().lock();
        try {
            return tasks.values().stream()
                    .filter(task -> task.getType() == type)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ========== 统计方法 ==========

    /**
     * 获取任务统计
     *
     * @return 统计信息
     */
    public TaskStatistics getStatistics() {
        lock.readLock().lock();
        try {
            Map<TeamTask.Status, Long> statusCounts = tasks.values().stream()
                    .collect(Collectors.groupingBy(TeamTask::getStatus, Collectors.counting()));

            return new TaskStatistics(
                    tasks.size(),
                    statusCounts.getOrDefault(TeamTask.Status.PENDING, 0L).intValue(),
                    statusCounts.getOrDefault(TeamTask.Status.IN_PROGRESS, 0L).intValue(),
                    statusCounts.getOrDefault(TeamTask.Status.COMPLETED, 0L).intValue(),
                    statusCounts.getOrDefault(TeamTask.Status.FAILED, 0L).intValue(),
                    agentLoad.size()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 任务统计信息
     */
    public static class TaskStatistics {
        public final int totalTasks;
        public final int pendingTasks;
        public final int inProgressTasks;
        public final int completedTasks;
        public final int failedTasks;
        public final int activeAgents;

        public TaskStatistics(int totalTasks, int pendingTasks, int inProgressTasks,
                             int completedTasks, int failedTasks, int activeAgents) {
            this.totalTasks = totalTasks;
            this.pendingTasks = pendingTasks;
            this.inProgressTasks = inProgressTasks;
            this.completedTasks = completedTasks;
            this.failedTasks = failedTasks;
            this.activeAgents = activeAgents;
        }

        @Override
        public String toString() {
            return String.format(
                    "TaskStatistics{总任务=%d, 待认领=%d, 进行中=%d, 已完成=%d, 失败=%d, 活跃Agent=%d}",
                    totalTasks, pendingTasks, inProgressTasks, completedTasks, failedTasks, activeAgents
            );
        }
    }

    // ========== 事件监听 ==========

    /**
     * 添加事件监听器
     *
     * @param listener 监听器
     */
    public void addEventListener(TaskEventListener listener) {
        eventListeners.add(listener);
    }

    /**
     * 移除事件监听器
     *
     * @param listener 监听器
     */
    public void removeEventListener(TaskEventListener listener) {
        eventListeners.remove(listener);
    }

    // ========== 私有方法 ==========

    /**
     * 更新 Agent 负载
     */
    private void updateAgentLoad(String agentId) {
        Set<String> taskSet = agentTasks.get(agentId);
        int load = (taskSet != null) ? taskSet.size() : 0;
        agentLoad.put(agentId, load);
    }

    /**
     * 清理已完成任务
     */
    private void cleanupCompletedTasks() {
        while (completedTaskQueue.size() > maxCompletedTasks) {
            String taskId = completedTaskQueue.poll();
            if (taskId != null) {
                TeamTask task = tasks.get(taskId);
                if (task != null && task.isCompleted()) {
                    tasks.remove(taskId);
                    LOG.debug("清理已完成任务: {}", task.getTitle());
                }
            }
        }
    }

    /**
     * 发布任务事件到 EventBus
     */
    private void publishTaskEvent(AgentEventType eventType, TeamTask task, String agentId) {
        if (eventBus != null) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("taskId", task.getId());
                payload.put("taskTitle", task.getTitle());
                payload.put("agentId", agentId);
                payload.put("status", task.getStatus());
                payload.put("priority", task.getPriority());

                eventBus.publishAsync(eventType, payload);
            } catch (Exception e) {
                LOG.error("发布任务事件失败", e);
            }
        }
    }

    /**
     * 通知任务已添加
     */
    private void notifyTaskAdded(TeamTask task) {
        for (TaskEventListener listener : eventListeners) {
            try {
                listener.onTaskAdded(task);
            } catch (Exception e) {
                LOG.error("任务事件监听器异常", e);
            }
        }
    }

    /**
     * 通知任务已认领
     */
    private void notifyTaskClaimed(TeamTask task, String agentId) {
        for (TaskEventListener listener : eventListeners) {
            try {
                listener.onTaskClaimed(task, agentId);
            } catch (Exception e) {
                LOG.error("任务事件监听器异常", e);
            }
        }
    }

    /**
     * 通知任务已释放
     */
    private void notifyTaskReleased(TeamTask task, String agentId) {
        for (TaskEventListener listener : eventListeners) {
            try {
                listener.onTaskReleased(task, agentId);
            } catch (Exception e) {
                LOG.error("任务事件监听器异常", e);
            }
        }
    }

    /**
     * 通知任务已完成
     */
    private void notifyTaskCompleted(TeamTask task, String agentId) {
        for (TaskEventListener listener : eventListeners) {
            try {
                listener.onTaskCompleted(task, agentId);
            } catch (Exception e) {
                LOG.error("任务事件监听器异常", e);
            }
        }
    }

    /**
     * 通知任务已失败
     */
    private void notifyTaskFailed(TeamTask task, String agentId, String error) {
        for (TaskEventListener listener : eventListeners) {
            try {
                listener.onTaskFailed(task, agentId, error);
            } catch (Exception e) {
                LOG.error("任务事件监听器异常", e);
            }
        }
    }
}
