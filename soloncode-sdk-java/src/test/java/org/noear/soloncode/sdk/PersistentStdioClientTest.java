/*
 * Copyright 2025 soloncode
 * Licensed under the Apache License, Version 2.0
 */
package org.noear.soloncode.sdk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.noear.soloncode.sdk.types.Message;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 客户端级离线回归：默认 stdio 在同一个 soloncode stream 进程内承载多轮。 */
@DisabledOnOs(OS.WINDOWS)
class PersistentStdioClientTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path tempDir;

    private Path invocationLog;
    private Path stdinLog;
    private String fakeCli;

    @BeforeEach
    void setUp() throws Exception {
        invocationLog = tempDir.resolve("invocations.log");
        stdinLog = tempDir.resolve("stdin.log");
        Path script = tempDir.resolve("fake-stream-cli.sh");
        String body = "#!/bin/bash\n"
                + "printf '%s\\n' \"$1\" >> '" + invocationLog.toAbsolutePath() + "'\n"
                + "printf '%s\\n' '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"client-stream\",\"model\":\"fake\"}'\n"
                + "while IFS= read -r line; do\n"
                + "  printf '%s\\n' \"$line\" >> '" + stdinLog.toAbsolutePath() + "'\n"
                + "  if [[ \"$line\" == *'\"type\":\"control_request\"'* ]]; then\n"
                + "    id=$(printf '%s' \"$line\" | sed -n 's/.*\"request_id\":\"\\([^\"]*\\)\".*/\\1/p')\n"
                + "    printf '{\"type\":\"control_response\",\"response\":{\"subtype\":\"error\",\"request_id\":\"%s\",\"error\":\"unsupported control\"}}\\n' \"$id\"\n"
                + "    continue\n"
                + "  fi\n"
                + "  case \"$line\" in *timeout*) sleep 5; continue ;; *slow*) sleep 1 ;; esac\n"
                + "  printf '%s\\n' '{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}'\n"
                + "  printf '%s\\n' '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"client-stream\"}'\n"
                + "done\n";
        Files.write(script, body.getBytes(StandardCharsets.UTF_8));
        script.toFile().setExecutable(true);
        fakeCli = script.toAbsolutePath().toString();
    }

    @Test
    void syncClientReusesProcessAcrossTurns() throws Exception {
        try (SolonCodeSyncClient client = SolonCodeClient.sync()
                .workingDirectory(tempDir).stdio(fakeCli).timeout(TIMEOUT).build()) {
            client.connect();
            drain(client.queryAndReceive("first"));
            drain(client.queryAndReceive("second"));
        }

        assertOneStreamProcessAndTwoUserFrames();
    }

    @Test
    void asyncClientInstallsTurnSinkBeforeFastPersistentResponse() {
        SolonCodeAsyncClient client = SolonCodeClient.async()
                .workingDirectory(tempDir).stdio(fakeCli).timeout(TIMEOUT).build();
        try {
            client.connect("first").messages().blockLast(TIMEOUT);
            client.query("second").messages().blockLast(TIMEOUT);
        } finally {
            client.close().block(TIMEOUT);
        }

        assertOneStreamProcessAndTwoUserFrames();
    }

    @Test
    void asyncFlatMapCanStartNextTurnAfterCompletionSignal() throws Exception {
        SolonCodeAsyncClient client = SolonCodeClient.async()
                .workingDirectory(tempDir).stdio(fakeCli).timeout(TIMEOUT).build();
        try {
            String second = client.connect("first").text()
                    .flatMap(ignored -> client.query("second").text())
                    .block(TIMEOUT);
            assertThat(second).isEqualTo("ok");
            awaitStdinLines(2);
        } finally {
            client.close().block(TIMEOUT);
        }
    }

    @Test
    void syncQuerySessionOverrideFailsFastForPersistentTransport() throws Exception {
        SolonCodeSyncClient client = SolonCodeClient.sync()
                .workingDirectory(tempDir).stdio(fakeCli).timeout(TIMEOUT).build();
        try {
            client.connect("first");
            drain(client.messages());
            assertThatThrownBy(() -> client.query("second", "override-session"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("cannot switch session_id");
        } finally {
            client.close();
        }
    }

    @Test
    void asyncControlRequestWaitsForMatchingErrorResponse() {
        SolonCodeAsyncClient client = SolonCodeClient.async()
                .workingDirectory(tempDir).stdio(fakeCli).timeout(TIMEOUT).build();
        try {
            client.connect("first").messages().blockLast(TIMEOUT);
            assertThatThrownBy(() -> client.setModel("new-model").block(TIMEOUT))
                    .hasMessageContaining("Failed to set model")
                    .hasRootCauseMessage("Control request failed: unsupported control");
        } finally {
            client.close().block(TIMEOUT);
        }
    }

    @Test
    void registeredHooksFailFastInsteadOfBeingSilentlyIgnored() {
        DefaultSolonCodeSyncClient client = (DefaultSolonCodeSyncClient) SolonCodeClient.sync()
                .workingDirectory(tempDir).stdio(fakeCli).build();
        client.registerHook(org.noear.soloncode.sdk.types.control.HookEvent.PRE_TOOL_USE, "Bash",
                input -> org.noear.soloncode.sdk.types.control.HookOutput.allow());
        try {
            assertThatThrownBy(client::connect)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("does not currently support hook");
        } finally {
            client.close();
        }
    }

    @Test
    void syncClientRejectsOverlappingTurnsWithoutReplacingFirstReceiver() throws Exception {
        try (SolonCodeSyncClient client = SolonCodeClient.sync()
                .workingDirectory(tempDir).stdio(fakeCli).timeout(TIMEOUT).build()) {
            client.connect();
            client.query("slow-first");
            assertThatThrownBy(() -> client.query("second"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still active");
            drain(client.messages());
            drain(client.queryAndReceive("second"));
        }
    }

    @Test
    void syncClientTimeoutInterruptsAndFailsTheTurn() {
        try (SolonCodeSyncClient client = SolonCodeClient.sync()
                .workingDirectory(tempDir).stdio(fakeCli).timeout(Duration.ofMillis(200)).build()) {
            client.connect();
            assertThatThrownBy(() -> drain(client.queryAndReceive("timeout")))
                    .hasMessageContaining("Stream failed")
                    .hasRootCauseMessage("Response timed out after PT0.2S");
        }
    }

    @Test
    void asyncClientRejectsOverlappingSubscribers() throws Exception {
        SolonCodeAsyncClient client = SolonCodeClient.async()
                .workingDirectory(tempDir).stdio(fakeCli).timeout(TIMEOUT).build();
        Disposable first = null;
        try {
            client.connect().block(TIMEOUT);
            first = client.query("slow-first").messages().subscribe();
            awaitStdinLines(1);
            assertThatThrownBy(() -> client.query("second").messages().blockLast(TIMEOUT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still active");
        } finally {
            if (first != null) {
                first.dispose();
            }
            client.close().block(TIMEOUT);
        }
    }

    private void awaitStdinLines(int count) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline
                && (!Files.exists(stdinLog) || Files.readAllLines(stdinLog, StandardCharsets.UTF_8).size() < count)) {
            Thread.sleep(20);
        }
        assertThat(Files.readAllLines(stdinLog, StandardCharsets.UTF_8)).hasSizeGreaterThanOrEqualTo(count);
    }

    private static void drain(Iterable<Message> messages) {
        for (Message ignored : messages) {
            // 消费到 ResultMessage，确保下一轮开始前上一轮已完整结束。
        }
    }

    private void assertOneStreamProcessAndTwoUserFrames() {
        try {
            assertThat(Files.readAllLines(invocationLog, StandardCharsets.UTF_8)).containsExactly("stream");
            List<String> lines = Files.readAllLines(stdinLog, StandardCharsets.UTF_8);
            assertThat(lines).hasSize(2);
            assertThat(lines.get(0)).contains("\"type\":\"user\"").contains("first");
            assertThat(lines.get(1)).contains("\"type\":\"user\"").contains("second");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
