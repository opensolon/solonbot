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

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.bot.core.subagent.SubAgentMetadata;
import org.noear.solon.bot.core.subagent.SubagentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 团队命名建议工具
 *
 * 为现有团队提供更好的命名建议
 *
 * @author bai
 * @since 3.9.5
 */
public class TeamNameSuggestionTool {
    private static final Logger LOG = LoggerFactory.getLogger(TeamNameSuggestionTool.class);

    private final SubagentManager manager;

    public TeamNameSuggestionTool(SubagentManager manager) {
        this.manager = manager;
    }

    /**
     * 为团队提供命名建议
     */
    @ToolMapping(name = "suggest_team_name",
                 description = "为现有团队或即将创建的团队提供更好的命名建议。分析团队成员的角色和职责，生成语义化的团队名。")
    public String suggestTeamName(
            @Param(name = "oldTeamName", required = false,
                    description = "现有团队名（可选）。如果提供，将分析该团队的成员并给出改进建议") String oldTeamName,
            @Param(name = "role", required = false,
                    description = "主要角色（可选）。如：security-expert") String role,
            @Param(name = "description", required = false,
                    description = "团队描述（可选）。如：专注于系统安全") String description
    ) {
        StringBuilder result = new StringBuilder();

        if (oldTeamName != null && !oldTeamName.isEmpty()) {
            // 分析现有团队
            result.append(analyzeExistingTeam(oldTeamName));
        } else {
            // 为新团队生成建议
            result.append(generateSuggestions(role, description));
        }

        return result.toString();
    }

    /**
     * 分析现有团队并提供建议
     */
    private String analyzeExistingTeam(String oldTeamName) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 团队名称分析\n\n");

        // 检查是否是时间戳格式（如 team-1736640123456）
        if (oldTeamName.matches("^team-\\d+$")) {
            sb.append("⚠️ **当前团队名**: `").append(oldTeamName).append("`\n\n");
            sb.append("**问题**: 这是时间戳格式的团队名，不够语义化。\n\n");

            // 获取该团队的成员
            List<String> memberNames = getTeamMembers(oldTeamName);
            if (!memberNames.isEmpty()) {
                sb.append("**当前成员**:\n");
                for (String member : memberNames) {
                    sb.append("  - ").append(member).append("\n");
                }
                sb.append("\n");

                // 生成建议
                String suggestedName = TeamNameGenerator.suggestBetterName(oldTeamName, memberNames);
                if (suggestedName != null) {
                    sb.append("✅ **建议团队名**: `").append(suggestedName).append("`\n\n");
                    sb.append("**建议描述**: ")
                      .append(TeamNameGenerator.getTeamDescription(suggestedName))
                      .append("\n\n");
                }
            }

            sb.append("**如何重命名**:\n");
            sb.append("使用 `create_team` 工具创建新团队，并指定语义化的 teamName。\n");
        } else {
            sb.append("✅ **当前团队名**: `").append(oldTeamName).append("`\n\n");

            // 检查团队名是否有效
            if (!TeamNameGenerator.isValidTeamName(oldTeamName)) {
                sb.append("⚠️ **问题**: 团队名格式不符合规范（只允许小写字母、数字和连字符）\n\n");
                String normalized = TeamNameGenerator.normalizeTeamName(oldTeamName);
                sb.append("✅ **规范化建议**: `").append(normalized).append("`\n\n");
            } else {
                sb.append("✅ 团队名格式正确！\n\n");

                // 提取领域
                String domain = TeamNameGenerator.extractDomainFromTeamName(oldTeamName);
                if (domain != null) {
                    sb.append("**识别的领域**: ").append(domain).append("\n\n");
                    sb.append("**团队描述**: ")
                      .append(TeamNameGenerator.getTeamDescription(oldTeamName))
                      .append("\n\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 为新团队生成命名建议
     */
    private String generateSuggestions(String role, String description) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 团队命名建议\n\n");

        if (role == null && description == null) {
            sb.append("请提供角色或描述信息，以便生成更准确的团队名建议。\n\n");
            sb.append("**示例**:\n");
            sb.append("```\n");
            sb.append("suggest_team_name(role=\"security-expert\", description=\"专注于安全审计\")\n");
            sb.append("```\n\n");

            sb.append("**常见领域示例**:\n");
            sb.append("- `security-squad` - 安全专家团队\n");
            sb.append("- `database-team` - 数据库专家团队\n");
            sb.append("- `frontend-experts` - 前端开发团队\n");
            sb.append("- `backend-force` - 后端开发团队\n");
            sb.append("- `devops-alliance` - 运维自动化团队\n");
            sb.append("- `testing-guild` - 质量保证团队\n");
            sb.append("- `architecture-lab` - 架构设计团队\n");
            sb.append("- `ai-collective` - 人工智能团队\n");

            return sb.toString();
        }

        // 生成建议
        String teamName = TeamNameGenerator.generateTeamName(
            role != null ? role : "expert",
            description != null ? description : "",
            null
        );

        sb.append("**基于输入生成的建议**:\n\n");
        sb.append("```\n");
        sb.append("团队名: ").append(teamName).append("\n");
        sb.append("描述: ").append(TeamNameGenerator.getTeamDescription(teamName)).append("\n");
        sb.append("```\n\n");

        // 生成多个备选方案
        sb.append("**其他备选方案**:\n\n");
        String taskGoal = description != null ? description : role;
        for (int i = 0; i < 3; i++) {
            String alternative = TeamNameGenerator.generateTeamName(
                role + "-" + i,
                description,
                taskGoal
            );
            sb.append((i + 1)).append(". `").append(alternative).append("` - ")
              .append(TeamNameGenerator.getTeamDescription(alternative))
              .append("\n");
        }

        sb.append("\n**使用方法**:\n");
        sb.append("在创建团队成员时使用 `teamName=\"").append(teamName).append("\"` 参数。\n");

        return sb.toString();
    }

    /**
     * 获取团队成员
     */
    private List<String> getTeamMembers(String teamName) {
        return manager.getAgents().stream()
            .filter(agent -> agent.getMetadata().hasTeamName() &&
                            agent.getMetadata().getTeamName().equals(teamName))
            .map(agent -> agent.getMetadata().getCode())
            .collect(Collectors.toList());
    }
}
