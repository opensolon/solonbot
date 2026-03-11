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
package org.noear.solon.bot.core.memory;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * 动作执行记录（通用类）
 *
 * 记录各种动作的执行过程和结果，用于调试和审计。
 * 支持多种动作类型：
 * - Tool: 内置工具（bash, grep, read, write 等）
 * - Skill: 技能脚本（Python, Shell, JavaScript 等）
 * - MCP: Model Context Protocol 服务器工具
 * - Custom: 自定义动作类型
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
@Setter
public class ActionRecord {
    // ========== 类型常量 ==========

    /** 内置工具类型 */
    public static final String TYPE_TOOL = "tool";

    /** 技能类型 */
    public static final String TYPE_SKILL = "skill";

    /** MCP 服务器工具类型 */
    public static final String TYPE_MCP = "mcp";

    /** 自定义动作类型 */
    public static final String TYPE_CUSTOM = "custom";

    // ========== 字段 ==========

    private String actionName;             // 动作名称（通用字段）
    private String actionDescription;      // 动作描述
    private String actionType;             // 动作类型：tool/skill/mcp/custom 等
    private Map<String, Object> inputs;    // 输入参数
    private Object output;                 // 输出结果
    private long startTime;                // 开始时间
    private long endTime;                  // 结束时间
    private long duration;                 // 执行耗时（毫秒）
    private boolean success;               // 是否成功
    private String errorMessage;           // 错误信息
    private String agentId;                // 执行的 Agent ID

    // Skill 特有字段（可选）
    private String skillPool;              // Skill 池（如 @soloncode_skills）
    private String skillFile;              // Skill 文件路径（如果是文件 skill）

    // MCP 特有字段（可选）
    private String mcpServer;              // MCP 服务器名称
    private String mcpMethod;              // MCP 方法名称
    private String mcpResource;            // MCP 资源标识

    /**
     * 构造函数（Tool）
     *
     * @param actionName 动作名称
     * @param agentId 执行的 Agent ID
     */
    public ActionRecord(String actionName, String agentId) {
        this(actionName, agentId, TYPE_TOOL);
    }

    /**
     * 构造函数（Skill - 向后兼容）
     *
     * @param skillName Skill 名称
     * @param agentId 执行的 Agent ID
     * @param asSkill 标记为 Skill 类型
     */
    public ActionRecord(String skillName, String agentId, boolean asSkill) {
        this(skillName, agentId, asSkill ? TYPE_SKILL : TYPE_TOOL);
    }

    /**
     * 完整构造函数
     *
     * @param name 动作名称
     * @param agentId 执行的 Agent ID
     * @param actionType 动作类型
     */
    public ActionRecord(String name, String agentId, String actionType) {
        this.actionName = name;
        this.agentId = agentId;
        this.actionType = actionType != null ? actionType : TYPE_TOOL;
        this.inputs = new HashMap<>();
        this.startTime = System.currentTimeMillis();
        this.success = false;
    }

    // ========== 静态工厂方法 ==========

    /**
     * 创建 Tool 记录
     *
     * @param toolName 工具名称
     * @param agentId 执行的 Agent ID
     * @return ActionRecord 实例
     */
    public static ActionRecord forTool(String toolName, String agentId) {
        return new ActionRecord(toolName, agentId, TYPE_TOOL);
    }

    /**
     * 创建 Skill 记录
     *
     * @param skillName Skill 名称
     * @param agentId 执行的 Agent ID
     * @return ActionRecord 实例
     */
    public static ActionRecord forSkill(String skillName, String agentId) {
        return new ActionRecord(skillName, agentId, TYPE_SKILL);
    }

    /**
     * 创建 MCP 工具记录
     *
     * @param toolName MCP 工具名称
     * @param mcpServer MCP 服务器名称
     * @param agentId 执行的 Agent ID
     * @return ActionRecord 实例
     */
    public static ActionRecord forMcp(String toolName, String mcpServer, String agentId) {
        ActionRecord record = new ActionRecord(toolName, agentId, TYPE_MCP);
        record.setMcpServer(mcpServer);
        return record;
    }

    /**
     * 创建自定义动作记录
     *
     * @param actionName 动作名称
     * @param customType 自定义类型
     * @param agentId 执行的 Agent ID
     * @return ActionRecord 实例
     */
    public static ActionRecord forCustom(String actionName, String customType, String agentId) {
        return new ActionRecord(actionName, agentId, customType);
    }

    /**
     * 标记成功
     *
     * @param output 输出结果
     */
    public void success(Object output) {
        this.output = output;
        this.success = true;
        this.endTime = System.currentTimeMillis();
        this.duration = this.endTime - this.startTime;
    }

    /**
     * 标记失败
     *
     * @param errorMessage 错误信息
     */
    public void failure(String errorMessage) {
        this.errorMessage = errorMessage;
        this.success = false;
        this.endTime = System.currentTimeMillis();
        this.duration = this.endTime - this.startTime;
    }

    /**
     * 添加输入参数
     *
     * @param key 参数名
     * @param value 参数值
     */
    public ActionRecord addInput(String key, Object value) {
        this.inputs.put(key, value);
        return this;
    }

    /**
     * 设置输入参数
     *
     * @param inputs 输入参数 Map
     */
    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs != null ? inputs : new HashMap<>();
    }

    // ========== 向后兼容方法 ==========

    /**
     * 获取 Tool 名称（别名）
     * 向后兼容 ToolRecord.getToolName()
     */
    public String getToolName() {
        return actionName;
    }

    /**
     * 设置 Tool 名称（别名）
     * 向后兼容 ToolRecord.setToolName()
     */
    public void setToolName(String toolName) {
        this.actionName = toolName;
    }

    /**
     * 获取 Skill 名称（别名）
     * 向后兼容 SkillRecord.getSkillName()
     */
    public String getSkillName() {
        return actionName;
    }

    /**
     * 设置 Skill 名称（别名）
     * 向后兼容 SkillRecord.setSkillName()
     */
    public void setSkillName(String skillName) {
        this.actionName = skillName;
    }

    // ========== 类型判断方法 ==========

    /**
     * 判断是否为 Tool 类型
     */
    public boolean isTool() {
        return TYPE_TOOL.equals(actionType);
    }

    /**
     * 判断是否为 Skill 类型
     */
    public boolean isSkill() {
        return TYPE_SKILL.equals(actionType);
    }

    /**
     * 判断是否为 MCP 类型
     */
    public boolean isMcp() {
        return TYPE_MCP.equals(actionType);
    }

    /**
     * 判断是否为自定义类型
     */
    public boolean isCustom() {
        return TYPE_CUSTOM.equals(actionType);
    }

    /**
     * 判断是否为指定类型
     *
     * @param type 类型字符串
     * @return 是否匹配
     */
    public boolean isType(String type) {
        return (type != null) && type.equals(actionType);
    }

    // ========== toString ==========

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ActionRecord{");
        sb.append("type='").append(actionType).append("'");
        sb.append(", name='").append(actionName).append("'");
        sb.append(", success=").append(success);
        sb.append(", duration=").append(duration);
        sb.append(", inputs=").append(inputs.size());

        if (output != null) {
            sb.append(", hasOutput=true");
        }

        if (errorMessage != null) {
            sb.append(", error='").append(errorMessage).append("'");
        }

        // Skill 特有字段
        if (isSkill()) {
            if (skillPool != null) {
                sb.append(", pool='").append(skillPool).append("'");
            }
            if (skillFile != null) {
                sb.append(", file='").append(skillFile).append("'");
            }
        }

        // MCP 特有字段
        if (isMcp()) {
            if (mcpServer != null) {
                sb.append(", mcpServer='").append(mcpServer).append("'");
            }
            if (mcpMethod != null) {
                sb.append(", mcpMethod='").append(mcpMethod).append("'");
            }
            if (mcpResource != null) {
                sb.append(", mcpResource='").append(mcpResource).append("'");
            }
        }

        sb.append("}");
        return sb.toString();
    }
}
