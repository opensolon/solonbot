package org.codecli.ext1;

import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

/**
 *
 * @author noear 2026/4/21 created
 *
 */
public class Extension1Config implements Plugin {
    @Override
    public void start(AppContext context) throws Throwable {
        context.beanMake(Extension1.class);
    }
}
