package org.noear.solon.codecli.memory;

import org.noear.solon.Utils;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.md.MemorySolutionMdImpl;
import org.noear.solon.codecli.config.AgentFlags;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryProvider implements MemorySolutionProvider {
    private Map<String, MemorySolution> cached = new ConcurrentHashMap<>();


    @Override
    public MemorySolution get(String __cwd) {
        return cached.computeIfAbsent(__cwd, k ->
                new MemorySolutionMdImpl(
                        Utils.asMap(
                                AgentFlags.SCOPE_USER, Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessMemory()),
                                AgentFlags.SCOPE_LOCAL, Paths.get(k, AgentFlags.getHarnessMemory())
                        )));
    }

    public String getScopeDefault() {
        return AgentFlags.SCOPE_LOCAL;
    }

    public String getScopesDescription() {
        return "存储作用域: workspace(工作区,默认) 或 user(用户全局)。跨项目的通用认知用 user 域。";
    }
}