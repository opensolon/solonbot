package org.noear.solon.codecli.core;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessProperties;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.core.util.ResourceUtil;

import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 代理属性
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
@Setter
public class AgentProperties extends HarnessProperties {
    private String uiType = "old";

    private boolean thinkPrinted = false;

    private boolean cliEnabled = true;
    private boolean cliPrintSimplified = true;

    private boolean webEnabled = false;
    private String webEndpoint = "/cli";

    private boolean acpEnabled = false;
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";

    private boolean wsEnabled = true;
    private String wsEndpoint = "/ws";

    public AgentProperties() {
        super(".soloncode/");
    }
}