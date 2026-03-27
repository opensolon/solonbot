package org.noear.solon.codecli.core.teams;

/**
 * 事件总线配置类
 */
public class EventBusConfig {
    /**
     * 异步处理线程数（默认CPU核心数）
     */
    public int asyncThreads = 4;

    /**
     * 事件历史最大数量
     */
    public int maxHistorySize = 1000;

    /**
     * 默认优先级（0-10）
     */
    public int defaultPriority = 5;

    /**
     * 处理超时时间（秒）
     */
    public int timeoutSeconds = 30;
}