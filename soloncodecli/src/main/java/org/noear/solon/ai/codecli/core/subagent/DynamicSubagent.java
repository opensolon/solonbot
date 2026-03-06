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
import org.noear.solon.ai.codecli.core.tool.CodeSearchTool;
import org.noear.solon.ai.codecli.core.tool.WebfetchTool;
import org.noear.solon.ai.codecli.core.tool.WebsearchTool;
import org.noear.solon.core.util.Assert;

/**
 * 动态子代理 - 从 MD 文件动态加载提示词
 *
 * @author bai
 * @since 3.9.5
 */
public class DynamicSubagent extends AbsSubagent {

    private final String subagentType;


    public DynamicSubagent(AgentKernel mainAgent, String subagentType) {
        super(mainAgent);

        this.subagentType = subagentType;
    }

    /**
     * 初始化动态代理
     */
    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 添加所有核心技能
        builder.defaultSkillAdd(mainAgent.getCliSkills());

        // 添加网络工具
        builder.defaultToolAdd(WebfetchTool.getInstance());
        builder.defaultToolAdd(WebsearchTool.getInstance());

        // 添加代码搜索工具（与主代理共享能力）
        builder.defaultToolAdd(CodeSearchTool.getInstance());

        // 设置最大步数
        builder.maxSteps(25);

        // 设置会话窗口大小
        builder.sessionWindowSize(10);
    }

    @Override
    public String getType() {
        return subagentType;
    }

    @Override
    protected String getDefaultDescription() {
        return "自定义代理: " + subagentType;
    }

    @Override
    protected String getDefaultSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 动态子代理 (Type: ").append(subagentType).append(")\n\n");
        sb.append("你是一个专业且高效的任务执行代理。\n\n");
        sb.append("## 基本工作准则：\n");
        sb.append("1. 充分利用提供的工具完成目标。\n");
        sb.append("2. 保持逻辑严密，每步操作后都要验证结果。\n");
        sb.append("3. 如果发现工具输出不符合预期，请及时调整策略。\n");

        return sb.toString();
    }
}