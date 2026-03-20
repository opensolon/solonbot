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
package org.noear.solon.bot.core.agent;

import org.noear.solon.core.util.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 代理定义管理器
 *
 * @author bai
 * @since 3.9.5
 */
public class AgentManager {
    private static final Logger LOG = LoggerFactory.getLogger(AgentManager.class);

    private final Map<String, AgentDefinition> agentMap = new ConcurrentHashMap<>();

    public AgentManager() {
        // 添加预置的智能体类型（保持向后兼容）
//        addSubagent(new ExploreSubagent(rootAgent));
//        addSubagent(new PlanSubagent(rootAgent));
//        addSubagent(new GeneralPurposeSubagent(rootAgent));
//        addSubagent(new BashSubagent(rootAgent));

        loadAgentFile(ResourceUtil.getResource( "defaults/agents/bash.md"));
        loadAgentFile(ResourceUtil.getResource( "defaults/agents/explore.md"));
        loadAgentFile(ResourceUtil.getResource( "defaults/agents/general-purpose.md"));
        loadAgentFile(ResourceUtil.getResource( "defaults/agents/plan.md"));
    }

    public void addSubagent(AgentDefinition agentDefinition) {
        agentMap.putIfAbsent(agentDefinition.getMetadata().getName(), agentDefinition);
    }

    /**
     * 获取指定名称的子代理（支持自定义代理）
     */
    public AgentDefinition getAgent(String agentName) {
        // 1. 首先尝试作为预定义类型
        AgentDefinition agentDefinition = agentMap.get(agentName);

        if (agentDefinition == null) {
            throw new IllegalArgumentException("未找到代理: " + agentName);
        } else {
            return agentDefinition;
        }
    }


    /**
     * 检查子代理是否已注册
     */
    public boolean hasAgent(String agentName) {
        return agentMap.containsKey(agentName);
    }

    /**
     * 获取所有已注册的子代理
     */
    public Collection<AgentDefinition> getAgents() {
        return agentMap.values();
    }

    /**
     * 清除所有子代理
     */
    public void clear() {
        agentMap.clear();
    }

    /**
     * 注册自定义 agents 池
     *
     * @param dir       agents 目录路径，可以是绝对路径或相对路径
     * @param recursive 是否递归扫描子目录（用于团队成员目录）
     */
    public void agentPool(Path dir, boolean recursive) {
        if (dir == null) {
            return;
        }

        Path path = dir.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            LOG.warn("代理池目录不存在: {}", dir);
            return;
        }

        if (!Files.isDirectory(path)) {
            LOG.warn("代理池路径不是目录: {}", dir);
            return;
        }

        try {
            if (recursive) {
                // 递归扫描子目录（用于团队成员）
                Files.walk(path)
                        .filter(p -> p.toString().endsWith(".md"))
                        .forEach(file -> loadAgentFile(file));
            } else {
                // 只扫描当前目录
                try (Stream<Path> stream = Files.list(path)) {
                    List<Path> files = stream.filter(p -> p.toString().endsWith(".md"))
                            .collect(Collectors.toList());

                    for (Path file : files) {
                        loadAgentFile(file);
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("扫描代理池目录失败: {}", dir, e);
        }
    }

    /**
     * 注册自定义 agents 池（不递归）
     *
     * @param dir agents 目录路径，可以是绝对路径或相对路径
     */
    public void agentPool(Path dir) {
        agentPool(dir, false);
    }


    private void loadAgentFile(URL url) {
        if(url == null){
            return;
        }

        loadAgentFile(Paths.get(url.getFile()));
    }

    /**
     * 从文件加载子代理定义
     *
     * @param file 代理定义文件路径
     */
    private void loadAgentFile(Path file) {
        try {
            String fileName = file.getFileName().toString();
            List<String> fullContent = Files.readAllLines(file, StandardCharsets.UTF_8);

            // 解析文件：拆分元数据和 Prompt
            AgentDefinition definition = AgentDefinition.fromMarkdown(fullContent);

            String agentTypeName = definition.getMetadata().getName();

            if (agentTypeName == null || agentTypeName.isEmpty()) {
                agentTypeName = fileName.substring(0, fileName.length() - 3);
            }

            agentMap.put(agentTypeName, definition);

            LOG.debug("加载子代理: {} 从 {}", agentTypeName, file);
        } catch (IOException e) {
            LOG.error("读取代理文件失败: {}", file, e);
        }
    }
}