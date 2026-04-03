package org.noear.solon.ai.harness.agent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.test.SolonTest;

/**
 *
 * @author noear 2026/3/20 created
 *
 */
@SolonTest
public class AgentDefaultTest {
    @Test
    public void case1() {
        AgentManager agentManager = new AgentManager();

        AgentDefinition bash = agentManager.getAgent("bash");
        AgentDefinition explore = agentManager.getAgent("explore");
        AgentDefinition general_purpose = agentManager.getAgent("general-purpose");
        AgentDefinition plan = agentManager.getAgent("plan");

        Assertions.assertNotNull(bash);
        Assertions.assertNotNull(explore);
        Assertions.assertNotNull(general_purpose);
        Assertions.assertNotNull(plan);

        assert bash.getMetadata().getTools().contains("bash");
        assert bash.getMetadata().getTools().contains("list");

        assert explore.getMetadata().getTools().contains("list");

        assert general_purpose.getMetadata().getTools().contains("*");

        assert plan.getMetadata().getTools().contains("webfetch");
    }
}
