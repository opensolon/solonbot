package org.noear.solon.codecli.workspace;

import org.noear.snack4.ONode;
import org.noear.snack4.Feature;
import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.command.builtin.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.ManagerExtension;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.config.entity.*;
import org.noear.solon.codecli.memory.MemoryProvider;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.portal.web.service.FileService;
import org.noear.solon.codecli.portal.web.service.GitService;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.http.HttpConfiguration;
import org.noear.solon.net.http.HttpExtension;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作区管理器，用于统一管理多工作区的生命周期、实例化底层引擎及持久化最近工作区。
 *
 * @author noear
 */
public class WorkspaceManager {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final String WORKSPACES_FILE_PATH = Paths.get(AgentFlags.getUserHome(), ".soloncode", "workspaces.json").toString();

    private final Map<String, WorkspaceContext> contexts = new ConcurrentHashMap<>();

    private final AgentSettings defaultSettings;
    private WorkspaceContext defaultContext;
    private WebGate webGate;

    /**
     * 闲置释放阈值：30 分钟无访问且无连接
     */
    private static final long IDLE_RELEASE_MS = 30 * 60 * 1000L;

    public WorkspaceManager(AgentSettings defaultSettings) {
        this.defaultSettings = defaultSettings;

        // LRU 闲置释放：定期扫描非默认工作区，释放长时间无访问且无 WS 连接的引擎资源
        java.util.concurrent.ScheduledExecutorService sweeper =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "workspace-idle-sweeper");
                    t.setDaemon(true);
                    return t;
                });
        sweeper.scheduleWithFixedDelay(this::releaseIdleWorkspaces, 10, 10, java.util.concurrent.TimeUnit.MINUTES);
    }

    public void setWebGate(WebGate webGate) {
        this.webGate = webGate;
    }

    private void releaseIdleWorkspaces() {
        try {
            long now = System.currentTimeMillis();
            List<String> idleIds = new ArrayList<>();
            for (WorkspaceContext ctx : contexts.values()) {
                if (ctx.getMeta().isDefault()) {
                    continue;
                }
                long last = ctx.getMeta().getLastAccessed();
                if (now - last > IDLE_RELEASE_MS
                        && (ctx.getConnections() == null || ctx.getConnections().isEmpty())) {
                    idleIds.add(ctx.getMeta().getId());
                }
            }
            for (String id : idleIds) {
                LOG.info("[Workspace] Releasing idle workspace: {}", id);
                closeWorkspace(id);
            }
        } catch (Throwable e) {
            LOG.warn("[Workspace] Idle sweep failed: {}", e.getMessage());
        }
    }

    /**
     * 初始化默认工作区
     */
    public synchronized void initDefaultWorkspace() {
        if (defaultContext != null) {
            return;
        }
        String userDir = AgentFlags.getUserDir();
        try {
            WorkspaceMeta meta = new WorkspaceMeta("default", getLastSegment(userDir), userDir, System.currentTimeMillis(), true);
            defaultContext = createWorkspaceContext(meta);
            // default 是虚拟工作区概念（每次启动随 user.dir 变化），不落 workspaces.json
            String normalizedUserDir;
            try {
                normalizedUserDir = Paths.get(userDir).toAbsolutePath().normalize().toString();
            } catch (Exception e) {
                normalizedUserDir = userDir;
            }
            meta.setPath(normalizedUserDir);
            contexts.put("default", defaultContext);
            contexts.put(normalizedUserDir, defaultContext);
        } catch (Exception e) {
            LOG.error("Failed to init default workspace: " + userDir, e);
        }
    }

    /**
     * 获取或创建工作区上下文。
     *
     * @param workspaceIdOrPath 工作区ID或物理绝对路径
     */
    public synchronized WorkspaceContext getOrCreate(String workspaceIdOrPath) {
        if (workspaceIdOrPath == null || workspaceIdOrPath.trim().isEmpty() || "default".equals(workspaceIdOrPath)) {
            if (defaultContext == null) {
                initDefaultWorkspace();
            }
            return defaultContext;
        }

        // 1. 尝试通过 ID 查找内存中已加载的上下文
        WorkspaceContext context = contexts.get(workspaceIdOrPath);
        if (context != null) {
            context.getMeta().setLastAccessed(System.currentTimeMillis());
            return context;
        }

        // 2. 如果是以 "ws-" 开头的 ID，在内存中没找到，则尝试从历史记录 workspaces.json 中寻找匹配的物理路径
        if (workspaceIdOrPath.startsWith("ws-")) {
            for (WorkspaceMeta meta : listWorkspaces()) {
                if (workspaceIdOrPath.equals(meta.getId())) {
                    // 找到了历史记录，沿用原有 meta（保留原 ID）加载，避免生成新 ID 造成历史重复
                    Path histPath;
                    try {
                        histPath = Paths.get(meta.getPath()).toAbsolutePath().normalize();
                    } catch (Exception pe) {
                        LOG.warn("[Workspace] Illegal history path, skip: {} -> {}", meta.getId(), meta.getPath());
                        break;
                    }
                    if (!Files.isDirectory(histPath)) {
                        LOG.warn("[Workspace] History path missing, reject: {} -> {}", meta.getId(), meta.getPath());
                        break;
                    }
                    // 沿用原有 meta（保留原 ID），但把 path 归一化，避免同路径因格式差异匹配失败
                    meta.setPath(histPath.toString());
                    WorkspaceContext ctx;
                    try {
                        ctx = createWorkspaceContext(meta);
                    } catch (Exception e) {
                        LOG.error("[Workspace] Failed to load history workspace: " + meta.getId(), e);
                        break;
                    }
                    meta.setLastAccessed(System.currentTimeMillis());
                    contexts.put(meta.getId(), ctx);
                    contexts.put(histPath.toString(), ctx);
                    return ctx;
                }
            }
            // 如果历史记录里也没有这个 ws-xxx ID，说明是非法或不存在的 ID：
            // 返回 null（绝不回退默认工作区，回退会掩盖错误），由调用方决定 404/失败响应
            LOG.warn("[Workspace] Unknown workspace id, reject: {}", workspaceIdOrPath);
            return null;
        }

        // 3. 此时 workspaceIdOrPath 应该是物理绝对路径。先做参数防护：
        //    挂载别名（@ 开头）、含 .. 的相对路径、非绝对路径均为非法参数，
        //    一律返回 null，绝不据此创建目录。
        if (workspaceIdOrPath.startsWith("@")
                || workspaceIdOrPath.contains("..")
                || Paths.get(workspaceIdOrPath).isAbsolute() == false) {
            LOG.warn("[Workspace] Illegal workspace parameter, reject: {}", workspaceIdOrPath);
            return null;
        }

        Path inputPath;
        try {
            inputPath = Paths.get(workspaceIdOrPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            // 如果解析路径失败，同样返回 null
            LOG.warn("[Workspace] Illegal workspace path, reject: {}", workspaceIdOrPath);
            return null;
        }
        String normalizedPathStr = inputPath.toString();
        context = contexts.get(normalizedPathStr);
        if (context != null) {
            return context;
        }

        // 4. 新物理路径：目录不存在时不再自动创建（早期 bug 会在当前目录下误建目录），
        //    记录 warn 并返回 null，由调用方返回明确错误
        if (!Files.isDirectory(inputPath)) {
            LOG.warn("[Workspace] Directory not exists, reject: {}", normalizedPathStr);
            return null;
        }

        try {
            // 先按物理路径回查历史记录，命中则沿用原 meta（稳定 ID），
            // 避免重启/LRU 回收后重开同一目录时生成新 ws- ID，
            // 导致已渲染的旧卡片上的 ID 失效而 404 跳回默认工作区
            // 注：workspaceId 现已改为路径 md5 生成（幂等），此回查主要用于兼容旧随机 ID 存量
            WorkspaceMeta meta = null;
            for (WorkspaceMeta hist : listWorkspaces()) {
                // 按归一化绝对路径比较，避免同一路径因尾斜杠/相对段等格式差异
                // 匹配失败而生成新 ws- ID（ID 漂移导致旧卡片 404 跳回首页的隐患）
                if (hist.getPath() != null
                        && normalizedPathStr.equals(Paths.get(hist.getPath()).toAbsolutePath().normalize().toString())) {
                    hist.setLastAccessed(System.currentTimeMillis());
                    hist.setPath(normalizedPathStr);
                    meta = hist;
                    break;
                }
            }
            if (meta == null) {
                // 用路径 md5 生成幂等 ID：相同 path 恒得相同 ID，天然避免重开同目录时 ID 漂移
                String workspaceId = "ws-" + Utils.md5(normalizedPathStr);
                meta = new WorkspaceMeta(workspaceId, getLastSegment(normalizedPathStr), normalizedPathStr, System.currentTimeMillis(), false);
            }
            String ctxKey = meta.getId();
            meta.setLastAccessed(System.currentTimeMillis());

            context = createWorkspaceContext(meta);
            contexts.put(ctxKey, context);
            contexts.put(normalizedPathStr, context);
            saveWorkspaceToHistory(meta);
            return context;
        } catch (Exception e) {
            LOG.error("Failed to create workspace context: " + normalizedPathStr, e);
            return null;
        }
    }

    /**
     * 校验工作区 ID 是否有效（存在于内存或 workspaces.json 历史中）。
     * 仅对 ws- 前缀的多工作区 ID 有意义。
     */
    public boolean isValidWorkspaceId(String wsId) {
        if (wsId == null || wsId.trim().isEmpty() || "default".equals(wsId)) {
            return true;
        }
        if (contexts.containsKey(wsId)) {
            return true;
        }
        if (wsId.startsWith("ws-")) {
            for (WorkspaceMeta meta : listWorkspaces()) {
                if (wsId.equals(meta.getId())) {
                    return true;
                }
            }
            return false;
        }
        // 物理路径形式：目录存在即有效
        return Files.isDirectory(Paths.get(wsId));
    }

    /**
     * 获取所有已加载的工作区上下文
     */
    public Collection<WorkspaceContext> getContexts() {
        return contexts.values();
    }

    /**
     * 关闭并销毁工作区上下文
     */
    public synchronized void closeWorkspace(String workspaceIdOrPath) {
        if (workspaceIdOrPath == null || workspaceIdOrPath.trim().isEmpty()) {
            return;
        }
        // default 是虚拟工作区（随启动目录变化），不允许被关闭销毁
        if ("default".equals(workspaceIdOrPath)) {
            return;
        }
        WorkspaceContext context = contexts.remove(workspaceIdOrPath);
        if (context != null) {
            // 同时移除可能存在的其他 Key（如绝对路径或 ID）
            contexts.remove(context.getMeta().getId());
            contexts.remove(context.getMeta().getPath());
            try {
                context.close();
            } catch (IOException e) {
                LOG.error("Failed to close workspace: " + workspaceIdOrPath, e);
            }
        }
    }

    /**
     * 实例化一个新的 WorkspaceContext（仿照 Configurator 中的构建逻辑）
     */
    private WorkspaceContext createWorkspaceContext(WorkspaceMeta meta) throws Exception {
        String workspacePath = meta.getPath();

        // 多工作区配置隔离：非默认工作区按目录加载 global + 工作区覆盖；
        // 默认工作区沿用注入的全局 settings（与 CLI 启动语义一致）
        AgentSettings wsSettings = meta.isDefault()
                ? this.defaultSettings
                : AgentSettings.loadForWorkspace(workspacePath);
        String stealthIdentity = "<!--\n" +
                "  @poweredby: soloncode\n" +
                "  @build: " + AgentFlags.getVersion() + "\n" +
                "-->\n\n";

        // 初始化 HTTP 代理配置
        ProxyConfig.update(wsSettings.getGeneral());
        HttpConfiguration.addExtension(new HttpExtension() {
            @Override
            public void onInit(HttpUtils http, String url) {
                ProxyConfig.applyIfNeeded(http);
            }
        });

        HarnessEngine engine = HarnessEngine.of(workspacePath, AgentFlags.getHarnessHome())
                .userAgent(wsSettings.getGeneral().getUserAgent())
                .systemPrompt(stealthIdentity + AgentFlags.getAgentsMd())
                .maxTurns(wsSettings.getGeneral().getMaxTurns())
                .autoRethink(wsSettings.getGeneral().isAutoRethink())
                .sessionWindowSize(wsSettings.getGeneral().getSessionWindowSize())
                .sessionProvider(new SessionManager(workspacePath))
                .compressionThreshold(wsSettings.getGeneral().getCompressionThresholdMessages(), wsSettings.getGeneral().getCompressionThresholdPercent() / 100.0D)
                .memoryEnabled(wsSettings.getGeneral().isMemoryEnabled())
                .memoryRelevanceCount(wsSettings.getGeneral().getMemoryRelevanceCount())
                .memoryPriorityCount(wsSettings.getGeneral().getMemoryPriorityCount())
                .memorySummaryLength(wsSettings.getGeneral().getMemorySummaryLength())
                .memoryProvider(new MemoryProvider())
                .sandboxEnabled(wsSettings.getGeneral().isSandboxMode())
                .sandboxAllowUserHome(wsSettings.getGeneral().isSandboxAllowUserHome())
                .sandboxSystemRestrict(wsSettings.getGeneral().isSandboxSystemRestrict())
                .bashAsyncEnabled(wsSettings.getGeneral().isBashAsyncEnabled())
                .subagentEnabled(wsSettings.getGeneral().isSubagentEnabled())
                .hitlEnabled(wsSettings.getGeneral().isHitlEnabled())
                .apiRetries(wsSettings.getGeneral().getApiRetries())
                .modelRetries(wsSettings.getGeneral().getModelRetries())
                .mcpRetries(wsSettings.getGeneral().getModelRetries())
                .toolsAdd(wsSettings.getPermission().getTools())
                .disallowedToolsAdd(wsSettings.getPermission().getDisallowedTools())
                .cacheControl(CacheControl.ofEphemeral())
                .httpCustomizeSet(http -> {
                    ProxyConfig.applyIfNeeded(http);
                })
                .build();

        engine.setDefaultModel(wsSettings.getDefaultModel());
        for (ModelDo model : wsSettings.getModels().values()) {
            engine.addModel(model);
        }

        for (Map.Entry<String, MountDo> entry : wsSettings.getMountPools().entrySet()) {
            MountDo mount = entry.getValue();
            engine.addMount(MountDir.builder()
                    .alias(entry.getKey())
                    .description(mount.getDescription())
                    .type(mount.getType())
                    .path(mount.getPath())
                    .primary(mount.isPrimary())
                    .enabled(mount.isEnabled())
                    .writeable(mount.isWriteable())
                    .build());
        }

        engine.addMount(MountDir.builder().alias("@user-skills").type(MountType.SKILLS).path("~/" + engine.getHarnessSkills()).primary(true).build());
        engine.addMount(MountDir.builder().alias("@workspace-skills").type(MountType.SKILLS).path("./" + engine.getHarnessSkills()).primary(true).build());

        engine.addMount(MountDir.builder().alias("@user-agents").type(MountType.AGENTS).path("~/" + engine.getHarnessAgents()).primary(true).build());
        engine.addMount(MountDir.builder().alias("@workspace-agents").type(MountType.AGENTS).path("./" + engine.getHarnessAgents()).primary(true).build());

        // 灌入技能禁用清单
        engine.disallowSkillReset(wsSettings.getPermission().getDisallowedSkills());

        engine.getCommandRegistry().load(Paths.get(AgentFlags.getUserHome(), engine.getHarnessCommands()));
        engine.getCommandRegistry().load(Paths.get(workspacePath, engine.getHarnessCommands()));

        engine.getCommandRegistry().register(new ExitCommand());
        engine.getCommandRegistry().register(new ClearCommand());
        engine.getCommandRegistry().register(new ContinueCommand());
        engine.getCommandRegistry().register(new InterruptCommand());
        engine.getCommandRegistry().register(new RerunCommand());
        engine.getCommandRegistry().register(new RewindCommand());
        engine.getCommandRegistry().register(new ModelCommand());

        engine.getLspTalent().setEnabled(wsSettings.getGeneral().isLspEnabled());

        RunUtil.async(() -> addServers(engine, wsSettings));

        // loop scheduler
        LoopScheduler loopScheduler = new LoopScheduler(engine, wsSettings);

        // 初始化 Goal 验证器
        ValidatorFactory.initDefaults(workspacePath);

        // Goal 模式
        boolean goalsEnabled = wsSettings.getGeneral().isGoalsEnabled();
        GoalExtension goalExtension = new GoalExtension(loopScheduler);
        goalExtension.getGoalTalent().setEnabled(goalsEnabled);
        engine.addExtension(goalExtension);

        LoopCommand loopCommand = new LoopCommand(loopScheduler);
        engine.getCommandRegistry().register(loopCommand);
        engine.getCommandRegistry().register(new GoalCommand(loopCommand));

        engine.addExtension(new ManagerExtension(engine, wsSettings, loopScheduler));

        // 注入 HarnessExtension 扩展
        Solon.context().subBeansOfType(HarnessExtension.class, extension -> {
            engine.addExtension(extension);
        });


        // 为本工作区的 LoopScheduler 就地注册 web 端执行器与忙碌检查（绑定本工作区 webGate），
        // 避免非默认工作区的 loop/goal 任务因缺少 executor 而无法执行。
        registerWebLoopExecutor(engine, meta.getId(), loopScheduler, webGate);

        // FileWatchService
        FileWatchService fileWatchService = new FileWatchService();
        fileWatchService.addRoot("workspace", Paths.get(workspacePath).toAbsolutePath().normalize())
                .addHandler(changes -> webGate.broadcastRaw(meta.getId(), FileWatchService.buildFrontendJson(changes)));

        for (MountDir mount : engine.getMounts()) {
            if (!mount.isEnabled()) continue;
            FileWatchService.WatchRoot root = fileWatchService.addRoot(mount.getAlias(), mount.getRealPath());
            switch (mount.getType()) {
                case FILES:
                    root.addHandler(changes -> webGate.broadcastRaw(meta.getId(), FileWatchService.buildFrontendJson(changes)));
                    break;
                case SKILLS:
                    root.addHandler(changes -> engine.getSkillProvider().refreshByGroup(mount.getAlias()));
                    break;
                case AGENTS:
                    root.addHandler(changes -> engine.getAgentManager().refreshByMountAlias(mount.getAlias()));
                    break;
            }
        }
        fileWatchService.start();

        SessionManager sessionManager = (SessionManager) engine.getSessionProvider();
        FileService fileService = new FileService(workspacePath, engine);
        GitService gitService = new GitService(workspacePath, engine);

        return new WorkspaceContext(meta, engine, sessionManager, fileService, gitService, fileWatchService, loopScheduler, webGate, wsSettings);
    }

    /**
     * 为指定工作区的 LoopScheduler 注册 web 端任务执行器与忙碌检查器。
     *
     * <p>每个工作区拥有独立的 LoopScheduler 与 WebGate，执行器必须就地绑定本工作区的
     * WebGate，以保证定时触发的 AI 响应推送到本工作区的连接池，而非默认工作区。</p>
     */
    private void registerWebLoopExecutor(HarnessEngine engine, String workspaceId, LoopScheduler loopScheduler, WebGate webGate) {
        if (loopScheduler == null || webGate == null) {
            return;
        }

        // 会话繁忙守卫：session 正在执行任务时，loop 定时触发跳过本次执行
        loopScheduler.addBusyChecker(sessionId -> {
            if (sessionId == null || !sessionId.startsWith("web-")) {
                return false;
            }
            return webGate.isSessionBusy(engine, sessionId);
        });

        loopScheduler.addTaskExecutor((sessionId, prompt, agentName) -> {
            if (sessionId == null || !sessionId.startsWith("web-")) {
                return null;
            }
            // 如果指定了 agentName，将 prompt 拼接为 @agentName prompt 格式
            String effectiveInput = prompt;
            if (agentName != null && !agentName.isEmpty()) {
                effectiveInput = "@" + agentName + " " + prompt;
            }
            // Loop 任务可能长时间执行（数小时），使用 Loop 专用无限等待版本
            return webGate.safeChatInputAndCaptureLoop(workspaceId, sessionId, effectiveInput, "Loop");
        });
    }

    private void addServers(HarnessEngine engine, AgentSettings wsSettings) {
        for (Map.Entry<String, McpServerDo> entry : wsSettings.getMcpServers().entrySet()) {
            engine.addMcpServer(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, ApiSourceDo> entry : wsSettings.getApiServers().entrySet()) {
            engine.addApiServer(entry.getValue());
        }

        for (Map.Entry<String, LspServerDo> entry : wsSettings.getLspServers().entrySet()) {
            engine.addLspServer(entry.getKey(), entry.getValue());
        }

        addSystemLspServer(engine, "java", Arrays.asList("jdtls"), Arrays.asList(".java"));
        addSystemLspServer(engine, "typescript", Arrays.asList("typescript-language-server", "--stdio"), Arrays.asList(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts"));
        addSystemLspServer(engine, "go", Arrays.asList("gopls"), Arrays.asList(".go"));
        addSystemLspServer(engine, "python", Arrays.asList("pyright-langserver", "--stdio"), Arrays.asList(".py", ".pyi"));
        addSystemLspServer(engine, "rust", Arrays.asList("rust-analyzer"), Arrays.asList(".rs"));
        addSystemLspServer(engine, "c-cpp", Arrays.asList("clangd", "--background-index", "--clang-tidy"), Arrays.asList(".c", ".h", ".cpp", ".hpp", ".cc", ".cxx", ".hxx", ".c++", ".h++", ".hh"));
        addSystemLspServer(engine, "csharp", Arrays.asList("roslyn-language-server", "--stdio", "--autoLoadProjects"), Arrays.asList(".cs", ".csx"));
        addSystemLspServer(engine, "ruby", Arrays.asList("solargraph", "stdio"), Arrays.asList(".rb", ".rake", ".gemspec", ".ru"));
        addSystemLspServer(engine, "php", Arrays.asList("intelephense", "--stdio"), Arrays.asList(".php"));
        addSystemLspServer(engine, "bash", Arrays.asList("bash-language-server", "start"), Arrays.asList(".sh", ".bash", ".zsh", ".ksh"));
        addSystemLspServer(engine, "lua", Arrays.asList("lua-language-server"), Arrays.asList(".lua"));
        addSystemLspServer(engine, "dart", Arrays.asList("dart", "language-server", "--lsp"), Arrays.asList(".dart"));
        addSystemLspServer(engine, "swift", Arrays.asList("sourcekit-lsp"), Arrays.asList(".swift", ".objc", ".objcpp"));
        addSystemLspServer(engine, "kotlin", Arrays.asList("kotlin-language-server"), Arrays.asList(".kt", ".kts"));
        addSystemLspServer(engine, "yaml", Arrays.asList("yaml-language-server", "--stdio"), Arrays.asList(".yaml", ".yml"));

        // 同步 LSP servers
        for (Map.Entry<String, org.noear.solon.ai.talents.lsp.LspServerParameters> entry : engine.getLspServers().entrySet()) {
            if (!wsSettings.getLspServers().containsKey(entry.getKey())) {
                LspServerDo lspServer = new LspServerDo();
                lspServer.setCommand(entry.getValue().getCommand());
                lspServer.setExtensions(entry.getValue().getExtensions());
                lspServer.setEnabled(entry.getValue().isEnabled());
                wsSettings.getLspServers().put(entry.getKey(), lspServer);
            }
        }
    }

    private void addSystemLspServer(HarnessEngine engine, String name, List<String> command, List<String> extensions) {
        if (defaultSettings.getLspServers().containsKey(name)) {
            return;
        }
        LspServerDo lspServer = new LspServerDo();
        lspServer.setCommand(command);
        lspServer.setExtensions(extensions);
        lspServer.setEnabled(false);
        lspServer.setScope(AgentFlags.SCOPE_LOCAL);
        engine.addLspServer(name, lspServer);
    }

    private static String getLastSegment(String pathStr) {
        Path path = Paths.get(pathStr);
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    /**
     * 获取最近的工作区列表
     */
    public List<WorkspaceMeta> listWorkspaces() {
        // 读取原始条目（map 已按 id 天然唯一，无需再去重）
        Collection<WorkspaceMeta> raw = readWorkspaceEntries();

        // 默认工作区（启动目录）物理路径：用于过滤早期 bug 在默认工作区内部误建目录的脏条目
        String defaultPathStr;
        try {
            defaultPathStr = Paths.get(AgentFlags.getUserDir()).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            defaultPathStr = null;
        }

        List<WorkspaceMeta> result = new ArrayList<>();
        for (WorkspaceMeta w : raw) {
            // 归一化路径，避免同路径因格式差异比较失败
            String normalized;
            try {
                normalized = Paths.get(w.getPath()).toAbsolutePath().normalize().toString();
            } catch (Exception e) {
                continue;
            }
            w.setPath(normalized);
            // 展示层过滤（不回写文件）：
            // a) 物理目录已不存在的脏条目（不删目录本身，只从列表隐去）
            if (!Files.isDirectory(Paths.get(normalized))) {
                LOG.debug("[Workspace] Filter entry (dir missing): {} -> {}", w.getId(), normalized);
                continue;
            }
            // b) path 指向默认工作区目录内部（非默认本身）的误建目录条目
            if (defaultPathStr != null && !w.isDefault()
                    && normalized.startsWith(defaultPathStr + File.separator)) {
                LOG.debug("[Workspace] Filter entry (inside default dir): {} -> {}", w.getId(), normalized);
                continue;
            }
            result.add(w);
        }

        // 按最近访问时间倒序返回（MRU），不再依赖存储的插入顺序
        result.sort((a, b) -> Long.compare(b.getLastAccessed(), a.getLastAccessed()));
        return result;
    }

    /**
     * 从 workspaces.json 读取工作区条目。
     * 存储格式为 object/map：{ "ws-xxx": { name, path, lastAccessed }, ... }，key 即工作区 id。
     */
    private Collection<WorkspaceMeta> readWorkspaceEntries() {
        List<WorkspaceMeta> list = new ArrayList<>();
        try {
            Path file = Paths.get(WORKSPACES_FILE_PATH);
            if (!Files.exists(file)) {
                return list;
            }
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            ONode node = ONode.ofJson(json);
            if (node.isObject()) {
                for (Map.Entry<String, ONode> entry : node.getObject().entrySet()) {
                    String id = entry.getKey();
                    ONode item = entry.getValue();
                    // default 是虚拟工作区，持久化文件中不允许存在（存量脏数据自动清洗）
                    if (item == null || id == null || "default".equals(id)) {
                        continue;
                    }
                    WorkspaceMeta w = new WorkspaceMeta();
                    w.setId(id);
                    w.setName(item.get("name").getString());
                    w.setPath(item.get("path").getString());
                    w.setLastAccessed(item.get("lastAccessed").getLong());
                    if (w.getPath() != null) {
                        list.add(w);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read workspaces", e);
        }
        return list;
    }

    /**
     * 保存工作区历史记录
     */
    private synchronized void saveWorkspaceToHistory(WorkspaceMeta meta) {
        // default 是虚拟工作区，不持久化
        if (meta == null || "default".equals(meta.getId())) {
            return;
        }
        try {
            Path file = Paths.get(WORKSPACES_FILE_PATH);
            if (!Files.exists(file.getParent())) {
                Files.createDirectories(file.getParent());
            }

            // 以 map 形式组织：key=工作区 id（ws-md5(path)，与路径 1:1），value=元数据。
            // id 已与路径幂等，同 id 直接覆盖，无需再按路径 removeIf 去重。
            Map<String, WorkspaceMeta> map = new LinkedHashMap<>();
            for (WorkspaceMeta w : readWorkspaceEntries()) {
                if (w.getId() != null && w.getPath() != null && !"default".equals(w.getId())) {
                    map.put(w.getId(), w);
                }
            }

            map.put(meta.getId(), meta);

            // 序列化为 object：{ "ws-xxx": { name, path, lastAccessed } }（isDefault 为 transient 不落盘）
            String newJson = ONode.ofBean(map, Feature.Write_PrettyFormat).toJson();
            Files.write(file, newJson.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            LOG.warn("Failed to save workspace to history: " + meta.getPath(), e);
        }
    }

    /**
     * 根据 http 上下文获取
     */
    public WorkspaceContext currentContext() {
        Context ctx = Context.current();
        WorkspaceContext wctx = null;
        if (ctx != null) {
            wctx = ctx.attr("WORKSPACE_CTX");
        }

        if (wctx == null) {
            wctx = getOrCreate(null); // 回退默认
        }

        return wctx;
    }
}