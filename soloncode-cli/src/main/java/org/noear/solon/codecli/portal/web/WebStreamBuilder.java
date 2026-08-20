package org.noear.solon.codecli.portal.web;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.pipeline.ChannelBroadcastSink;
import org.noear.solon.codecli.portal.web.pipeline.SessionMetricsRecorder;
import org.noear.solon.codecli.portal.web.pipeline.ToolPresentationFilter;
import org.noear.solon.codecli.portal.web.pipeline.WebEventMapper;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 响应式 Web 流构建器 (SAEP 2.0 管道装配入口)
 *
 * @author noear
 */
@Slf4j
public class WebStreamBuilder {
    private final ChannelBroadcastSink broadcastSink = new ChannelBroadcastSink();

    public void replyToBoundChannel(WorkspaceContext wsContext, String sessionId, String text, boolean isFinal) {
        broadcastSink.replyToBoundChannel(wsContext.getChannelHub(), sessionId, text, isFinal);
    }

    /**
     * 构建 SAEP 2.0 响应式事件流
     */
    public Flux<WebEvent<?>> buildStreamFlux(WorkspaceContext wsContext, AgentSession session, ReActAgent agent, ChatModel chatModel, String sessionCwd, Prompt prompt) {
        if (prompt == null) {
            prompt = Prompt.of();
        }

        if ("/resume".equals(prompt.getUserContent())) {
            prompt = Prompt.of();
        }

        session.attrs().put("_agent_selected_tmp", agent.name());

        String sessionEffort = ReasoningSupportUtil.getSessionEffort(session);
        String sessionThinkingMode = ReasoningSupportUtil.getSessionThinkingMode(session);
        ReasoningSupportUtil.ModelCapability cap = null;
        try {
            ChatConfig fullConfig = null;
            if (chatModel != null && chatModel.getConfig() != null) {
                String key = chatModel.getConfig().getNameOrModel();
                if (Assert.isNotEmpty(key)) {
                    fullConfig = wsContext.getEngine().getModelOrNil(key);
                    if (fullConfig == null) {
                        fullConfig = ReasoningSupportUtil.findEngineConfig(wsContext.getEngine().getModels(), key);
                    }
                }
                if (fullConfig == null && Assert.isNotEmpty(chatModel.getConfig().getModel())) {
                    fullConfig = wsContext.getEngine().getModelOrNil(chatModel.getConfig().getModel());
                    if (fullConfig == null) {
                        fullConfig = ReasoningSupportUtil.findEngineConfig(
                                wsContext.getEngine().getModels(), chatModel.getConfig().getModel());
                    }
                }
                if (fullConfig == null && Assert.isNotEmpty(chatModel.getConfig().getName())) {
                    fullConfig = wsContext.getEngine().getModelOrNil(chatModel.getConfig().getName());
                }
            }
            if (fullConfig != null) {
                cap = ReasoningSupportUtil.resolveCapability(fullConfig);
            } else if (chatModel != null && chatModel.getConfig() != null) {
                cap = ReasoningSupportUtil.resolveCapability(
                        chatModel.getConfig().getName(),
                        chatModel.getConfig().getModel(),
                        chatModel.getConfig().getStandardOrProvider(),
                        null);
            }
        } catch (Throwable ignored) {
        }
        final String effectiveEffort = ReasoningSupportUtil.resolveEffectiveEffort(
                null, sessionEffort, cap, false);
        ReasoningSupportUtil.applyToPrompt(prompt, sessionThinkingMode, effectiveEffort);

        WebEventMapper mapper = new WebEventMapper(this, wsContext, session, chatModel);
        ToolPresentationFilter toolFilter = new ToolPresentationFilter();
        SessionMetricsRecorder metricsRecorder = new SessionMetricsRecorder(session);

        return agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.chatModel(chatModel);
                    ReasoningSupportUtil.applyToOptions(o, sessionThinkingMode, effectiveEffort);

                    if (Assert.isNotEmpty(sessionCwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwd);
                    }
                })
                .stream()
                .flatMap(event -> Flux.fromIterable(mapper.mapEvent(event)))
                .filter(WebEvent::isNotEmpty)
                .map(toolFilter::apply)
                .doOnNext(event -> broadcastSink.broadcast(wsContext.getChannelHub(), event))
                .doOnNext(metricsRecorder::record)
                .onErrorResume(e -> {
                    log.error("Stream execution error", e);
                    return Flux.just(WebEvent.ofError(e), WebEvent.ofDone());
                });
    }
}
