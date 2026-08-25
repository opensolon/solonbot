package org.noear.solon.codecli.workspace;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.portal.web.service.FileService;
import org.noear.solon.codecli.portal.web.service.GitService;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.util.WorkspaceDataUtil;
import org.noear.solon.net.websocket.WebSocket;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作区上下文，持有属于该工作区的一整套服务与引擎实例。
 *
 * @author noear
 */
public class WorkspaceContext implements Closeable {
    private final WorkspaceMeta meta;
    private final HarnessEngine engine;
    private final Path sessionsRoot;
    private final SessionManager sessionManager;
    private final FileService fileService;
    private final GitService gitService;
    private final FileWatchService fileWatchService;
    private final LoopScheduler loopScheduler;
    private final WorkspaceManager manager;
    private final AgentSettings settings;
    private final List<WebSocket> connections;
    private final ChannelHub channelHub;

    public WorkspaceContext(WorkspaceMeta meta,
                            HarnessEngine engine,
                            SessionManager sessionManager,
                            FileService fileService,
                            GitService gitService,
                            FileWatchService fileWatchService,
                            LoopScheduler loopScheduler,
                            WorkspaceManager manager,
                            AgentSettings settings) {
        this.meta = meta;
        this.engine = engine;
        //以 meta 路径为唯一来源（与 workspaceKey 的哈希源一致），避免 engine workspace 与 meta 出现差异时静默指向两个 key 目录
        this.sessionsRoot = WorkspaceDataUtil.sessionsPath(meta.getPath());
        this.sessionManager = sessionManager;
        this.fileService = fileService;
        this.gitService = gitService;
        this.fileWatchService = fileWatchService;
        this.loopScheduler = loopScheduler;
        this.manager = manager;
        this.settings = settings;
        this.connections = new CopyOnWriteArrayList<>();
        this.channelHub = new ChannelHub(this);
    }

    public WorkspaceMeta getMeta() {
        return meta;
    }

    public AgentSettings getSettings() {
        return settings;
    }

    public HarnessEngine getEngine() {
        return engine;
    }

    public Path getSessionsRoot() {
        return sessionsRoot;
    }

    public Path getSessionPath(String sessionId) {
        return sessionsRoot.resolve(sessionId);
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public FileService getFileService() {
        return fileService;
    }

    public GitService getGitService() {
        return gitService;
    }

    public FileWatchService getFileWatchService() {
        return fileWatchService;
    }

    public LoopScheduler getLoopScheduler() {
        return loopScheduler;
    }

    public WebGate getWebGate() {
        // 动态取值：webGate 由 Configurator 在 webServe 阶段注入 WorkspaceManager，
        // 早于注入创建的默认工作区（IM Link/Loop 执行器等）通过本方法拿到最新实例，
        // 消除初始化顺序导致的 null 快照问题。
        return manager != null ? manager.getWebGate() : null;
    }

    public List<WebSocket> getConnections() {
        return connections;
    }

    public ChannelHub getChannelHub() {
        return channelHub;
    }

    @Override
    public void close() throws IOException {
        // 停止 IM 渠道长连接（微信/飞书/钉钉），释放与外部服务器的连接
        if (channelHub != null) {
            try {
                channelHub.stop();
            } catch (Exception e) {
                // Ignore
            }
        }
        // 停止本工作区全部 loop/goal 定时任务（注销调度，任务文件随 stopAll 清理）
        if (loopScheduler != null) {
            try {
                loopScheduler.shutdown();
            } catch (Exception e) {
                // Ignore
            }
        }
        // 释放 fileWatchService 资源
        if (fileWatchService != null) {
            try {
                fileWatchService.stop();
            } catch (Exception e) {
                // Ignore
            }
        }
        // 清理 WebSocket 连接
        for (WebSocket socket : connections) {
            try {
                socket.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        connections.clear();
    }
}