package org.noear.solon.codecli.portal.web;

import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.Solon;

/**
 * 工作区请求拦截器：只信任 X-Workspace-Id 请求头进行多工作区路由。
 *
 * <p>注意：query 参数中出现的 workspaceId/workspace 在 filer/git 接口里表示的是
 * 挂载点别名（mount），与多工作区 ID 是两套语义，此处绝不从 query 读取，
 * 避免挂载别名被误当成工作区路径处理。</p>
 *
 * <p>若 Header 携带的 ws-xxx ID 在历史记录中不存在，返回 404，
 * 引导前端回退到 home.html（对齐 hub.md 场景 C）。</p>
 *
 * @author noear
 */
@Component(index = -90)
public class WorkspaceFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        if (ctx.path().startsWith("/web/")) {
            String wsId = ctx.header("X-Workspace-Id");
            // 防御：若多处代码重复 setRequestHeader，浏览器会合并为 "id, id"，只取第一个合法片段
            if (wsId != null && wsId.contains(",")) {
                wsId = wsId.split(",")[0].trim();
            }

            WorkspaceManager manager = Solon.context().getBean(WorkspaceManager.class);

            // WebSocket 握手（浏览器无法为 WS 设置自定义请求头）
            // 仅对 /web/gate 端点例外地从 query 读取工作区 ID，且必须是合法的 ws-xxx/default，
            // 其余接口仍只信任 Header（filer/git 的 query 参数是挂载别名 mount，语义不同）
            if ((wsId == null || wsId.isEmpty()) && "/web/gate".equals(ctx.path())) {
                String q = ctx.param("workspaceId");
                if (q != null && manager != null && manager.isValidWorkspaceId(q)) {
                    wsId = q;
                }
            }
            if (manager != null) {
                if (wsId != null && !wsId.isEmpty()
                        && !manager.isValidWorkspaceId(wsId)) {
                    // 场景 C：失效的工作区 ID，返回 404 引导前端回 home
                    ctx.status(404);
                    ctx.contentType("application/json;charset=utf-8");
                    ctx.output("{\"code\":1,\"description\":\"WORKSPACE_NOT_FOUND\",\"redirect\":\"/home.html\"}");
                    return;
                }

                WorkspaceContext wctx = manager.getOrCreate(wsId);
                if (wctx == null) {
                    // getOrCreate 收紧语义后可能返回 null（目录不存在/非法 ID/加载失败），
                    // 同样返回 404 引导前端回 home，避免后续控制器 NPE 或静默路由到错误工作区
                    ctx.status(404);
                    ctx.contentType("application/json;charset=utf-8");
                    ctx.output("{\"code\":1,\"description\":\"WORKSPACE_NOT_FOUND\",\"redirect\":\"/home.html\"}");
                    return;
                }
                ctx.attrSet("WORKSPACE_CTX", wctx);
            }
        }
        chain.doFilter(ctx);
    }
}
