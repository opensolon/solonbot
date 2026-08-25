package org.codecli.uiext;

import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

public class UiExtensionConfig implements Plugin {
    @Override
    public void start(AppContext context) throws Throwable {
        context.beanMake(UiExtension.class);
    }
}
