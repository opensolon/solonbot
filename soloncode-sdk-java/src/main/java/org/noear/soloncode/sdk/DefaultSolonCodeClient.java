package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.permission.ToolPermissionCallback;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.QueryResult;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Unified request-oriented facade. It owns one blocking session implementation and
 * exposes execution mode only on {@link Request}: {@code call()} or {@code stream()}.
 * The legacy sync/async facades remain available during the migration period.
 */
final class DefaultSolonCodeClient implements SolonCodeClient {
    private SolonCodeSession delegate;
    private final Function<QueryOptions, SolonCodeSession> sessionFactory;
    private final Object turnLock = new Object();
    private boolean started;
    private boolean closed;

    DefaultSolonCodeClient(SolonCodeSession delegate) {
        this.delegate = delegate;
        this.sessionFactory = ignored -> delegate;
    }

    DefaultSolonCodeClient(Function<QueryOptions, SolonCodeSession> sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    private SolonCodeSession session(QueryOptions firstTurnOptions) {
        synchronized (turnLock) {
            if (delegate == null) {
                if (closed) {
                    throw new SolonCodeSDKException("SolonCodeClient is closed");
                }
                delegate = sessionFactory.apply(firstTurnOptions);
            }
            return delegate;
        }
    }

    @Override
    public SolonCodeClient.Request prompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be empty");
        }
        return new RequestImpl(prompt);
    }

    @Override
    public void interrupt() throws SolonCodeSDKException {
        session(null).interrupt();
    }

    @Override
    public void setModel(String model) throws SolonCodeSDKException {
        session(null).setModel(model);
    }

    @Override
    public void setPermissionMode(String mode) throws SolonCodeSDKException {
        session(null).setPermissionMode(mode);
    }

    @Override
    public CLIOptions getOptions() {
        return session(null).getOptions();
    }

    @Override
    public String getCurrentModel() {
        return session(null).getCurrentModel();
    }

    @Override
    public String getCurrentPermissionMode() {
        return session(null).getCurrentPermissionMode();
    }

    @Override
    public java.util.Map<String, Object> getServerInfo() {
        return session(null).getServerInfo();
    }

    @Override
    public boolean isConnected() {
        return delegate != null && delegate.isConnected();
    }

    @Override
    public void setToolPermissionCallback(ToolPermissionCallback callback) {
        session(null).setToolPermissionCallback(callback);
    }

    @Override
    public ToolPermissionCallback getToolPermissionCallback() {
        return session(null).getToolPermissionCallback();
    }

    @Override
    public void close() {
        synchronized (turnLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (delegate != null) {
                delegate.close();
            }
        }
    }

    private void start(String prompt) {
        synchronized (turnLock) {
            if (closed) {
                throw new SolonCodeSDKException("SolonCodeClient is closed");
            }
            SolonCodeSession current = session(null);
            if (started) {
                current.query(prompt);
            } else {
                current.connect(prompt);
                started = true;
            }
        }
    }

    private final class RequestImpl implements SolonCodeClient.Request {
        private final String prompt;
        private final AtomicBoolean executed = new AtomicBoolean();
        private QueryOptions requestOptions;

        private RequestImpl(String prompt) {
            this.prompt = prompt;
        }

        @Override
        public SolonCodeClient.Request options(QueryOptions options) {
            if (executed.get()) {
                throw new IllegalStateException("request options must be set before execution");
            }
            this.requestOptions = options == null ? QueryOptions.defaults() : options;
            return this;
        }

        private void ensureSingleUse() {
            if (!executed.compareAndSet(false, true)) {
                throw new IllegalStateException("request can only be executed once");
            }
        }

        private void applyRequestOptions() {
            if (requestOptions == null) {
                return;
            }
            // Model and permission mode are the only options that can be changed on an
            // already-created persistent session. Transport, workspace and process-level
            // options remain client-scoped; silently ignoring them would be misleading.
            CLIOptions base = session(requestOptions).getOptions();
            CLIOptions requested = requestOptions.toCLIOptions();
            // The first request created the session with the complete request options.
            // From the second turn onward, only protocol-supported dynamic settings may change.
            if (!started) {
                return;
            }
            if (requested.model() != null && !requested.model().equals(base == null ? null : base.model())) {
                session(requestOptions).setModel(requested.model());
            }
            // Process/transport options cannot be changed after the client is built. Do
            // not silently ignore them: callers get a deterministic explanation instead.
            if (requestOptions.systemPrompt() != null
                    || requestOptions.appendSystemPrompt() != null
                    || !requestOptions.allowedTools().isEmpty()
                    || !requestOptions.disallowedTools().isEmpty()
                    || requestOptions.maxTurns() != null
                    || requestOptions.maxBudgetUsd() != null
                    || requestOptions.maxTokens() != null
                    || requestOptions.maxThinkingTokens() != null
                    || requestOptions.fallbackModel() != null
                    || requestOptions.jsonSchema() != null
                    || requestOptions.sessionId() != null
                    || requestOptions.bare()) {
                throw new IllegalArgumentException(
                        "Only model can be overridden per request; configure process and transport options on the client builder");
            }
        }

        @Override
        public QueryResult call() throws SolonCodeSDKException {
            ensureSingleUse();
            synchronized (turnLock) {
                applyRequestOptions();
                start(prompt);
                List<Message> messages = new ArrayList<>();
                Iterator<ParsedMessage> it = delegate.receiveResponse();
                while (it.hasNext()) {
                    ParsedMessage parsed = it.next();
                    if (parsed.isRegularMessage()) {
                        messages.add(parsed.asMessage());
                    }
                }
                CLIOptions effective = delegate.getOptions();
                return Query.buildQueryResult(messages,
                        effective == null ? CLIOptions.builder().build() : effective);
            }
        }

        @Override
        public Flux<Message> stream() {
            if (executed.get()) {
                return Flux.error(new IllegalStateException("request can only be executed once"));
            }
            return Flux.<Message>create(sink -> {
                final SolonCodeSession current = session(requestOptions);
                sink.onCancel(() -> {
                    try {
                        current.interrupt();
                    } catch (Throwable ignored) {
                        // 取消路径不覆盖下游原有的取消信号。
                    }
                });
                try {
                    ensureSingleUse();
                    synchronized (turnLock) {
                        applyRequestOptions();
                        start(prompt);
                        Iterator<ParsedMessage> it = current.receiveResponse();
                        while (!sink.isCancelled() && it.hasNext()) {
                            ParsedMessage parsed = it.next();
                            if (parsed.isRegularMessage()) {
                                sink.next(parsed.asMessage());
                            }
                        }
                    }
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                } catch (Throwable e) {
                    if (!sink.isCancelled()) {
                        sink.error(e);
                    }
                }
            }).subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        public reactor.core.publisher.Mono<QueryResult> streamResult() {
            return stream().collectList().map(messages -> {
                CLIOptions effective = session(requestOptions).getOptions();
                return Query.buildQueryResult(messages,
                        effective == null ? CLIOptions.builder().build() : effective);
            });
        }
    }
}
