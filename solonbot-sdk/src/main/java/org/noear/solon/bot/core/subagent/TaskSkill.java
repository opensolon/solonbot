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
package org.noear.solon.bot.core.subagent;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.noear.solon.annotation.Param;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
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

    private final AgentKernel mainAgent;
    private final SubagentManager manager;

    public TaskSkill(AgentKernel mainAgent, SubagentManager manager) {
        this.mainAgent = mainAgent;
        this.manager = manager;
    }


    @Override
    public String description() {
        return "战略任务调度与子代理委派专家";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 战略调度协议 (Orchestration Protocol)\n");
        sb.append("你拥有派生专项子代理的能力。对于重型或涉及全局认知的任务，你必须扮演【调度员】而非【执行者】。\n\n");

        sb.append("### 强制委派准则\n");
        sb.append("- **项目认知**: 凡是涉及“探索项目”、“分析架构”、“查找核心入口”等需要阅读多个文件或理解代码库的任务，应委派给子代理。\n");
        sb.append("- **复杂变更**: 涉及跨文件的代码修复、重构或需要运行测试验证的任务，应委派给子代理。\n");
        sb.append("- **决策量化**: 预感需要连续调用超过 3 次原子工具（如 grep, read_file）时，应改用子代理以节省主对话上下文。\n\n");

        sb.append("### 可用的子代理注册表 (Capabilities Registry)\n");
        sb.append("请根据任务语义匹配最合适的 `subagent_type`：\n");
        sb.append("<available_agents>\n");
        for (Subagent agent : manager.getAgents()) {
            sb.append(String.format("  <agent subagent_type=\"%s\" capability=\"%s\" />\n",
                    agent.getType(), agent.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("### 调用约定\n");
        sb.append("- **上下文对齐**: 子代理看不见当前历史。必须在 `prompt` 中通过 <context> 标签传入必要的类名、报错或路径。\n");
        sb.append("- **示例**: `task(subagent_type=\"explore\", prompt=\"分析 demo-web 核心架构\", description=\"架构探索\")`\n");
        sb.append("- **动态创建 Agent**: 可以使用 `create_agent` 工具动态创建新的子代理，自定义其行为和技能。\n");

        return sb.toString();
    }


    @ToolMapping(name = "task", description = "派生并分派任务给专项子代理")
    public String handle(
            @Param(name = "subagent_type", description = "子代理类型") String subagent_type,
            @Param(name = "prompt",description = "具体指令。必须包含任务目标、关键类名或必要的背景上下文。") String prompt,
            @Param(name = "description", required = false, description = "简短的任务描述（3-5个词），给用户看") String description,
            @Param(name = "taskId", required = false, description = "可选。若要继续之前的任务会话，请传入对应的 task_id") String taskId,
            String __cwd,
            String __sessionId
    ) {
        AgentSession __parentSession = mainAgent.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getSnapshot());

        try {
            Subagent agent = manager.getAgent(subagent_type);

            if (agent == null) {
                return "ERROR: 未知的子代理类型 '" + subagent_type + "'。请从 <available_agents> 列表中选择。";
            }

            // 1. 会话标识处理：如果有 task_id 则沿用，否则生成基于类型的标识
            String finalSessionId = Assert.isEmpty(taskId)
                    ? "subagent_" + subagent_type + "_" + System.currentTimeMillis()
                    : taskId;

            LOG.info("分派任务 -> 类型: {}, 会话: {}, 描述: {}", subagent_type, finalSessionId, description);

            // 2. 这里的 AgentSession 逻辑应与 AgentKernel 深度绑定
            // 注意：execute 内部应能识别 sessionId 并从 sessionManager 恢复上下文

            String result = null;

            if (__parentTrace.getOptions().getStreamSink() == null) {
                //没有流信息（同步）
                AgentResponse response = agent.call(__cwd, finalSessionId, Prompt.of(prompt));
                result = response.getContent();

                //累计 tokens 计数
                __parentTrace.getMetrics().addMetrics(response.getMetrics());
            } else {
                ReActChunk chunk1 = (ReActChunk) agent.stream(__cwd, finalSessionId, Prompt.of(prompt))
                        .doOnNext(chunk -> {
                            if(chunk instanceof ActionChunk) {
                                __parentTrace.getOptions().getStreamSink().next(chunk);
                            }
                        })
                        .blockLast();

                result = chunk1.getContent();
                //累计 tokens 计数
                __parentTrace.getMetrics().addMetrics(chunk1.getResponse().getMetrics());
            }


            LOG.info("子代理任务完成: {}", finalSessionId);

            // 3. 结构化输出，方便主代理识别结果并知道如何继续
            return String.format(
                    "task_id: %s\n" +
                            "subagent_type: %s\n" +
                            "\n" +
                            "<task_result>\n" +
                            "%s\n" +
                            "</task_result>",
                    finalSessionId, subagent_type, result
            );

        } catch (Throwable e) {
            LOG.error("子代理执行崩溃: type={}, error={}", subagent_type, e.getMessage(), e);
            return "ERROR: 子代理执行失败: " + e.getMessage();
        }
    }

    /**
     * 动态创建子代理工具
     *
     * 允许主 Agent 动态创建新的子代理，自定义其行为和技能。
     * 创建的 agent 定义会保存到文件，并立即注册到 SubagentManager 中。
     */
    @ToolMapping(name = "create_agent",
                 description = "动态创建一个新的子代理。创建的子代理将保存到 .soloncode/agents/ 目录并立即可用。")
    public String createAgent(
            @Param(name = "code", description = "子代理的唯一标识码（如 'my-custom-agent'）") String code,
            @Param(name = "name", description = "子代理的显示名称") String name,
            @Param(name = "description", description = "子代理的功能描述") String description,
            @Param(name = "systemPrompt", description = "子代理的系统提示词（行为指令）") String systemPrompt,
            @Param(name = "model", required = false, description = "可选。指定使用的模型（如 'gpt-4'）") String model,
            @Param(name = "tools", required = false, description = "可选。允许使用的工具列表，逗号分隔（如 'read,write,grep'）") String tools,
            @Param(name = "skills", required = false, description = "可选。启用的技能列表，逗号分隔") String skills,
            @Param(name = "maxTurns", required = false, description = "可选。最大对话轮数限制") Integer maxTurns,
            @Param(name = "saveToFile", required = false, description = "可选。是否保存到文件（默认 true）") Boolean saveToFile,
            String __cwd
    ) {
        try {
            // 1. 构建 SubAgentMetadata
            SubAgentMetadata metadata = new SubAgentMetadata();
            metadata.setCode(code);
            metadata.setName(name);
            metadata.setDescription(description);
            metadata.setEnabled(true);

            // 可选参数
            if (model != null && !model.isEmpty()) {
                metadata.setModel(model);
            }
            if (tools != null && !tools.isEmpty()) {
                metadata.setTools(Arrays.asList(tools.split(",\\s*")));
            }
            if (skills != null && !skills.isEmpty()) {
                metadata.setSkills(Arrays.asList(skills.split(",\\s*")));
            }
            if (maxTurns != null && maxTurns > 0) {
                metadata.setMaxTurns(maxTurns);
            }

            // 2. 生成完整的 agent 定义（YAML frontmatter + system prompt）
            String agentDefinition = metadata.toYamlFrontmatterWithPrompt(systemPrompt);

            // 3. 保存到文件（默认保存）
            boolean shouldSave = saveToFile == null || saveToFile;
            String filePath = null;

            if (shouldSave) {
                Path agentsDir = Paths.get(__cwd, ".soloncode", "agents");

                // 确保目录存在
                if (!Files.exists(agentsDir)) {
                    Files.createDirectories(agentsDir);
                    LOG.info("创建 agents 目录: {}", agentsDir);
                }

                // 使用 code 作为文件名
                String fileName = code + ".md";
                Path agentFile = agentsDir.resolve(fileName);
                filePath = agentFile.toString();


                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                Files.newOutputStream(agentFile.toFile().toPath()),
                                StandardCharsets.UTF_8))) {
                    writer.write(agentDefinition);
                }
                LOG.info("Agent 定义已保存到: {}", filePath);
            }

            // 4. 动态创建并注册新的子代理
            AbsSubagent newAgent = new GeneralPurposeSubagent(mainAgent, code);
            newAgent.setDescription(description);
            newAgent.setSystemPrompt(agentDefinition);
            newAgent.refresh();

            // 注册到 manager
            manager.addSubagent(newAgent);

            LOG.info("动态创建子代理成功: code={}, name={}", code, name);

            // 5. 返回结果
            StringBuilder result = new StringBuilder();
            result.append("✅ 子代理创建成功！\n\n");
            result.append(String.format("**代码**: %s\n", code));
            result.append(String.format("**名称**: %s\n", name));
            result.append(String.format("**描述**: %s\n", description));

            if (filePath != null) {
                result.append(String.format("**文件**: %s\n", filePath));
            }

            result.append(String.format("\n现在可以使用 `task(subagent_type=\"%s\", prompt=\"...\")` 来调用这个子代理。\n", code));

            return result.toString();

        } catch (Throwable e) {
            LOG.error("创建子代理失败: code={}, error={}", code, e.getMessage(), e);
            return "ERROR: 创建子代理失败: " + e.getMessage();
        }
    }
}