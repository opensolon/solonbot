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

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocolFactory;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.codecli.core.subagent.AbstractSubAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SubAgent 团队构建器
 *
 * 基于 SubAgents 构建 TeamAgent 的便捷工具。
 *
 * @author bai
 * @since 3.9.5
 */
public class SubAgentAgentBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(SubAgentAgentBuilder.class);

    /**
     * 构建基于 SubAgents 的 TeamAgent
     *
     * @param chatModel LLM 模型
     * @param subAgents SubAgent 列表
     * @param protocolFactory 协作协议工厂
     * @return TeamAgent 实例
     */
    public static TeamAgent buildTeam(
            ChatModel chatModel,
            List<AbstractSubAgent> subAgents,
            TeamProtocolFactory protocolFactory) {

        if (subAgents == null || subAgents.isEmpty()) {
            throw new IllegalArgumentException("SubAgents 列表不能为空");
        }

        LOG.info("正在构建 TeamAgent，协议: {}，成员数: {}",
                protocolFactory.getClass().getSimpleName(), subAgents.size());

        // 1. 创建 TeamAgent Builder
        TeamAgent.Builder builder = TeamAgent.of(chatModel);

        // 2. 将 SubAgents 适配为 Agents 并添加
        for (AbstractSubAgent subAgent : subAgents) {
            Agent adapter = new SubAgentAdapter(subAgent);
            builder.agentAdd(adapter);
            LOG.debug("添加团队成员: {}", subAgent.getConfig().getName());
        }

        // 3. 设置协作协议
        builder.protocol(protocolFactory);

        // 4. 基础配置
        builder.name("soloncode_team");
        builder.maxTurns(30);

        // 5. 构建并返回
        TeamAgent teamAgent = builder.build();
        LOG.info("TeamAgent 构建完成: {}", teamAgent.name());

        return teamAgent;
    }

    /**
     * 使用默认的层级协议构建团队
     */
    public static TeamAgent buildHierarchicalTeam(
            ChatModel chatModel,
            List<AbstractSubAgent> subAgents) {
        return buildTeam(chatModel, subAgents, TeamProtocols.HIERARCHICAL);
    }

    /**
     * 使用蜂群协议构建团队
     */
    public static TeamAgent buildSwarmTeam(
            ChatModel chatModel,
            List<AbstractSubAgent> subAgents) {
        return buildTeam(chatModel, subAgents, TeamProtocols.SWARM);
    }

    /**
     * 使用顺序协议构建团队
     */
    public static TeamAgent buildSequentialTeam(
            ChatModel chatModel,
            List<AbstractSubAgent> subAgents) {
        return buildTeam(chatModel, subAgents, TeamProtocols.SEQUENTIAL);
    }
}
