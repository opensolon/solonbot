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

/**
 * Unified request-oriented facade. It owns one blocking session implementation and
 * exposes execution mode only on {@link Request}: {@code call()} or {@code stream()}.
 * The legacy sync/async facades remain available during the migration period.
 */
final class DefaultSolonCodeClient implements SolonCodeClient {
    private final SolonCodeSession delegate;
    private final Object turnLock = new Object();
    private boolean started;
    private boolean closed;

    DefaultSolonCodeClient(SolonCodeSession delegate) {
        this.delegate = delegate;
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
        delegate.interrupt();
    }

    @Override
    public void setModel(String model) throws SolonCodeSDKException {
        delegate.setModel(model);
    }

    @Override
    public void setPermissionMode(String mode) throws SolonCodeSDKException {
        delegate.setPermissionMode(mode);
    }

    @Override
    public CLIOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public String getCurrentModel() {
        return delegate.getCurrentModel();
    }

    @Override
    public String getCurrentPermissionMode() {
        return delegate.getCurrentPermissionMode();
    }

    @Override
    public java.util.Map<String, Object> getServerInfo() {
        return delegate.getServerInfo();
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void setToolPermissionCallback(ToolPermissionCallback callback) {
        delegate.setToolPermissionCallback(callback);
    }

    @Override
    public ToolPermissionCallback getToolPermissionCallback() {
        return delegate.getToolPermissionCallback();
    }

    @Override
    public void close() {
        synchronized (turnLock) {
            if (closed) {
                return;
            }
            closed = true;
            delegate.close();
        }
    }

    private void start(String prompt) {
        synchronized (turnLock) {
            if (closed) {
                throw new SolonCodeSDKException("SolonCodeClient is closed");
            }
            if (started) {
                delegate.query(prompt);
            } else {
                delegate.connect(prompt);
                started = true;
            }
        }
    }

    private final class RequestImpl implements SolonCodeClient.Request {
        private final String prompt;
        private final AtomicBoolean executed = new AtomicBoolean();

        private RequestImpl(String prompt) {
            this.prompt = prompt;
        }

        private void ensureSingleUse() {
            if (!executed.compareAndSet(false, true)) {
                throw new IllegalStateException("request can only be executed once");
            }
        }

        @Override
        public QueryResult call() throws SolonCodeSDKException {
            ensureSingleUse();
            synchronized (turnLock) {
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
                sink.onCancel(() -> {
                    try {
                        delegate.interrupt();
                    } catch (Throwable ignored) {
                        // 取消路径不覆盖下游原有的取消信号。
                    }
                });
                try {
                    ensureSingleUse();
                    synchronized (turnLock) {
                        start(prompt);
                        Iterator<ParsedMessage> it = delegate.receiveResponse();
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
    }
}
