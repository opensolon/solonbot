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

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.core.AgentProperties;

import java.util.List;
import java.util.function.Consumer;

/**
 * 命令执行上下文
 *
 * @author noear
 * @since 2026.4.28
 */
public class CliCommandContext {
    private final AgentSession session;
    private final Terminal terminal;
    private final LineReader reader;
    private final HarnessEngine agentRuntime;
    private final AgentProperties agentProps;
    private final String rawInput;
    private final String commandName;
    private final List<String> args;

    /**
     * Agent 任务回调（用于 ResumeCommand 等 Agent 级命令）
     */
    private final Consumer<String> agentTaskRunner;

    public CliCommandContext(AgentSession session, Terminal terminal, LineReader reader,
                             HarnessEngine agentRuntime, AgentProperties agentProps,
                             String rawInput, String commandName, List<String> args,
                             Consumer<String> agentTaskRunner) {
        this.session = session;
        this.terminal = terminal;
        this.reader = reader;
        this.agentRuntime = agentRuntime;
        this.agentProps = agentProps;
        this.rawInput = rawInput;
        this.commandName = commandName;
        this.args = args;
        this.agentTaskRunner = agentTaskRunner;
    }

    public AgentSession getSession() {
        return session;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public LineReader getReader() {
        return reader;
    }

    public HarnessEngine getAgentRuntime() {
        return agentRuntime;
    }

    public AgentProperties getAgentProps() {
        return agentProps;
    }

    public String getRawInput() {
        return rawInput;
    }

    public String getCommandName() {
        return commandName;
    }

    public List<String> getArgs() {
        return args;
    }

    /**
     * 获取第一个参数，若无则返回 null
     */
    public String argAt(int index) {
        return args.size() > index ? args.get(index) : null;
    }

    /**
     * 获取参数数量
     */
    public int argCount() {
        return args.size();
    }

    /**
     * 打印到终端
     */
    public void println(String text) {
        terminal.writer().println(text);
        terminal.flush();
    }

    /**
     * 运行 Agent 任务（回调 CliShell.performAgentTask）
     */
    public void runAgentTask(String input) throws Exception {
        if (agentTaskRunner != null) {
            agentTaskRunner.accept(input);
        }
    }
}
