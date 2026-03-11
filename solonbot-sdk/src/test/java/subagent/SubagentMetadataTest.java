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
package subagent;

import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.subagent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Subagent 元数据测试
 *
 * 测试内置 Subagent 的 getMetadata() 方法返回的元数据
 *
 * @author bai
 * @since 3.9.5
 */
public class SubagentMetadataTest {

    private AgentKernel kernel;

    @BeforeEach
    public void setUp() {
        // 初始化 AgentKernel（测试用）
        // kernel = new AgentKernel(...);
    }

    /**
     * 测试 ExploreSubagent 元数据
     */
    @Test
    public void testExploreSubagentMetadata() {
        ExploreSubagent agent = new ExploreSubagent(kernel);
        SubAgentMetadata metadata = agent.getMetadata();

        // 基本属性
        assertEquals("explore", metadata.getCode());
        assertEquals("探索子代理", metadata.getName());
        assertTrue(metadata.isEnabled());
        assertEquals(15, metadata.getMaxTurns());

        // 工具
        List<String> tools = metadata.getTools();
        assertTrue(tools.contains("ls"));
        assertTrue(tools.contains("read"));
        assertTrue(tools.contains("grep"));
        assertTrue(tools.contains("glob"));
        assertTrue(tools.contains("codesearch"));
        assertFalse(tools.contains("write"));  // 不包含写工具
        assertFalse(tools.contains("bash"));    // 不包含 bash

        // 技能
        List<String> skills = metadata.getSkills();
        assertTrue(skills.contains("expert"));
        assertTrue(skills.contains("lucene"));

        // 输出 YAML 格式
        System.out.println("ExploreSubagent YAML:");
        System.out.println(metadata.toYamlFrontmatter());
        System.out.println();
    }

    /**
     * 测试 PlanSubagent 元数据
     */
    @Test
    public void testPlanSubagentMetadata() {
        PlanSubagent agent = new PlanSubagent(kernel);
        SubAgentMetadata metadata = agent.getMetadata();

        // 基本属性
        assertEquals("plan", metadata.getCode());
        assertEquals("计划子代理", metadata.getName());
        assertEquals(20, metadata.getMaxTurns());

        // 工具 - 包含网络搜索
        List<String> tools = metadata.getTools();
        assertTrue(tools.contains("websearch"));
        assertTrue(tools.contains("webfetch"));
        assertTrue(tools.contains("codesearch"));

        // 技能
        List<String> skills = metadata.getSkills();
        assertTrue(skills.contains("expert"));
        assertTrue(skills.contains("lucene"));

        // 输出 YAML 格式
        System.out.println("PlanSubagent YAML:");
        System.out.println(metadata.toYamlFrontmatter());
        System.out.println();
    }

    /**
     * 测试 BashSubagent 元数据
     */
    @Test
    public void testBashSubagentMetadata() {
        BashSubagent agent = new BashSubagent(kernel);
        SubAgentMetadata metadata = agent.getMetadata();

        // 基本属性
        assertEquals("bash", metadata.getCode());
        assertEquals("Bash 命令子代理", metadata.getName());
        assertEquals(10, metadata.getMaxTurns());

        // 工具 - 最小工具集
        List<String> tools = metadata.getTools();
        assertEquals(3, tools.size());
        assertTrue(tools.contains("ls"));
        assertTrue(tools.contains("read"));
        assertTrue(tools.contains("bash"));

        // 技能 - 无额外技能
        List<String> skills = metadata.getSkills();
        assertTrue(skills.isEmpty());

        // 输出 YAML 格式
        System.out.println("BashSubagent YAML:");
        System.out.println(metadata.toYamlFrontmatter());
        System.out.println();
    }

    /**
     * 测试 GeneralPurposeSubagent 元数据
     */
    @Test
    public void testGeneralPurposeSubagentMetadata() {
        GeneralPurposeSubagent agent = new GeneralPurposeSubagent(kernel);
        SubAgentMetadata metadata = agent.getMetadata();

        // 基本属性
        assertEquals("general-purpose", metadata.getCode());
        assertEquals("通用子代理", metadata.getName());
        assertEquals(25, metadata.getMaxTurns());

        // 工具 - 包含所有工具
        List<String> tools = metadata.getTools();
        assertTrue(tools.contains("ls"));
        assertTrue(tools.contains("read"));
        assertTrue(tools.contains("write"));
        assertTrue(tools.contains("edit"));
        assertTrue(tools.contains("bash"));
        assertTrue(tools.contains("websearch"));
        assertTrue(tools.contains("webfetch"));
        assertTrue(tools.contains("codesearch"));

        // 技能 - 包含所有技能
        List<String> skills = metadata.getSkills();
        assertTrue(skills.contains("terminal"));
        assertTrue(skills.contains("expert"));
        assertTrue(skills.contains("lucene"));
        assertTrue(skills.contains("todo"));
        assertTrue(skills.contains("code"));

        // 输出 YAML 格式
        System.out.println("GeneralPurposeSubagent YAML:");
        System.out.println(metadata.toYamlFrontmatter());
        System.out.println();
    }

    /**
     * 测试元数据导出功能
     */
    @Test
    public void testMetadataExport() {
        ExploreSubagent agent = new ExploreSubagent(kernel);
        SubAgentMetadata metadata = agent.getMetadata();

        // 测试 YAML frontmatter 导出
        String yaml = metadata.toYamlFrontmatter();
        assertNotNull(yaml);
        assertTrue(yaml.contains("code: explore"));
        assertTrue(yaml.contains("tools:"));
        assertTrue(yaml.contains("skills:"));

        // 测试带提示词的完整导出
        String fullExport = metadata.toYamlFrontmatterWithPrompt("# 系统提示词");
        assertNotNull(fullExport);
        assertTrue(fullExport.contains("---"));
        assertTrue(fullExport.contains("# 系统提示词"));

        System.out.println("完整导出示例:");
        System.out.println(fullExport);
    }

    /**
     * 测试工具和技能对照表
     */
    @Test
    public void testToolsAndSkillsMatrix() {
        System.out.println("\n=== 内置 Subagent 工具和技能对照表 ===\n");

        System.out.println("工具对照表:");
        System.out.println("| 工具 | Explore | Plan | Bash | General | Solon |");
        System.out.println("|------|---------|------|------|---------|-------|");
        printToolRow("ls");
        printToolRow("read");
        printToolRow("write");
        printToolRow("edit");
        printToolRow("grep");
        printToolRow("glob");
        printToolRow("bash");
        printToolRow("websearch");
        printToolRow("webfetch");
        printToolRow("codesearch");

        System.out.println("\n技能对照表:");
        System.out.println("| 技能 | Explore | Plan | Bash | General | Solon |");
        System.out.println("|------|---------|------|------|---------|-------|");
        printSkillRow("terminal");
        printSkillRow("expert");
        printSkillRow("lucene");
        printSkillRow("todo");
        printSkillRow("code");
    }

    private void printToolRow(String tool) {
        String[] marks = new String[5];
        marks[0] = hasTool("explore", tool);
        marks[1] = hasTool("plan", tool);
        marks[2] = hasTool("bash", tool);
        marks[3] = hasTool("general-purpose", tool);
        marks[4] = hasTool("solon-guide", tool);

        System.out.printf("| %s | %s | %s | %s | %s | %s |\n",
                tool, marks[0], marks[1], marks[2], marks[3], marks[4]);
    }

    private void printSkillRow(String skill) {
        String[] marks = new String[5];
        marks[0] = hasSkill("explore", skill);
        marks[1] = hasSkill("plan", skill);
        marks[2] = hasSkill("bash", skill);
        marks[3] = hasSkill("general-purpose", skill);
        marks[4] = hasSkill("solon-guide", skill);

        System.out.printf("| %s | %s | %s | %s | %s | %s |\n",
                skill, marks[0], marks[1], marks[2], marks[3], marks[4]);
    }

    private String hasTool(String agentType, String tool) {
        Subagent agent = createAgent(agentType);
        return agent.getMetadata().getTools().contains(tool) ? "✅" : "❌";
    }

    private String hasSkill(String agentType, String skill) {
        Subagent agent = createAgent(agentType);
        return agent.getMetadata().getSkills().contains(skill) ? "✅" : "❌";
    }

    private Subagent createAgent(String type) {
        switch (type) {
            case "explore": return new ExploreSubagent(kernel);
            case "plan": return new PlanSubagent(kernel);
            case "bash": return new BashSubagent(kernel);
            case "general-purpose": return new GeneralPurposeSubagent(kernel);
            default: throw new IllegalArgumentException("Unknown agent type: " + type);
        }
    }

    /**
     * 测试元数据一致性
     */
    @Test
    public void testMetadataConsistency() {
        // 确保 getType() 和 getCode() 一致
        ExploreSubagent exploreAgent = new ExploreSubagent(kernel);
        assertEquals(exploreAgent.getType(), exploreAgent.getMetadata().getCode());

        PlanSubagent planAgent = new PlanSubagent(kernel);
        assertEquals(planAgent.getType(), planAgent.getMetadata().getCode());

        BashSubagent bashAgent = new BashSubagent(kernel);
        assertEquals(bashAgent.getType(), bashAgent.getMetadata().getCode());

        GeneralPurposeSubagent generalAgent = new GeneralPurposeSubagent(kernel);
        assertEquals(generalAgent.getType(), generalAgent.getMetadata().getCode());

    }

    /**
     * 测试元数据完整性
     */
    @Test
    public void testMetadataCompleteness() {
        Subagent[] agents = {
            new ExploreSubagent(kernel),
            new PlanSubagent(kernel),
            new BashSubagent(kernel),
            new GeneralPurposeSubagent(kernel),
        };

        for (Subagent agent : agents) {
            SubAgentMetadata metadata = agent.getMetadata();

            // 检查必需字段
            assertNotNull(metadata.getCode(), "Code 不能为 null");
            assertNotNull(metadata.getName(), "Name 不能为 null");
            assertNotNull(metadata.getDescription(), "Description 不能为 null");
            assertTrue(metadata.isEnabled(), "默认应该是启用的");

            // 检查工具和技能列表
            assertNotNull(metadata.getTools(), "Tools 列表不能为 null");
            assertNotNull(metadata.getSkills(), "Skills 列表不能为 null");

            System.out.printf("✅ %s 元数据完整%n", metadata.getCode());
        }
    }
}
