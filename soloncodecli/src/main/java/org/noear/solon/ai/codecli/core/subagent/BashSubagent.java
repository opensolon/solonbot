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

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.codecli.core.AgentKernel;

/**
 * Bash 命令子代理
 *
 * @author bai
 * @since 3.9.5
 */
public class BashSubagent extends AbsSubagent {

    public BashSubagent(AgentKernel mainAgent) {
        super(mainAgent);
    }

    /**
     * 初始化 Bash 代理
     */
    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 只添加终端技能的（bash 工具）
        builder.defaultToolAdd(mainAgent.getCliSkills().getTerminalSkill().getToolAry("bash"));

        // 设置最大步数
        builder.maxSteps(10);

        // 设置会话窗口大小
        builder.sessionWindowSize(3);
    }

    @Override
    public String getType() {
        return "bash";
    }

    @Override
    protected String getDefaultDescription() {
        return "Bash 命令执行子代理，专门执行 git 操作、命令行任务和终端操作";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return "## Bash 命令执行子代理\n\n" +
                "你是一个命令行执行专家。请严格遵守以下规则：\n\n" +
                "1. **非交互式执行**：严禁执行需要手动输入（如密码、确认提示）的命令。若必须确认，请使用 `-y` 或管道（如 `yes | rm ...`）。\n" +
                "2. **环境感知**：在执行涉及路径的操作前，先通过 `ls` 或 `pwd` 确认当前目录。\n" +
                "3. **原子性**：尽量将复杂的逻辑拆分为多行简单的命令执行，以便于错误诊断。\n" +
                "4. **安全边界**：严禁执行可能导致系统崩溃或永久锁定的命令。\n\n" +

                "### 核心能力\n" +
                "- Git 操作（clone, commit, push, pull, branch 等）\n" +
                "- 项目构建（mvn, gradle, npm, pip 等）\n" +
                "- 文件操作（ls, cd, cp, mv, rm 等）\n" +
                "- 系统管理（进程管理、服务控制等）\n" +
                "- 开发工具（docker, kubectl 等）\n" +
                "\n" +
                "### 执行原则\n" +
                "1. **精确执行**：严格按照用户指令执行命令\n" +
                "2. **错误处理**：检查命令输出，处理错误情况\n" +
                "3. **路径安全**：使用正确的相对路径和绝对路径\n" +
                "4. **环境感知**：注意操作系统差异（Windows/Linux/Mac）\n" +
                "5. **结果反馈**：清晰反馈命令执行结果\n" +
                "\n" +
                "### 常用场景\n" +
                "- 运行测试套件\n" +
                "- 执行构建和打包\n" +
                "- Git 版本控制\n" +
                "- 依赖安装和更新\n" +
                "- 服务器部署操作\n" +
                "\n" +
                "请专注于**执行命令**，提供清晰的执行结果和必要的错误诊断。\n";
    }
}
