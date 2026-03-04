package features.ai.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.ai.codecli.core.CliSkillProvider;
import org.noear.solon.ai.codecli.core.CodeProperties;
import org.noear.solon.ai.codecli.core.ExpertSkill;
import org.noear.solon.ai.codecli.core.PoolManager;
import org.noear.solon.core.util.Assert;
import org.noear.solon.test.SolonTest;

import java.util.Map;

/**
 *
 * @author noear 2026/2/28 created
 *
 */
@SolonTest
public class SkillTest {
    @Test
    public void case1() {
        CodeProperties config = Solon.cfg().toBean("solon.code.cli", CodeProperties.class);

        CliSkillProvider cliSkillProvider = new CliSkillProvider();

        if(Assert.isNotEmpty(config.skillPools)) {
            for (Map.Entry<String, String> entry : config.skillPools.entrySet()) {
                cliSkillProvider.skillPool(entry.getKey(), entry.getValue());
            }
        }

        //video generation animation
        //AI image video media generation

        //discoverySkill.searchSkills()

        String desc = cliSkillProvider.getPoolManager().getSkillMap().get("@shared/docusign-automation").getDescription();
        System.out.println(desc);
        assert desc.length() > 30;

        String list = cliSkillProvider.getExpertSkill().skillsearch("video generation animation");
        System.out.println(list);
        assert list.length() > 100;
    }
}
