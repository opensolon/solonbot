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

/**
 * 命令来源枚举
 *
 * @author noear
 * @since 2026.4.28
 */
public enum CliCommandSource {
    /**
     * 内置命令
     */
    BUILTIN,
    /**
     * 用户自定义命令（~/.soloncode/commands/）
     */
    USER,
    /**
     * 项目自定义命令（.soloncode/commands/）
     */
    PROJECT,
    /**
     * 扩展命令（通过 HarnessExtension 注册）
     */
    EXTENSION
}
