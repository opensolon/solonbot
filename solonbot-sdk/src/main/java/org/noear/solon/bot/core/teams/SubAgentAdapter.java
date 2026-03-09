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
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.bot.core.subagent.Subagent;

/**
 * SubAgent 到 Agent 的适配器
 *
 * 将现有的 SubAgent 适配为 Solon AI Agent 接口，
 * 以便在需要 Agent 接口的地方使用。
 *
 * @author bai
 * @since 3.9.5
 */
public class SubAgentAdapter implements Agent {
    private final Subagent subAgent;

    public SubAgentAdapter(Subagent subAgent) {
        this.subAgent = subAgent;
    }

    @Override
    public String name() {
        return subAgent.name();
    }

    @Override
    public String role() {
        String desc = subAgent.getDescription();
        return desc != null ? desc : subAgent.name();
    }

    @Override
    public AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable {
        // 调用 SubAgent 的执行逻辑
        AgentResponse response = subAgent.execute(session.getSessionId(), "", prompt);

        // 转换为 AssistantMessage
        String text = response != null ? response.getText() : "";
        return AssistantMessage.of(text);
    }

    /**
     * 获取底层的 SubAgent
     */
    public Subagent getSubAgent() {
        return subAgent;
    }
}
