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
package org.noear.solon.ai.codecli.core.subagent;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * 抽象子代理实现
 *
 * @author bai
 * @since 3.9.5
 */
public abstract class AbstractSubagent implements Subagent {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractSubagent.class);

    protected final SubagentConfig config;
    protected final AgentSessionProvider sessionProvider;
    protected ReActAgent agent;

    public AbstractSubagent(SubagentConfig config, AgentSessionProvider sessionProvider) {
        this.config = config;
        this.sessionProvider = sessionProvider;
    }

    @Override
    public SubagentType getType() {
        return config.getType();
    }

    @Override
    public SubagentConfig getConfig() {
        return config;
    }

    /**
     * 初始化代理
     */
    protected synchronized void initAgent(ChatModel chatModel,
                                           Consumer<ReActAgent.Builder> configurator) {
        if (agent == null) {
            ReActAgent.Builder builder = ReActAgent.of(chatModel);

            // 设置系统提示词
            String systemPrompt = buildSystemPrompt();
            builder.systemPrompt(SystemPrompt.builder()
                    .instruction(systemPrompt)
                    .build());

            // 应用自定义配置
            if (configurator != null) {
                configurator.accept(builder);
            }

            this.agent = builder.build();
            LOG.info("SubAgent '{}' 初始化完成", config.getCode());
        }
    }

    /**
     * 构建系统提示词（优先从 agents 池读取，否则使用内置提示词）
     */
    protected String buildSystemPrompt() {
        // 1. 尝试从自定义文件读取提示词（如果存在）
        String customPrompt = readCustomPrompt();
        if (customPrompt != null) {
            LOG.info("SubAgent '{}' 使用自定义提示词", config.getCode());
            return customPrompt;
        }

        // 2. 使用内置提示词
        String defaultPrompt = getDefaultSystemPrompt();
        LOG.debug("SubAgent '{}' 使用内置提示词", config.getCode());
        return defaultPrompt;
    }

    /**
     * 获取内置系统提示词（由子类实现）
     */
    protected abstract String getDefaultSystemPrompt();

    /**
     * 导出提示词到默认目录
     */
    public void exportSystemPrompt(String workDir) {
        try {
            String promptDir = ".soloncode" + File.separator + "agents";
            File dir = new File(promptDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String promptFile = promptDir + File.separator + config.getCode() + ".md";
            File file = new File(promptFile);

            // 只在不存在的时才导出，避免覆盖用户自定义的提示词
            if (!file.exists()) {
                String content = getDefaultSystemPrompt();
                Files.write(Paths.get(promptFile), content.getBytes(StandardCharsets.UTF_8));
                LOG.info("SubAgent '{}' 提示词已导出到: {}", config.getCode(), promptFile);
            } else {
                LOG.debug("SubAgent '{}' 提示词文件已存在，跳过导出: {}", config.getCode(), promptFile);
            }
        } catch (Throwable e) {
            LOG.warn("SubAgent '{}' 提示词导出失败: {}", config.getCode(), e.getMessage());
        }
    }

    /**
     * 从文件系统读取自定义提示词
     */
    private String readCustomPrompt() {
        try {
            // 尝试多个位置
            String[] locations = {
                ".soloncode/agents/" + config.getCode() + ".md",  // 项目根目录
                ".soloncode/agents/" + config.getCode() + ".md",  // 相对路径
                config.getWorkDir() + "/.soloncode/agents/" + config.getCode() + ".md"  // work 目录下
            };

            for (String location : locations) {
                File file = new File(location);
                if (file.exists() && file.isFile()) {
                    byte[] bytes = Files.readAllBytes(Paths.get(location));
                    LOG.info("从 {} 读取 SubAgent '{}' 提示词", location, config.getCode());
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }

            LOG.debug("未找到 SubAgent '{}' 的自定义提示词文件", config.getCode());
        } catch (Throwable e) {
            LOG.warn("读取 SubAgent '{}' 自定义提示词失败: {}", config.getCode(), e.getMessage());
        }
        return null;
    }

    /**
     * 获取会话
     */

    /**
     * 获取会话
     */
    protected AgentSession getSession(String sessionId) {
        return sessionProvider.getSession(sessionId);
    }

    @Override
    public AgentResponse execute(Prompt prompt) throws Throwable {
        if (agent == null) {
            throw new IllegalStateException("SubAgent 尚未初始化");
        }

        String sessionId = "subagent_" + config.getCode();
        AgentSession session = getSession(sessionId);

        return agent.prompt(prompt)
                .session(session)
                .call();
    }

    @Override
    public Flux<org.noear.solon.ai.agent.AgentChunk> stream(Prompt prompt) {
        if (agent == null) {
            return Flux.error(new IllegalStateException("SubAgent 尚未初始化"));
        }

        String sessionId = "subagent_" + config.getCode();
        AgentSession session = getSession(sessionId);

        return agent.prompt(prompt)
                .session(session)
                .stream();
    }
}
