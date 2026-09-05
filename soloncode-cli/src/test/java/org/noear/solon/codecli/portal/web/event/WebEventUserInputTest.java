package org.noear.solon.codecli.portal.web.event;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.portal.web.event.payload.SystemUserInputPayload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端推送用户消息的来源展示契约测试。
 *
 * @author noear
 */
class WebEventUserInputTest {
    @Test
    void userInputEventCarriesSourceLabel() {
        WebEvent<SystemUserInputPayload> event = WebEvent.ofUserInput("执行任务", "Loop");

        assertEquals(WebEventNames.SYSTEM_USER_INPUT, event.getEvent());
        assertEquals("执行任务", event.getPayload().getText());
        assertEquals("Loop", event.getPayload().getSource());
        assertEquals("Loop", event.getPayload().getSourceLabel());
    }

    @Test
    void webSourceKeepsHiddenLabelContract() {
        WebEvent<SystemUserInputPayload> event = WebEvent.ofUserInput("普通消息", null);

        assertEquals("Web", event.getPayload().getSourceLabel());
    }

    @Test
    void realtimeRendererFallsBackToRawSource() throws IOException {
        String javascript = resourceText("/static/js/app-streaming.js");

        assertTrue(javascript.contains("p.sourceLabel || p.source"),
                "实时消息应在展示标签缺失时回退到原始发起源");
    }

    private String resourceText(String path) throws IOException {
        InputStream input = WebEventUserInputTest.class.getResourceAsStream(path);
        if (input == null) {
            throw new IOException("Missing test resource: " + path);
        }
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) >= 0) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
