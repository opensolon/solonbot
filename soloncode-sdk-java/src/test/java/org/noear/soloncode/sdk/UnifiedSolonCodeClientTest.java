package org.noear.soloncode.sdk;

import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.ContentBlock;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.TextBlock;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;

class UnifiedSolonCodeClientTest {
    private static ParsedMessage assistant(String text) {
        return ParsedMessage.RegularMessage.of(
                AssistantMessage.of(Arrays.<ContentBlock>asList(TextBlock.of(text))));
    }

    private static ParsedMessage result() {
        return ParsedMessage.RegularMessage.of(
                ResultMessage.builder().subtype("success").result("ok").build());
    }

    @Test
    void callAndStreamShareOneMultiTurnClient() {
        SolonCodeSyncClient delegate = mock(SolonCodeSyncClient.class);
        when(delegate.receiveResponse())
                .thenReturn(Arrays.asList(assistant("one"), result()).iterator())
                .thenReturn(Arrays.asList(assistant("two"), result()).iterator());

        SolonCodeClient client = new DefaultSolonCodeClient(delegate);
        assertThat(client.prompt("first").call().messages()).hasSize(2);
        StepVerifier.create(client.prompt("second").stream())
                .expectNextCount(2)
                .verifyComplete();

        verify(delegate).connect("first");
        verify(delegate).query("second");
        client.close();
        verify(delegate).close();
    }

    @Test
    void streamCancellationInterruptsRunningTurn() {
        SolonCodeSyncClient delegate = mock(SolonCodeSyncClient.class);
        AtomicBoolean interrupted = new AtomicBoolean();
        doAnswer(invocation -> {
            interrupted.set(true);
            return null;
        }).when(delegate).interrupt();
        when(delegate.receiveResponse()).thenReturn(new Iterator<ParsedMessage>() {
            @Override
            public boolean hasNext() {
                while (!interrupted.get()) {
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return false;
            }

            @Override
            public ParsedMessage next() {
                throw new NoSuchElementException();
            }
        });

        SolonCodeClient client = new DefaultSolonCodeClient(delegate);
        StepVerifier.create(client.prompt("long task").stream())
                .thenAwait(java.time.Duration.ofMillis(200L))
                .thenCancel()
                .verify();

        verify(delegate, timeout(1000)).interrupt();
        client.close();
    }
    @Test
    void requestCanOnlyBeExecutedOnce() {
        SolonCodeSyncClient delegate = mock(SolonCodeSyncClient.class);
        when(delegate.receiveResponse()).thenReturn(Arrays.asList(result()).iterator());
        SolonCodeClient client = new DefaultSolonCodeClient(delegate);
        SolonCodeClient.Request request = client.prompt("hello");

        request.call();
        assertThatThrownBy(request::call)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only be executed once");
    }

    @Test
    void firstTurnOptionsAreAppliedBeforeSessionCreation() {
        AtomicReference<QueryOptions> captured = new AtomicReference<>();
        SolonCodeSyncClient delegate = mock(SolonCodeSyncClient.class);
        when(delegate.getOptions()).thenReturn(org.noear.soloncode.sdk.transport.CLIOptions.builder()
                .model("request-model").systemPrompt("request-system").build());
        when(delegate.receiveResponse()).thenReturn(Arrays.asList(result()).iterator());
        SolonCodeClient client = new DefaultSolonCodeClient(options -> {
            captured.set(options);
            return delegate;
        });
        QueryOptions options = QueryOptions.builder()
                .model("request-model")
                .systemPrompt("request-system")
                .maxTurns(3)
                .build();

        client.prompt("hello").options(options).call();

        assertThat(captured.get()).isSameAs(options);
        verify(delegate).connect("hello");
    }

    @Test
    void streamResultAggregatesMessagesAndTerminalMetadata() {
        SolonCodeSyncClient delegate = mock(SolonCodeSyncClient.class);
        when(delegate.getOptions()).thenReturn(org.noear.soloncode.sdk.transport.CLIOptions.builder()
                .model("sonnet").build());
        when(delegate.receiveResponse()).thenReturn(Arrays.asList(assistant("one"), result()).iterator());
        SolonCodeClient client = new DefaultSolonCodeClient(delegate);

        StepVerifier.create(client.prompt("hello").streamResult())
                .assertNext(queryResult -> {
                    assertThat(queryResult.messages()).hasSize(2);
                    assertThat(queryResult.text()).contains("one");
                    assertThat(queryResult.metadata().model()).isEqualTo("sonnet");
                    assertThat(queryResult.isSuccessful()).isTrue();
                })
                .verifyComplete();
    }
}
