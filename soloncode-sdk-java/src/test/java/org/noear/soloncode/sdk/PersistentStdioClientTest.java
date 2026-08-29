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

import static org.assertj.core.api.Assertions.assertThat;

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
