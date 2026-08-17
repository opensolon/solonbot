package org.noear.solon.codecli.portal.web.pipeline;

import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.MessagePayload;

import java.util.List;

/**
 * 将文本片段异步/同步广播到绑定的微信/飞书等 IM 渠道
 *
 * @author noear
 */
public class ChannelBroadcastSink {

    private final List<Channel> imLinks;

    public ChannelBroadcastSink(List<Channel> imLinks) {
        this.imLinks = imLinks;
    }

    public void broadcast(WebEvent<?> event) {
        if (event == null || imLinks == null || imLinks.isEmpty()) {
            return;
        }

        if (!WebEventNames.MESSAGE_DELTA.equals(event.getEvent())) {
            return;
        }

        if (!(event.getPayload() instanceof MessagePayload)) {
            return;
        }

        MessagePayload payload = (MessagePayload) event.getPayload();
        String delta = payload.getDelta();
        String sessionId = event.getSessionId();
        if (delta != null && !delta.isEmpty() && sessionId != null) {
            for (Channel link : imLinks) {
                if (link.isBound(sessionId)) {
                    link.sendReply(sessionId, delta, false);
                }
            }
        }
    }

    public void replyToBoundChannel(String sessionId, String text, boolean isFinal) {
        if (sessionId == null || imLinks == null || imLinks.isEmpty()) {
            return;
        }
        for (Channel link : imLinks) {
            if (link.isBound(sessionId)) {
                link.sendReply(sessionId, text, isFinal);
            }
        }
    }
}
