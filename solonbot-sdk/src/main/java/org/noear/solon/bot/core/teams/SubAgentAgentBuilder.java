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
package org.noear.solon.bot.core.teams;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.bot.core.subagent.Subagent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * SubAgent 团队构建器
 *
 * 基于 SubAgents 构建团队协作的便捷工具。
 *
 * 注意：此类的当前实现提供了基本的团队管理功能。
 * 完整的 TeamAgent 支持可能在未来的 Solon AI 版本中提供。
 *
 * @author bai
 * @since 3.9.5
 */
public class SubAgentAgentBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(SubAgentAgentBuilder.class);

    private final ChatModel chatModel;
    private final List<Subagent> subAgents = new ArrayList<>();

    private SubAgentAgentBuilder(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 创建构建器
     */
    public static SubAgentAgentBuilder of(ChatModel chatModel) {
        return new SubAgentAgentBuilder(chatModel);
    }

    /**
     * 添加子代理
     */
    public SubAgentAgentBuilder addAgent(Subagent subAgent) {
        if (subAgent != null) {
            this.subAgents.add(subAgent);
            LOG.debug("添加团队成员: {}", subAgent.getType());
        }
        return this;
    }

    /**
     * 添加多个子代理
     */
    public SubAgentAgentBuilder addAgents(List<Subagent> agents) {
        if (agents != null) {
            for (Subagent agent : agents) {
                addAgent(agent);
            }
        }
        return this;
    }

    /**
     * 构建团队
     *
     * @return MainAgent 实例作为团队协调器
     */
    public MainAgent build() {
        if (subAgents.isEmpty()) {
            throw new IllegalStateException("至少需要一个团队成员");
        }

        LOG.info("构建团队，成员数: {}", subAgents.size());

        // 创建 MainAgent 作为团队协调器
        // 注意：这里简化了实现，实际使用时需要通过 AgentKernel 创建
        LOG.warn("SubAgentAgentBuilder.build() 需要通过 AgentKernel 集成使用");

        return null; // 需要通过 AgentKernel 创建
    }

    /**
     * 获取子代理列表
     */
    public List<Subagent> getSubAgents() {
        return new ArrayList<>(subAgents);
    }

    /**
     * 获取聊天模型
     */
    public ChatModel getChatModel() {
        return chatModel;
    }
}
