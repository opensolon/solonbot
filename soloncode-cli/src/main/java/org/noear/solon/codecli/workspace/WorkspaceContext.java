package org.noear.solon.codecli.workspace;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.portal.web.service.FileService;
import org.noear.solon.codecli.portal.web.service.GitService;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.net.websocket.WebSocket;

import java.io.Closeable;
import java.io.IOException;
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
    private final SessionManager sessionManager;
    private final FileService fileService;
    private final GitService gitService;
    private final FileWatchService fileWatchService;
    private final LoopScheduler loopScheduler;
    private final WebGate webGate;
    private final AgentSettings settings;
    /** 工作区连接池：与本上下文的 {@link WebGate} 共享同一引用，由构造方先建后传入 */
    private final List<WebSocket> connections;

    public WorkspaceContext(WorkspaceMeta meta,
                            HarnessEngine engine,
                            SessionManager sessionManager,
                            FileService fileService,
                            GitService gitService,
                            FileWatchService fileWatchService,
                            LoopScheduler loopScheduler,
                            WebGate webGate,
                            AgentSettings settings,
                            List<WebSocket> connections) {
        this.meta = meta;
        this.engine = engine;
        this.sessionManager = sessionManager;
        this.fileService = fileService;
        this.gitService = gitService;
        this.fileWatchService = fileWatchService;
        this.loopScheduler = loopScheduler;
        this.webGate = webGate;
        this.settings = settings;
        this.connections = connections;
    }

    public WorkspaceMeta getMeta() {
        return meta;
    }

    public AgentSettings getSettings() {
        // 优先返回该工作区自己的配置（含 工作区作用域 settings.json 覆盖）；
        // 兼容旧路径：未注入时回退到 WorkspaceManager 的全局配置
        if (settings != null) {
            return settings;
        }
        return org.noear.solon.Solon.context().getBean(org.noear.solon.codecli.workspace.WorkspaceManager.class).getSettings();
    }

    public HarnessEngine getEngine() {
        return engine;
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
        return webGate;
    }

    public List<WebSocket> getConnections() {
        return connections;
    }

    @Override
    public void close() throws IOException {
        // 释放 fileWatchService 资源
        if (fileWatchService != null) {
            try {
                fileWatchService.stop();
            } catch (Exception e) {
                // Ignore
            }
        }
        // harness engine 后台线程等清理（McpServer, LspServer, apiServer）
        if (engine != null) {
            try {
                // engine 没有 close 方法，忽略
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
