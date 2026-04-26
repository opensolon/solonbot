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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CLI 命令注册表
 *
 * @author noear
 * @since 2026.4.28
 */
public class CliCommandRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(CliCommandRegistry.class);

    private final Map<String, CliCommand> commands = new ConcurrentHashMap<>();

    /**
     * 注册命令
     */
    public void register(CliCommand command) {
        CliCommand existing = commands.putIfAbsent(command.name(), command);
        if (existing != null) {
            LOG.warn("Command '{}' already registered by {}, skip: {}",
                    command.name(), existing.getClass().getSimpleName(), command.getClass().getSimpleName());
        }
    }

    /**
     * 查找命令
     */
    public CliCommand find(String name) {
        return commands.get(name);
    }

    /**
     * 获取所有命令（排序后）
     */
    public List<CliCommand> all() {
        return commands.values().stream()
                .sorted(Comparator.comparing(CliCommand::name))
                .collect(Collectors.toList());
    }

    /**
     * 获取命令名列表（用于 Tab 补全）
     */
    public List<String> names() {
        return new ArrayList<>(commands.keySet()).stream().sorted().collect(Collectors.toList());
    }
}
