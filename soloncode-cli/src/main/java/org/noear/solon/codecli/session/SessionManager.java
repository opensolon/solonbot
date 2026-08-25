package org.noear.solon.codecli.session;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.lang.NonNull;
import org.noear.solon.lang.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author noear 2026/7/12 created
 *
 */
public class SessionManager implements AgentSessionProvider {
    private final String workspace;
    private final Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

    public SessionManager() {
        this.workspace = AgentFlags.getUserDir();
    }

    public SessionManager(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public @NonNull AgentSession getSession(String sessionId) {
        // 会话数据存到 ~/.soloncode/workspaces/<工作区标识>/sessions/<sessionId>/（不落在工作区内，避免污染项目目录）

        return sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, WorkspaceDataUtil.sessionsDir(workspace).toPath().resolve(key).normalize().toString()));
    }

    public @Nullable AgentSession removeSession(String sessionId) {
        return sessionMap.remove(sessionId);
    }
}