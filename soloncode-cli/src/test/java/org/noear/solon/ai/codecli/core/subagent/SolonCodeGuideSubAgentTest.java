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

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.codecli.core.tool.ReadSolonDocTool;

/**
 * Solon Code 指南代理测试
 *
 * @author bai
 * @since 3.9.5
 */
public class SolonCodeGuideSubAgentTest {

    /**
     * 测试 ReadSolonDocTool 工具
     */
    @Test
    public void testReadSolonDocTool() {
        System.out.println("=== 测试 ReadSolonDocTool ===\n");

        ReadSolonDocTool tool = new ReadSolonDocTool("work");

        // 1. 列出所有可用文档
        System.out.println("1. 列出所有可用的 Solon 文档:");
        String docList = tool.list();
        System.out.println(docList);
        System.out.println("\n" + "============================================================" + "\n");

        // 2. 测试读取文档（如果网络可用）
        System.out.println("2. 尝试读取 Solon 快速入门文档:");
        try {
            String content = tool.fetch("learn-start");

            // 显示前 500 个字符
            int previewLength = Math.min(500, content.length());
            System.out.println("文档内容预览（前 " + previewLength + " 字符）:");
            System.out.println(content.substring(0, previewLength));
            System.out.println("\n... (文档总长度: " + content.length() + " 字符)");

        } catch (Exception e) {
            System.out.println("读取文档失败: " + e.getMessage());
            System.out.println("这可能是网络问题，请检查网络连接。");
        }
        System.out.println("\n" + "============================================================" + "\n");

        // 3. 测试缓存功能
        System.out.println("3. 测试缓存功能（再次读取同一文档）:");
        try {
            long startTime = System.currentTimeMillis();
            String content = tool.fetch("learn-start");
            long endTime = System.currentTimeMillis();

            System.out.println("第二次读取耗时: " + (endTime - startTime) + " ms");
            System.out.println("文档长度: " + content.length() + " 字符");
            System.out.println("（应该使用缓存，速度更快）");
        } catch (Exception e) {
            System.out.println("读取失败: " + e.getMessage());
        }
        System.out.println("\n" + "============================================================" + "\n");

        // 4. 清除缓存
        System.out.println("4. 清除文档缓存:");
        String clearResult = tool.clearCache();
        System.out.println(clearResult);
    }

    /**
     * 测试不同的文档名称
     */
    @Test
    public void testDifferentDocuments() {
        System.out.println("=== 测试不同的 Solon 文档 ===\n");

        ReadSolonDocTool tool = new ReadSolonDocTool("work");

        String[] docNames = {
                "learn-start",
                "learn-features",
                "agent-quick-start"
        };

        for (String docName : docNames) {
            System.out.println("尝试读取: " + docName);
            try {
                String content = tool.fetch(docName);
                System.out.println("✓ 成功读取，文档长度: " + content.length() + " 字符");

                // 显示第一行
                String[] lines = content.split("\n");
                if (lines.length > 0) {
                    System.out.println("  标题: " + lines[0]);
                }
            } catch (Exception e) {
                System.out.println("✗ 读取失败: " + e.getMessage());
            }
            System.out.println();
        }
    }

    /**
     * 测试错误处理
     */
    @Test
    public void testErrorHandling() {
        System.out.println("=== 测试错误处理 ===\n");

        ReadSolonDocTool tool = new ReadSolonDocTool("work");

        // 测试不存在的文档
        System.out.println("1. 尝试读取不存在的文档:");
        String result = tool.fetch("non-existent-doc");
        System.out.println(result);
        System.out.println();

        // 测试空文档名称
        System.out.println("2. 测试空文档名称:");
        result = tool.fetch("");
        System.out.println(result);
    }
}
