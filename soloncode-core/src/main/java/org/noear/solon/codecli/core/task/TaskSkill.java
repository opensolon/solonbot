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
package org.noear.solon.codecli.core.task;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.core.AgentRuntime;
import org.noear.solon.codecli.core.agent.AgentDefinition;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.List;

/**
 * 子代理技能
 *
 * 将子代理能力暴露为可调用的工具（Claude Code Subagent 类似实现）
 *
 * @author bai
 * @since 3.9.5
 */
public class TaskSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(TaskSkill.class);

    private final AgentRuntime agentRuntime;

    public TaskSkill(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Override
    public String description() {
        return "子代理管理专家：委派任务给专门的子代理（explore、plan、bash等）";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 当前可用的子代理注册表\n");
        sb.append("<available_agents>\n");
        for (AgentDefinition agentDefinition : agentRuntime.getAgentManager().getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agentDefinition.getName(), agentDefinition.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("**提醒**： 如果以上子代理不满足需求，可通过 generate_agent 生态生成新的子代理。");

        return sb.toString();
    }

    @ToolMapping(name = "task", description =
            "分派任务给专项子代理。所有实际开发工作必须使用此工具委派给子代理完成。")
    public String task(@Body TaskOp taskSpec, String __cwd, String __sessionId) {
        AgentSession __parentSession = agentRuntime.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());

        return taskDo(__parentTrace, __cwd, taskSpec, false);
    }

    @ToolMapping(name = "multitask", description =
            "并行执行多个子任务。仅当任务之间完全独立（如：同时修改两个无关的模块、并行进行多项搜索）时使用。")
    public String multitask(@Param(name = "tasks", description = "任务列表") List<TaskOp> tasks, String __cwd, String __sessionId) {
        if (Assert.isEmpty(tasks)) {
            return "WARNING: 任务列表为空";
        }

        AgentSession __parentSession = agentRuntime.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (TaskOp task : tasks) {
            // 使用 RunUtil.io() 是正确的，因为这主要是 I/O 密集型（等待 AI 响应）
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                    taskDo(__parentTrace, __cwd, task, true), RunUtil.io());
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .handle((v, ex) -> {
                    StringBuilder compositeResult = new StringBuilder("## 多任务执行报告\n\n");
                    for (int i = 0; i < futures.size(); i++) {
                        TaskOp task = tasks.get(i);
                        compositeResult.append(String.format("### 任务 %d: [%s]\n", i + 1, task.getName()));
                        try {
                            // 即使 ex 不为 null，也要尝试获取每个 future 的结果
                            String result = futures.get(i).getNow("ERROR: 任务未完成");
                            compositeResult.append(result).append("\n\n");
                        } catch (Exception e) {
                            compositeResult.append("执行异常: ").append(e.getMessage()).append("\n\n");
                        }
                    }
                    return compositeResult.toString();
                }).join();
    }

    private String taskDo(ReActTrace __parentTrace, String __cwd, TaskOp task, boolean isMultitask) {
        AgentDefinition agentDefinition = agentRuntime.getAgentManager().getAgent(task.getName());
        if (agentDefinition == null) {
            return "ERROR: 未知的子代理类型 '" + task.getName() + "'。";
        }

        try {
            String result = null;
            ReActAgent agent = agentDefinition.builder(agentRuntime).build();
            final AgentSession session;

            if (Assert.isEmpty(task.getTaskId())) {
                session = InMemoryAgentSession.of(agent.name());
            } else {
                session = agentRuntime.getSession(task.getTaskId());
            }

            String finalSessionId = session.getSessionId();

            LOG.info("分派任务 -> 类型: {}, 会话: {}, 描述: {}", task.getName(), finalSessionId, task.getDescription());

            if (__parentTrace.getOptions().getStreamSink() == null) {
                // 同步模式
                AgentResponse response = agent.prompt(task.getPrompt())
                        .session(session)
                        .options(o -> {
                            o.toolContextPut("__cwd", __cwd);
                        })
                        .call();

                __parentTrace.getMetrics().addMetrics(response.getMetrics());
                result = response.getContent();
            } else {
                // 流式模式
                ReActChunk response = (ReActChunk) agent.prompt(task.getPrompt())
                        .session(session)
                        .options(o -> {
                            o.toolContextPut("__cwd", __cwd);
                        })
                        .stream()
                        .doOnNext(chunk -> {
                            if (chunk instanceof ActionEndChunk) {
                                __parentTrace.getOptions().getStreamSink().next(chunk);
                            } else if (chunk instanceof ReasonChunk) {
                                if (isMultitask == false) {
                                    __parentTrace.getOptions().getStreamSink().next(chunk);
                                }
                            }
                        })
                        .blockLast();

                __parentTrace.getMetrics().addMetrics(response.getMetrics());
                result = response.getContent();
            }

            LOG.info("子代理任务完成: {}", finalSessionId);

            return String.format(
                    "task_id: %s\n" +
                            "name: %s\n" +
                            "\n" +
                            "<task_result>\n" +
                            "%s\n" +
                            "</task_result>",
                    finalSessionId, task.getName(), result != null ? result : "(无输出)"
            );
        } catch (Throwable e) {
            LOG.error("子代理[{}]执行失败: {}", task.getName(), e.getMessage(), e);
            return String.format("ERROR: 子代理 '%s' 执行失败: %s", task.getName(), e.getMessage());
        }
    }
}