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
package org.noear.solon.codecli.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 从 .soloncode/commands/ 目录加载 Markdown 自定义命令
 * <p>
 * 兼容 Claude Code 的 Custom Command 格式规范：
 * <ul>
 *   <li>支持 YAML Frontmatter（--- 包裹的头部元数据）</li>
 *   <li>支持子目录命名空间（deploy/staging.md → /deploy:staging）</li>
 *   <li>支持 description、argument-hint、allowed-tools 等元数据字段</li>
 * </ul>
 *
 * @author noear
 * @since 2026.4.28
 */
public class CustomCommandLoader {
    private static final Logger LOG = LoggerFactory.getLogger(CustomCommandLoader.class);

    /**
     * 扫描目录（含子目录），注册 .md 文件为命令
     *
     * @param dirPath  命令目录根路径
     * @param registry 注册表
     * @param source   命令来源
     */
    public static void loadFromDirectory(String dirPath, CliCommandRegistry registry, CliCommandSource source) {
        Path dir = Paths.get(dirPath);
        if (!Files.isDirectory(dir)) {
            return;
        }

        // 递归扫描子目录，支持 deploy/staging.md → deploy:staging
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .filter(p -> Files.isRegularFile(p))
                 .forEach(p -> registerMarkdownCommand(p, dir, registry, source));
        } catch (IOException e) {
            LOG.warn("Failed to load commands from {}: {}", dirPath, e.getMessage());
        }
    }

    /**
     * 注册单个 Markdown 命令
     *
     * @param mdFile   文件路径
     * @param baseDir  基础目录（用于计算相对路径，生成命名空间）
     * @param registry 注册表
     * @param source   命令来源
     */
    private static void registerMarkdownCommand(Path mdFile, Path baseDir, CliCommandRegistry registry, CliCommandSource source) {
        // 1. 计算命令名（含命名空间）
        String cmdName = buildCommandName(mdFile, baseDir);

        try {
            // 2. 读取文件内容
            String content = new String(Files.readAllBytes(mdFile), StandardCharsets.UTF_8);

            // 3. 解析 YAML Frontmatter
            FrontmatterResult fm = parseFrontmatter(content);

            // 4. 注册命令
            registry.register(new MarkdownCommand(cmdName, fm.description, fm.argumentHint,
                    fm.body, fm.allowedTools, source));

        } catch (IOException e) {
            LOG.warn("Failed to read command file {}: {}", mdFile, e.getMessage());
        }
    }

    /**
     * 构建命令名（含命名空间）
     * <p>
     * 规则（对齐 Claude Code）：
     * - 根目录下：review.md → review
     * - 子目录下：deploy/staging.md → deploy:staging
     * - 多层子目录：ci/docker/build.md → ci:docker:build
     */
    static String buildCommandName(Path mdFile, Path baseDir) {
        Path relative = baseDir.relativize(mdFile);

        // 去掉 .md 后缀
        String relativeStr = relative.toString();
        if (relativeStr.endsWith(".md")) {
            relativeStr = relativeStr.substring(0, relativeStr.length() - 3);
        }

        // 将路径分隔符替换为冒号（命名空间分隔符）
        return relativeStr.replace('/', ':').replace('\\', ':');
    }

    /**
     * 解析 YAML Frontmatter
     * <p>
     * 格式：
     * <pre>
     * ---
     * description: Create a git commit
     * argument-hint: [message]
     * allowed-tools: Bash(git add:*), Bash(git status:*)
     * ---
     * </pre>
     * <p>
     * 如果没有 Frontmatter（无 --- 包裹），则整个文件作为模板，尝试从 HTML 注释提取描述（向后兼容）。
     */
    static FrontmatterResult parseFrontmatter(String content) {
        FrontmatterResult result = new FrontmatterResult();

        String trimmed = content.trim();

        // 检测 YAML Frontmatter：以 --- 开始和结束
        if (trimmed.startsWith("---")) {
            int endMarker = trimmed.indexOf("---", 3);
            if (endMarker > 3) {
                String frontmatter = trimmed.substring(3, endMarker).trim();
                result.body = trimmed.substring(endMarker + 3).trim();
                parseYamlFields(frontmatter, result);
                return result;
            }
        }

        // 无 Frontmatter：向后兼容（整个文件为模板，尝试 HTML 注释提取描述）
        result.body = content;
        String[] lines = content.split("\n");
        if (lines.length > 0) {
            String firstLine = lines[0].trim();
            if (firstLine.startsWith("<!--") && firstLine.endsWith("-->")) {
                result.description = firstLine.substring(4, firstLine.length() - 3).trim();
            }
        }

        return result;
    }

    /**
     * 简易 YAML 字段解析（不支持嵌套，仅解析 key: value 平铺格式）
     */
    private static void parseYamlFields(String yaml, FrontmatterResult result) {
        String[] lines = yaml.split("\n");
        for (String line : lines) {
            int colonIdx = line.indexOf(':');
            if (colonIdx <= 0) {
                continue;
            }

            String key = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();

            switch (key) {
                case "description":
                    result.description = value;
                    break;
                case "argument-hint":
                    result.argumentHint = value;
                    break;
                case "allowed-tools":
                    result.allowedTools = parseAllowedTools(value);
                    break;
                default:
                    // 忽略不认识的字段，保持前向兼容
                    break;
            }
        }
    }

    /**
     * 解析 allowed-tools 字段值
     * <p>
     * 格式："Bash(git add:*), Bash(git status:*), FileRead(*)"
     * → ["Bash(git add:*)", "Bash(git status:*)", "FileRead(*)"]
     */
    static List<String> parseAllowedTools(String value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> tools = new ArrayList<>();
        // 按逗号分割，但要处理括号内的逗号（如 Bash(a,b) 不应被分割）
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String tool = value.substring(start, i).trim();
                if (!tool.isEmpty()) {
                    tools.add(tool);
                }
                start = i + 1;
            }
        }
        // 最后一个
        String last = value.substring(start).trim();
        if (!last.isEmpty()) {
            tools.add(last);
        }

        return tools;
    }

    /**
     * Frontmatter 解析结果
     */
    static class FrontmatterResult {
        String description = null;
        String argumentHint = null;
        String body = "";
        List<String> allowedTools = Collections.emptyList();
    }
}
