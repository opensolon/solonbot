package org.noear.solon.codecli.core.teams.config;

/**
 * 子代理并发控制配置类
 * 用于控制同时发起的子代理请求数量，避免触发API速率限制
 */
public class SubagentConcurrencyConfig {
    /**
     * 最大并发子代理数
     * 默认：1（串行执行，避免触发速率限制）
     * 设置为 2-3 可以适当提高性能，但可能触发速率限制
     */
    public int maxConcurrent = 2;

    /**
     * 子代理调用间隔（毫秒）
     * 默认：1000ms（1秒）
     * 在串行模式下，每次调用后等待的时间
     */
    public long callIntervalMs = 1000L;

    /**
     * 获取执行许可的超时时间（毫秒）
     * 默认：60000ms（60秒）
     * 当达到并发限制时，等待获取许可的最长时间
     */
    public long acquireTimeoutMs = 60000L;

}