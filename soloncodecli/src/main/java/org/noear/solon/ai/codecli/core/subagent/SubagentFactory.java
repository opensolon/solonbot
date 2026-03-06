package org.noear.solon.ai.codecli.core.subagent;

import org.noear.solon.ai.codecli.core.AgentKernel;

/**
 *
 * @author noear 2026/3/6 created
 *
 */
@FunctionalInterface
public interface SubagentFactory {
    Subagent create(AgentKernel mainAgent);
}
