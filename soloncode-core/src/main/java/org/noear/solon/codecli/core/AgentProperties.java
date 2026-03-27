/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.core;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.restapi.ApiSource;
import org.noear.solon.codecli.core.teams.*;

import java.io.*;
import java.util.Map;

/**
 * Cli 配置
 *
 * @author noear
 * @since 3.9.1
 */
@Getter
@Setter
public class AgentProperties implements Serializable {
    /**
     * 共享记忆配置
     */
    public boolean sharedMemoryEnabled = false;
    public SharedMemoryConfig sharedMemory = new SharedMemoryConfig();
    /**
     * 事件总线配置
     */
    public boolean eventBusEnabled = false;
    public EventBusConfig eventBus = new EventBusConfig();
    /**
     * 消息通道配置
     */
    public boolean messageChannelEnabled = false;
    public MessageChannelConfig messageChannel = new MessageChannelConfig();
    /**
     * Agent Teams 模式配置
     */
    public boolean teamsEnabled = false;
    public TeamsConfig teams = new TeamsConfig();
    public Map<String, McpServerParameters> mcpServers;
    public ChatConfig chatModel;
    /**
     * SubAgent 模型配置
     * 格式：subAgentCode -> modelName
     * 例如：{"explore": "glm-4-flash", "plan": "glm-4.7"}
     * 如果未配置，将使用默认的 chatModel.model
     */
    public Map<String, String> subAgentModels;
    /**
     * 子代理并发控制配置
     */
    public SubagentConcurrencyConfig subagentConcurrency = new SubagentConcurrencyConfig();
    private String workDir = System.getProperty("user.dir");
    private int maxSteps = 10;
    private boolean maxStepsAutoExtensible = false;
    private String uiType = "old";
    private int sessionWindowSize = 10;
    private int summaryWindowSize = 12;
    private int summaryWindowToken = 15000;
    private boolean sandboxMode = true;
    private boolean thinkPrinted = false;
    private boolean hitlEnabled = false;
    private boolean subagentEnabled = true;
    private boolean agentTeamEnabled = false;
    private boolean browserEnabled = true;
    private boolean cliEnabled = true;
    private boolean cliPrintSimplified = true;
    private boolean webEnabled = false;
    private String webEndpoint = "/cli";
    private boolean acpEnabled = false;
    private String acpTransport = "stdio";
    private String acpEndpoint = "/acp";
    private Map<String, ApiSource> restApis;
    @Deprecated
    private Map<String, String> mountPool;
    private Map<String, String> skillPools;
}
