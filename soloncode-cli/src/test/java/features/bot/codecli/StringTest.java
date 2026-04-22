package features.bot.codecli;

import org.junit.jupiter.api.Test;

/**
 *
 * @author noear 2026/4/22 created
 *
 */
public class StringTest {
    @Test
    public void case1() {
        String str = " \n".trim();

        assert str.length() == 0;
    }
}
