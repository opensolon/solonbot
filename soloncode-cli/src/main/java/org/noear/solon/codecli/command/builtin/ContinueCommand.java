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
package org.noear.solon.codecli.command.builtin;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.core.util.Assert;

import java.util.List;

/**
 * /continue 命令
 *
 * <pre>
 * 用法:
 *   /continue                   → 继续当前会话最后一个未完成的任务
 *   /continue &lt;sessionId&gt;       → 继续指定会话中最后一个未完成的任务（IM 跨会话控制）
 * </pre>
 *
 * @author noear
 * @since 2026.4.28
 */
public class ContinueCommand implements Command {
    @Override
    public String name() {
        return "continue";
    }

    @Override
    public String description() {
        return "继续运行最后一个未完成的任务";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/continue",
                "/continue <sessionId>"
        };
    }

    @Override
    public void execute(CommandContext ctx) throws Exception {
        String sessionId = ctx.argAt(0);
        AgentSession session;

        if (Assert.isNotEmpty(sessionId)) {
            session = ctx.getEngine().getSession(sessionId);
        } else {
            session = ctx.getSession();
            sessionId = session.getSessionId();
        }

        if (session == null) {
            ctx.println("会话不存在：" + sessionId);
            return;
        }

        ReActTrace trace = session.getContext().getAs(AgentFlags.TRACE_KEY_MAIN);

        // 前置拦截：无可继续的任务时，以命令回执形式提示并直接返回（不调用 runAgentTask）。
        // trace 为 null：rewind 后 __main 被移除、会话重启/刷新后轨迹未还原；
        // originalPrompt 为空：__main 是从未跑过任务的空壳轨迹（如恢复会话时被惰性创建）。
        // 此两种情况下 runAgentTask(null, null) 只会产生空 Prompt —— ReActAgent 命中
        // "Prompt is empty!" 分支后不经推理直接空转结束，前端表现为「点了没反应、马上结束」。
        if (trace == null || Prompt.isEmpty(trace.getOriginalPrompt())) {
            ctx.println("没有可继续的任务");
            return;
        }

        if (Agent.ID_END.equals(trace.getRoute())) {
            // 说明有结束节点，重新回到思考点
            trace.setRoute(ReActAgent.ID_REASON);
            trace.setFinalAnswer(null, false);

            /* 只能删「本轮自己产出的最终回答」：判据除了类型，还要带当前 runId 且不带 toolCalls。
             * 只判类型会误删历史：本轮无结果时（ReActAgent 在 result 为空且无 media 时不写最终回答），
             * WorkingMemory / Session 末条可能是上一轮载入的历史 AssistantMessage，删它等于删真实对话历史。 */
            String runId = trace.getRunId();

            ChatMessage workMessage = trace.getWorkingMemory().getLastMessage();
            if (isOwnFinalAnswer(workMessage, runId)) {
                trace.getWorkingMemory().removeLastMessage();
            }

            // 回退一条 ai 消息（要重新生成）
            List<ChatMessage> messageList = session.getMessages();
            if (Assert.isNotEmpty(messageList)
                    && isOwnFinalAnswer(messageList.get(messageList.size() - 1), runId)) {
                session.removeLatestMessage(1);
            }
        }

        ctx.runAgentTask(null, null);
    }

    /**
     * 是否是「本轮产出的最终回答」：带当前 runId 的普通 AssistantMessage。
     *
     * <p>带 toolCalls 的不算（它是推理中间步，删它会断开工具调用链）；
     * 无 runId 的不算（老数据或外部写入，宁可不删）。</p>
     */
    private boolean isOwnFinalAnswer(ChatMessage message, String runId) {
        if (!(message instanceof AssistantMessage) || Assert.isEmpty(runId)) {
            return false;
        }
        AssistantMessage am = (AssistantMessage) message;
        if (am.isToolCalls()) {
            return false;
        }
        Object msgRunId = (am.getMetadata() == null) ? null : am.getMetadata().get(AgentTrace.META_RUN_ID);
        return msgRunId != null && runId.equals(String.valueOf(msgRunId));
    }
}
