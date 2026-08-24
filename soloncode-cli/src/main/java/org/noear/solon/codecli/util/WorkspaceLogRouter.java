/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.classic.PatternLayout;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多工作区日志路由器：把原本 JVM 全局唯一的 file appender 替换为按工作区分流的路由 appender。
 *
 * <p>路由依据是 MDC 中的 {@link LogDirUtil#MDC_KEY}（由 WorkspaceFilter / WebGate 在请求与
 * agent 任务入口打标）；无标记的日志（启动阶段、后台线程）落入启动工作区那份文件。</p>
 *
 * <p>app.yml 中 solon.logging.appender.file 已设为 enable:false（全局文件 appender 不再创建），
 * 路由器是唯一的文件写入方；滚动参数从 Solon.cfg() 读取（即 yml 默认值 + settings.json
 * 在启动时同步的 logFileMaxSize/maxHistory），避免重初始化后全局 appender 复活把日志写串目录。</p>
 *
 * <p>若 install 未执行（如单测），则完全没有文件日志，行为与配置一致。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class WorkspaceLogRouter extends AppenderBase<ILoggingEvent> {

    private static final String ROUTER_NAME = "SOLONCODE_WS_ROUTER";

    private final ConcurrentHashMap<String, RollingFileAppender<ILoggingEvent>> appenders = new ConcurrentHashMap<>();

    @Override
    protected void append(ILoggingEvent event) {
        String logKey = event.getMDCPropertyMap().get(LogDirUtil.MDC_KEY);
        if (logKey == null || logKey.isEmpty()) {
            //无标记：落到启动工作区（App.main 写入的 logkey）
            logKey = System.getProperty(LogDirUtil.LOG_KEY_PROP, LogDirUtil.workspaceLogKey());
        }

        try {
            appenders.computeIfAbsent(logKey, WorkspaceLogRouter::buildAppender).doAppend(event);
        } catch (Throwable e) {
            //任何一个工作区的 appender 故障都不能拖垮整个日志链路
            addWarn("Route log failed for workspace key: " + logKey, e);
        }
    }

    private static volatile boolean installed = false;

    /** 当前已安装的路由器实例（releaseWorkspace/rebuildAll 的入口） */
    private static volatile WorkspaceLogRouter instance;

    /**
     * 用工作区路径打标包装任务：在裸线程（如 IM 渠道的 stream/reconnect 线程，
     * 不经过 Reactor 调度器、MDC 传播钩子覆盖不到）入口打上 wskey 标记，
     * 使该线程内的日志正确路由到所属工作区文件，结束后清理标记。
     */
    public static Runnable withWorkspaceLogKey(String workspacePath, Runnable task) {
        return () -> {
            MDC.put(LogDirUtil.MDC_KEY, LogDirUtil.workspaceLogKey(workspacePath));
            try {
                task.run();
            } finally {
                MDC.remove(LogDirUtil.MDC_KEY);
            }
        };
    }

    /**
     * 释放工作区专属的文件 appender（停止并移除，释放文件句柄）。
     * 用于工作区关闭/闲置回收后，避免句柄泄漏（Windows 上还会锁住日志文件）。
     * 未安装路由器或该工作区尚无 appender 时为无操作。
     */
    public static void releaseWorkspace(String workspacePath) {
        WorkspaceLogRouter r = instance;
        if (r == null || workspacePath == null || workspacePath.isEmpty()) {
            return;
        }
        try {
            String logKey = LogDirUtil.workspaceLogKey(workspacePath);
            RollingFileAppender<ILoggingEvent> ap = r.appenders.remove(logKey);
            if (ap != null) {
                ap.stop();
            }
        } catch (Throwable e) {
            //释放失败不能影响工作区关闭链路
        }
    }

    /**
     * 重建所有工作区 appender：停掉现有实例并清空缓存，下一条日志会按当前
     * Solon.cfg()（含 settings 热更新后的滚动参数/级别）懒加载重建。
     * 用于日志配置保存后的热更新（原已创建的 appender 参数固定、不感知配置变化）。
     */
    public static void rebuildAll() {
        WorkspaceLogRouter r = instance;
        if (r == null) {
            return;
        }
        try {
            for (Map.Entry<String, RollingFileAppender<ILoggingEvent>> e : r.appenders.entrySet()) {
                try {
                    e.getValue().stop();
                } catch (Throwable ignored) {
                }
            }
            r.appenders.clear();
        } catch (Throwable e) {
            //重建失败不能影响设置保存链路
        }
    }

    /**
     * 安装路由器：挂到 logback root logger 上，成为唯一的文件写入方（全局 file appender 已在 yml 禁用）。
     * 幂等：重复调用不会重复挂路由器。
     * 需在日志体系初始化完成后调用（App.main 中 Solon.start 之后）。
     */
    public static void install() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        synchronized (WorkspaceLogRouter.class) {
            if (installed) {
                return;
            }

            WorkspaceLogRouter router = new WorkspaceLogRouter();
            router.setName(ROUTER_NAME);
            router.start();
            instance = router;

            synchronized (root) {
                //防御：若 root 上仍残留文件类 appender（如旧配置升级、运行期重初始化），一并摘除
                Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders();
                while (it.hasNext()) {
                    Appender<ILoggingEvent> ap = it.next();
                    if (ap instanceof FileAppender) {
                        root.detachAppender(ap);
                        ap.stop();
                    }
                }
                root.addAppender(router);
            }

            installed = true;
        }
    }

    /**
     * 给 Reactor 调度器安装全局 MDC 传播钩子。
     *
     * <p>MDC 基于 ThreadLocal，任务经 {@code Schedulers.boundedElastic()} 等调度器跳线程后标记即丢失，
     * 路由器拿不到 wskey 会把日志回退到启动工作区文件。此钩子在任务提交时捕获提交线程的 MDC 快照，
     * 在运行线程上恢复、结束后还原，保证 agent 管道任意层 subscribeOn 跳线程都不丢工作区标记。</p>
     *
     * <p>需在首次调度发生前安装（App.main 中 Solon.start 之后立刻调用）。只影响 Reactor 调度器</p>
     */
    public static void installMdcPropagation() {
        reactor.core.scheduler.Schedulers.onScheduleHook("soloncode-mdc", task -> {
            Map<String, String> captured = MDC.getCopyOfContextMap();
            if (captured == null || captured.isEmpty()) {
                return task;
            }
            return () -> {
                Map<String, String> prev = MDC.getCopyOfContextMap();
                MDC.setContextMap(captured);
                try {
                    task.run();
                } finally {
                    if (prev == null) {
                        MDC.remove(LogDirUtil.MDC_KEY);
                    } else {
                        MDC.setContextMap(prev);
                    }
                }
            };
        });
    }

    /**
     * 构建某个工作区专属的滚动文件 appender（参数读取 Solon.cfg()，即 yml 默认值 + settings.json 同步值）
     */
    private static RollingFileAppender<ILoggingEvent> buildAppender(String logKey) {
        File dir = new File(LogDirUtil.logRootDir(), logKey);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory());
        appender.setName("WS-" + logKey);
        appender.setFile(new File(dir, "soloncode.log").getAbsolutePath());

        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(appender.getContext());
        policy.setParent(appender);
        policy.setMaxFileSize(FileSize.valueOf(cfgGet("solon.logging.appender.file.maxFileSize", "10 MB")));
        policy.setMaxHistory(cfgGetInt("solon.logging.appender.file.maxHistory", 7));
        policy.setTotalSizeCap(FileSize.valueOf(cfgGet("solon.logging.appender.file.totalSizeCap", "200 MB")));
        policy.setFileNamePattern(new File(dir, "soloncode_%d{yyyy-MM-dd}_%i.log").getAbsolutePath());
        policy.start();
        appender.setRollingPolicy(policy);

        PatternLayout layout = new PatternLayout();
        layout.setContext(appender.getContext());
        layout.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        layout.start();

        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(appender.getContext());
        encoder.setLayout(layout);
        encoder.setCharset(StandardCharsets.UTF_8);
        appender.setEncoder(encoder);

        ThresholdFilter levelFilter = new ThresholdFilter();
        levelFilter.setContext(appender.getContext());
        levelFilter.setLevel(cfgGet("solon.logging.appender.file.level", "INFO").toUpperCase());
        levelFilter.start();
        appender.addFilter(levelFilter);

        appender.start();
        return appender;
    }

    private static String cfgGet(String key, String def) {
        try {
            String v = org.noear.solon.Solon.cfg().get(key);
            return (v == null || v.isEmpty()) ? def : v;
        } catch (Throwable e) {
            return def;
        }
    }

    private static int cfgGetInt(String key, int def) {
        try {
            return Integer.parseInt(cfgGet(key, String.valueOf(def)).trim());
        } catch (Throwable e) {
            return def;
        }
    }
}
