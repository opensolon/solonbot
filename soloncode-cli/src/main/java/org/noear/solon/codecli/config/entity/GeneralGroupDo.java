package org.noear.solon.codecli.config.entity;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 *
 * @author noear 2026/5/31 created
 *
 */
@Getter
@Setter
public class GeneralGroupDo implements Serializable {
    //会话历史窗口大小（即，新指令时使用几条历史消息）
    private int sessionWindowSize = 8;
    //上下文压缩触发消息数（达到这个数，就开始触发）
    private int summaryWindowSize = 100;
    //上下文压缩触发上下文比例（百分比，达到这个比例，就开始触发）
    private int compressionThresholdPercent = 75;

    //启用沙盒模式
    private boolean sandboxMode = true;
    //沙盒允许访问用户主目录
    private boolean sandboxAllowUserHome = true;
    //沙盒使用系统接口限制
    private boolean sandboxSystemRestrict = false;

    //api 重试次数
    private int apiRetries = 3;
    //Mcp 重试次数
    private int mcpRetries = 3;
    //模型重试次数
    private int modelRetries = 3;

    //启用异步终端（增加上下文消耗，非编码用户建议关闭）
    private boolean bashAsyncEnabled = false;
    //启用心智记忆（跨会话长期记忆）
    private boolean memoryEnabled = true;
    //启用心智记忆隔离（按工作区隔离长期记忆）【已废弃，请使用 memoryScope】
    @Deprecated
    private boolean memoryIsolation = true;

    //心智记忆作用域：workspace(工作区本地，默认) / user(用户全局)
    private String memoryScope = "workspace";

    //是否接入 MCP 服务
    private boolean mcpEnabled = true;
    //是否接入 OpenAPI 服务
    private boolean openApiEnabled = true;
    //启用LSP代码智能（增加上下文消耗，非编码用户建议关闭）
    private boolean lspEnabled = true;

    //------------

    //http 用户代理
    private String userAgent = "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; SolonCode/1.0 like claude-code; +https://solon.noear.org/)";

    //最大回合
    private int maxTurns = 20; // 20
    //自我反思
    private boolean autoRethink = true; //true

    //是否启用人工审核危险操作
    private boolean hitlEnabled = false; //false
    //是否启用子代理模式
    private boolean subagentEnabled = true; // true

    //内心思考，是否打印
    private boolean cliThinkPrinted = false; //true
    //控制台打印是否简化
    private boolean cliPrintSimplified = true; //true

    //是否启用 Goal 模式（Codex CLI 对齐的长任务目标模式）
    private boolean goalsEnabled = true; // true

    //当前激活的皮肤（default / 预置名 / 本地安装名；空或 default 表示默认）
    private String activeSkin;


    //===================

    //Web 访问认证用户名（登录页用，留空则不启用）
    private String webAuthUser;
    //Web 访问认证密码（登录页用，留空则不启用）
    private String webAuthPass;

    //===================

    //日志级别 (TRACE/DEBUG/INFO/WARN/ERROR)
    private String logLevel;
    //日志文件大小限制，如 "10MB"
    private String logFileMaxSize;
    //日志存档保留周期（天）
    private Integer logMaxHistory;
}
