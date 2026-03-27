package org.noear.solon.codecli.core.teams.config;

/**
 * 共享记忆配置类
 */
public class SharedMemoryConfig {
    /**
     * 短期记忆TTL（毫秒，默认1小时）
     */
    public long shortTermTtl = 3600_000L;

    /**
     * 长期记忆TTL（毫秒，默认7天）
     */
    public long longTermTtl = 7 * 24 * 3600_000L;

    /**
     * 清理间隔（毫秒，默认5分钟）
     */
    public long cleanupInterval = 300_000L;

    /**
     * 写入时立即持久化
     */
    public boolean persistOnWrite = true;

    /**
     * 短期记忆最大数量
     */
    public int maxShortTermCount = 1000;

    /**
     * 长期记忆最大数量
     */
    public int maxLongTermCount = 500;
}
