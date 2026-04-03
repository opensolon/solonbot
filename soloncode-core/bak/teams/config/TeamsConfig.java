package org.noear.solon.codecli.core.teams.config;

/**
 * Agent Teams 模式配置类
 */
public class TeamsConfig {
    /**
     * 任务操作线程池大小
     * 默认：CPU核心数 * 2，最小10
     */
    public Integer taskExecutorThreads;

    /**
     * 事件发布线程池大小
     * 默认：1（单线程）
     */
    public Integer eventExecutorThreads;

    /**
     * 最大保留已完成任务数
     * 默认：100
     */
    public int maxCompletedTasks = 100;

    /**
     * 最大依赖深度限制
     * 默认：100
     */
    public int maxDependencyDepth = 100;
}
