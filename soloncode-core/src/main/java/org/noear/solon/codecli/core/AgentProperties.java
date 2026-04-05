package org.noear.solon.codecli.core;

import lombok.Getter;
import lombok.Setter;

import org.noear.solon.ai.harness.HarnessProperties;

import java.nio.file.Paths;

/**
 * 代理属性
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
@Setter
public class AgentProperties extends HarnessProperties {
    public final static String OPENCODE_SKILLS = ".opencode/skills/";
    public final static String CLAUDE_SKILLS = ".claude/skills/";

    public final static String X_SESSION_ID = "X-Session-Id";
    public final static String X_SESSION_CWD = "X-Session-Cwd";
    public final static String X_CTX = "X-Ctx";

    public final static String ARG_SESSION = "session";

    private String uiType = "old";

    private String sessionId = "default"; //默认会话

    private boolean thinkPrinted = false;

    private boolean cliEnabled = true;
    private boolean cliPrintSimplified = true;

    private boolean webEnabled = false;
    private String webEndpoint = "/cli";

    private boolean acpEnabled = false;
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";

    private boolean wsEnabled = false;
    private String wsEndpoint = "/ws";

    public AgentProperties() {
        super(".soloncode/");

        getSkillPools().put("@opencode_skills", Paths.get(getWorkspace(), OPENCODE_SKILLS).toString());
        getSkillPools().put("@claude_skills", Paths.get(getWorkspace(), CLAUDE_SKILLS).toString());
    private String startupSessionMode = "resume";
    private String uiTheme = "solon";
    private Map<String, Map<String, String>> uiThemes;
    }

    public static URL getConfigUrl() throws MalformedURLException {
        //1. 资源文件（一般开发时）
        URL tmp = ResourceUtil.getResource(AgentRuntime.NAME_CONFIG);
        if (tmp != null) {
            return tmp;
        }

        //2. 工作区配置
        Path path = Paths.get(AgentProperties.getUserDir(), AgentRuntime.SOLONCODE, AgentRuntime.NAME_CONFIG);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //3. 用户目录区配置
        path = Paths.get(AgentProperties.getUserHome(), AgentRuntime.SOLONCODE_BIN, AgentRuntime.NAME_CONFIG);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //4. 程序边上的配置文件
        tmp = ResourceUtil.getResourceByFile(AgentRuntime.NAME_CONFIG);
        if (tmp != null) {
            return tmp;
        }

        return null;
    }

    public URL getAgentsUrl() throws MalformedURLException {
        //1. 工作区配置
        Path path = Paths.get(getWorkDir(), AgentRuntime.SOLONCODE, AgentRuntime.NAME_AGENTS);
        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //2. 用户目录区配置
        path = Paths.get(AgentProperties.getUserHome(), AgentRuntime.SOLONCODE_BIN, AgentRuntime.NAME_AGENTS);

        if (Files.exists(path)) {
            return path.toUri().toURL();
        }

        //3. 程序边上的配置文件
        URL tmp = ResourceUtil.getResourceByFile(AgentRuntime.NAME_CONFIG);
        if (tmp != null) {
            return tmp;
        }

        return null;
    }
}