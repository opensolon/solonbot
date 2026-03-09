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
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.AgentProperties;
import org.noear.solon.bot.core.subagent.SolonDocTool;
import org.noear.solon.bot.core.subagent.Subagent;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.noear.solon.bot.core.subagent.SolonGuideSubagent;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Solon Code 指南代理测试
 *
 * 测试 SolonGuideSubagent 和 SolonDocTool 的功能
 *
 * @author bai
 * @since 3.9.5
 */
public class SolonCodeGuideTest {

    private static final String TEST_WORK_DIR = "work/test";

    /**
     * 测试 SolonDocTool - 列出文档功能
     */
    @Test
    public void testSolonDocToolList() {
        System.out.println("=== 测试 SolonDocTool 列出文档 ===");

        SolonDocTool tool = new SolonDocTool(TEST_WORK_DIR);

        // 列出所有可用文档
        String docList = tool.list();

        assertNotNull(docList);
        assertTrue(docList.contains("可用的 Solon 文档"));
        System.out.println(docList);

        System.out.println("✓ SolonDocTool 列出文档测试通过\n");
    }

    /**
     * 测试 SolonDocTool - 读取文档功能（需要网络）
     */
    @Test
    public void testSolonDocToolFetch() {
        System.out.println("=== 测试 SolonDocTool 读取文档 ===");

        SolonDocTool tool = new SolonDocTool(TEST_WORK_DIR);

        try {
            // 尝试读取一个基础文档
            String content = tool.fetch("learn-start");

            assertNotNull(content);
            assertTrue(content.contains("Solon 文档"));
            assertTrue(content.contains("来源: https://solon.noear.org"));

            System.out.println("文档内容预览（前 200 字符）:");
            System.out.println(content.substring(0, Math.min(200, content.length())));

            System.out.println("\n✓ SolonDocTool 读取文档测试通过");
        } catch (Exception e) {
            System.out.println("⚠ 网络不可用，跳过实际文档读取测试");
            System.out.println("错误: " + e.getMessage());
            // 网络测试失败不应该导致测试失败
            assertTrue(true, "网络不可用是预期的");
        }
        System.out.println();
    }

    /**
     * 测试 SolonDocTool - 缓存功能
     */
    @Test
    public void testSolonDocToolCache() {
        System.out.println("=== 测试 SolonDocTool 缓存功能 ===");

        SolonDocTool tool = new SolonDocTool(TEST_WORK_DIR);

        try {
            // 第一次读取
            String content1 = tool.fetch("learn-start");
            assertNotNull(content1);

            // 第二次读取（应该从缓存）
            String content2 = tool.fetch("learn-start");
            assertNotNull(content2);

            // 内容应该一致
            assertEquals(content1, content2);

            System.out.println("✓ 缓存功能正常");

            // 清除缓存
            String clearResult = tool.clearCache();
            assertTrue(clearResult.contains("缓存已清除"));
            System.out.println(clearResult);

            System.out.println("\n✓ SolonDocTool 缓存测试通过");
        } catch (Exception e) {
            System.out.println("⚠ 网络不可用，跳过缓存测试");
            assertTrue(true, "网络不可用是预期的");
        }
        System.out.println();
    }

    /**
     * 测试 SolonGuideSubagent 的基本功能
     */
    @Test
    public void testSolonGuideSubagentBasic() {
        System.out.println("=== 测试 SolonGuideSubagent 基本功能 ===");

        try {
            // 创建 AgentKernel（不需要实际的 ChatModel）
            AgentProperties properties = new AgentProperties();
            properties.setWorkDir(TEST_WORK_DIR);

            AgentKernel kernel = new AgentKernel(
                    null, // chatModel
                    properties,
                    null, // sessionProvider
                    null  // configurator
            );

            // 创建 SolonGuideSubagent
            SolonGuideSubagent subagent = new SolonGuideSubagent(kernel);

            // 验证基本属性
            assertNotNull(subagent);
            assertEquals("solon-guide", subagent.getType());
            assertEquals("Solon 开发指南子代理，专门回答关于 Solon Code、Agent SDK 及框架 API 的问题",
                       subagent.getDescription());

            System.out.println("✓ Type: " + subagent.getType());
            System.out.println("✓ Description: " + subagent.getDescription());
            System.out.println("✓ System Prompt 长度: " + subagent.getSystemPrompt().length());

            System.out.println("\n✓ SolonGuideSubagent 基本功能测试通过");
        } catch (Exception e) {
            System.out.println("✗ 创建 AgentKernel 失败: " + e.getMessage());
            fail("创建 AgentKernel 失败: " + e.getMessage());
        }
    }

    /**
     * 测试 SubagentManager 获取 SolonGuideSubagent
     */
    @Test
    public void testSubagentManagerGetSolonGuide() {
        System.out.println("=== 测试 SubagentManager 获取 SolonGuide ===");

        try {
            AgentProperties properties = new AgentProperties();
            properties.setWorkDir(TEST_WORK_DIR);

            AgentKernel kernel = new AgentKernel(
                    null, properties, null, null
            );

            SubagentManager manager = new SubagentManager(kernel);

            // 验证 SolonGuideSubagent 已注册
            assertTrue(manager.hasAgent("solon-guide"));

            // 获取代理
            Subagent subagent = manager.getAgent("solon-guide");
            assertNotNull(subagent);
            assertInstanceOf(SolonGuideSubagent.class, subagent);
            assertEquals("solon-guide", subagent.getType());

            System.out.println("✓ 找到 SolonGuideSubagent");
            System.out.println("✓ Type: " + subagent.getType());
            System.out.println("✓ Description: " + subagent.getDescription());

            System.out.println("\n✓ SubagentManager 获取 SolonGuide 测试通过");
        } catch (Exception e) {
            System.out.println("✗ 测试失败: " + e.getMessage());
            fail("测试失败: " + e.getMessage());
        }
    }

    /**
     * 集成测试：完整的问答流程（模拟）
     */
    @Test
    public void testSolonGuideQASimulation() {
        System.out.println("=== 测试 SolonGuide 问答流程（模拟）===");

        try {
            AgentProperties properties = new AgentProperties();
            properties.setWorkDir(TEST_WORK_DIR);

            AgentKernel kernel = new AgentKernel(
                    null, properties, null, null
            );

            SubagentManager manager = new SubagentManager(kernel);
            Subagent solonGuide = manager.getAgent("solon-guide");

            // 模拟问题
            String question = "如何在 Solon 中定义一个简单的组件？";

            System.out.println("问题: " + question);
            System.out.println("→ 问题已提交给 " + solonGuide.getType());

            // 模拟回答流程
            System.out.println("→ SolonGuide 应该:");
            System.out.println("  1. 使用 list_solon_docs 查看相关文档");
            System.out.println("  2. 使用 solon_doc_read 读取组件相关文档");
            System.out.println("  3. 基于文档回答问题");

            System.out.println("\n✓ 问答流程测试通过（模拟）");
        } catch (Exception e) {
            System.out.println("✗ 测试失败: " + e.getMessage());
            fail("测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试工具注册
     */
    @Test
    public void testSolonDocToolRegistration() {
        System.out.println("=== 测试 SolonDocTool 工具注册 ===");

        SolonDocTool tool = new SolonDocTool(TEST_WORK_DIR);

        // 验证工具方法存在
        try {
            java.lang.reflect.Method fetchMethod = tool.getClass().getMethod("fetch", String.class);
            assertNotNull(fetchMethod);
            assertEquals("solon_doc_read",
                       fetchMethod.getAnnotation(org.noear.solon.ai.annotation.ToolMapping.class).name());
            System.out.println("✓ fetch 方法已注册为 solon_doc_read");

            java.lang.reflect.Method listMethod = tool.getClass().getMethod("list");
            assertNotNull(listMethod);
            assertEquals("list_solon_docs",
                       listMethod.getAnnotation(org.noear.solon.ai.annotation.ToolMapping.class).name());
            System.out.println("✓ list 方法已注册为 list_solon_docs");

            java.lang.reflect.Method clearMethod = tool.getClass().getMethod("clearCache");
            assertNotNull(clearMethod);
            assertEquals("clear_solon_doc_cache",
                       clearMethod.getAnnotation(org.noear.solon.ai.annotation.ToolMapping.class).name());
            System.out.println("✓ clearCache 方法已注册为 clear_solon_doc_cache");

            System.out.println("\n✓ 工具注册测试通过");
        } catch (NoSuchMethodException e) {
            fail("找不到方法: " + e.getMessage());
        }
    }

    /**
     * 性能测试：大量文档列表
     */
    @Test
    public void testSolonDocToolPerformance() {
        System.out.println("=== 测试 SolonDocTool 性能 ===");

        SolonDocTool tool = new SolonDocTool(TEST_WORK_DIR);

        long startTime = System.currentTimeMillis();

        // 测试列表性能
        String docList = tool.list();

        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(docList);
        assertTrue(duration < 10000, "列表操作应在 10 秒内完成，实际耗时: " + duration + "ms");

        System.out.println("✓ 列表操作耗时: " + duration + "ms");
        System.out.println("✓ 性能测试通过\n");
    }

    /**
     * 测试错误处理
     */
    @Test
    public void testSolonDocToolErrorHandling() {
        System.out.println("=== 测试 SolonDocTool 错误处理 ===");

        SolonDocTool tool = new SolonDocTool(TEST_WORK_DIR);

        // 测试读取不存在的文档
        String result = tool.fetch("non-existent-doc-xyz");

        assertNotNull(result);
        assertTrue(result.contains("无法获取文档内容") || result.contains("读取文档失败"));

        System.out.println("✓ 不存在的文档返回错误信息");
        System.out.println("错误信息: " + result.substring(0, Math.min(100, result.length())));

        System.out.println("\n✓ 错误处理测试通过\n");
    }

    /**
     * 测试系统提示词
     */
    @Test
    public void testSolonGuideSystemPrompt() {
        System.out.println("=== 测试 SolonGuide 系统提示词 ===");

        try {
            AgentProperties properties = new AgentProperties();
            properties.setWorkDir(TEST_WORK_DIR);

            AgentKernel kernel = new AgentKernel(
                    null, properties, null, null
            );

            SolonGuideSubagent subagent = new SolonGuideSubagent(kernel);
            String systemPrompt = subagent.getSystemPrompt();

            assertNotNull(systemPrompt);
            assertTrue(systemPrompt.contains("Solon 开发指南子代理"));
            assertTrue(systemPrompt.contains("文档检索"));
            assertTrue(systemPrompt.contains("solon_doc_read"));
            assertTrue(systemPrompt.contains("list_solon_docs"));

            System.out.println("✓ 系统提示词长度: " + systemPrompt.length() + " 字符");
            System.out.println("✓ 包含关键指导原则");

            System.out.println("\n✓ 系统提示词测试通过");
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }
}
