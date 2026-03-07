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
package features.ai.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.codecli.core.CodeAgent;
import org.noear.solon.ai.codecli.core.CodeProperties;
import org.noear.solon.ai.codecli.core.subagent.SubAgentManager;
import org.noear.solon.ai.codecli.core.subagent.SubAgentType;
import org.noear.solon.ai.codecli.core.tool.ReadSolonDocTool;

/**
 * Solon Code 指南代理测试
 *
 * @author bai
 * @since 3.9.5
 */
public class SolonCodeGuideTest {

    @Test
    public void testSolonCodeGuideAgent() throws Throwable {
        // 创建配置
        CodeProperties config = new CodeProperties();
        config.workDir = "./work";
        config.subAgentEnabled = true;

        // 创建 ChatModel（需要配置 apiUrl, apiKey, model）
        ChatModel chatModel = null; // 这里需要实际配置

        // 创建 CodeAgent
        CodeAgent codeAgent = new CodeAgent(chatModel, null, config);
        codeAgent.prepare();

        // 获取 SubAgentManager
        SubAgentManager manager = codeAgent.getSubAgentManager();

        // 测试 Solon 指南代理
        String question = "Solon 的快速入门方法是什么？";
        String response = manager.getAgent(SubAgentType.SOLON_CODE_GUIDE)
                .execute(Prompt.of(question))
                .getContent();

        System.out.println("Question: " + question);
        System.out.println("Response: " + response);
    }

    @Test
    public void testReadSolonDocTool() throws Throwable {
        // 测试 ReadSolonDocTool
        ReadSolonDocTool tool = new ReadSolonDocTool("work");

        // 列出所有可用文档
        String docList = tool.list();
        System.out.println("Available Solon Documents:");
        System.out.println(docList);

        // 读取特定文档（如果网络可用）
        try {
            String content = tool.fetch("learn-start");
            System.out.println("\nDocument Content (first 200 chars):");
            System.out.println(content.substring(0, Math.min(200, content.length())));
        } catch (Exception e) {
            System.out.println("\nFailed to fetch document (network may be unavailable): " + e.getMessage());
        }
    }
}
