package org.noear.solon.codecli.core.teams.config;

/**
 * 消息通道配置类
 */
public class MessageChannelConfig {
    /**
     * 处理线程数
     */
    public Integer threads;

    /**
     * 默认消息TTL（毫秒，默认60秒）
     */
    public long defaultTtl = 60_000L;

    /**
     * 每个代理的最大队列长度
     */
    public int maxQueueSize = 1000;

    /**
     * 是否持久化消息
     */
    public boolean persistMessages = true;
}
