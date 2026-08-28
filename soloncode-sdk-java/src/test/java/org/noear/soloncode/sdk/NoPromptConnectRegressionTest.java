/*
 * Copyright 2025 soloncode
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.noear.soloncode.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the {@code connect()} / {@code query()} contract under soloncode
 * 的一次性执行语义。
 *
 * <p>
 * soloncode 的 {@code run} 子命令是一次性的：提示词作为 {@code run} 之后的第一个位置参数传入，
 * Agent 跑完即退出。因此：
 * </p>
 * <ul>
 * <li>{@code connect()}（无提示词）不能启动任何 CLI 进程 —— 无提示词的 {@code soloncode run}
 * 会以退出码 3 终止；进程延迟到首次 {@code query()} 时才启动。</li>
 * <li>{@code connect(prompt)} 恰好启动一个进程，提示词以位置参数投递，且只投递调用方自己的提示词。</li>
 * <li>后续每轮 {@code query()} 都是一个新进程，靠 {@code --resume} 续接上下文。</li>
 * </ul>
 *
 * <p>
 * CLI 是一个生成的 shell 桩，把每次调用的完整 argv 记录下来。这里不启动真实 SolonCode CLI、
 * 不需要凭据、不产生模型费用。
 * </p>
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("connect()/query() 必须匹配 soloncode run 的一次性语义")
class NoPromptConnectRegressionTest {

	/** Time allowed for an expected invocation to reach the stub's recording. */
	private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Quiet period observed after the expected traffic has arrived, so that an extra
	 * unsolicited invocation would have time to show up and fail the assertion.
	 */
	private static final Duration SETTLE = Duration.ofMillis(750);

	private static final String SENTINEL = "sentinel-first-user-message";

	/** argv 字段分隔符，与桩脚本保持一致。 */
	private static final String FIELD_SEP = "\u0001";

	@TempDir
	Path tempDir;

	private Path recording;

	private String stubCli;

	@BeforeEach
	void setUp() throws IOException {
		this.recording = tempDir.resolve("cli-argv.log");
		Files.createFile(recording);
		this.stubCli = writeStubCli();
	}

	@Nested
	@DisplayName("SolonCodeSyncClient")
	class Sync {

		@Test
		@DisplayName("connect() 不启动任何 CLI 进程")
		void noArgConnectStartsNoProcess() throws Exception {
			try (SolonCodeSyncClient client = newSyncClient()) {
				client.connect();
				quietPeriod();

				assertThat(invocations()).describedAs("CLI invocations after a no-argument connect()").isEmpty();
			}
		}

		@Test
		@DisplayName("connect() 后的首个 query() 就是 CLI 看到的第一个提示词")
		void noArgConnectSendsNoSyntheticPrompt() throws Exception {
			try (SolonCodeSyncClient client = newSyncClient()) {
				client.connect();
				client.query(SENTINEL);

				assertThat(awaitPrompts(1)).describedAs("first prompt the CLI ever sees").containsExactly(SENTINEL);
				quietPeriod();
				assertThat(prompts()).containsExactly(SENTINEL);
			}
		}

		@Test
		@DisplayName("connect(prompt) 只投递调用方的提示词，且仅一次")
		void explicitConnectSendsOnlyTheCallersPrompt() throws Exception {
			String prompt = "Summarise the build failure";
			try (SolonCodeSyncClient client = newSyncClient()) {
				client.connect(prompt);

				assertThat(awaitPrompts(1)).containsExactly(prompt);
				quietPeriod();
				assertThat(prompts()).containsExactly(prompt);
			}
		}

		@Test
		@DisplayName("后续 query() 各起一个新进程，并用 --resume 续接会话")
		void subsequentQueriesRestartTheProcessWithResume() throws Exception {
			try (SolonCodeSyncClient client = newSyncClient()) {
				client.connect();
				client.query("first");
				awaitPrompts(1);
				client.query("second");

				assertThat(awaitPrompts(2)).containsExactly("first", "second");

				List<String> first = invocations().get(0);
				List<String> second = invocations().get(1);
				assertThat(first).describedAs("首轮用 --session-id 固定会话").contains("--session-id");
				assertThat(first).describedAs("首轮不该 resume").doesNotContain("--resume");
				assertThat(second).describedAs("第二轮必须 --resume 续接上下文").contains("--resume");
			}
		}

	}

	@Nested
	@DisplayName("SolonCodeAsyncClient")
	class Async {

		@Test
		@DisplayName("connect() 不启动任何 CLI 进程")
		void noArgConnectStartsNoProcess() throws Exception {
			SolonCodeAsyncClient client = newAsyncClient();
			try {
				client.connect().block(ARRIVAL_TIMEOUT);
				quietPeriod();

				assertThat(invocations()).describedAs("CLI invocations after a no-argument connect()").isEmpty();
			}
			finally {
				closeQuietly(client);
			}
		}

		@Test
		@DisplayName("connect() 后的首个 query() 就是 CLI 看到的第一个提示词")
		void noArgConnectSendsNoSyntheticPrompt() throws Exception {
			SolonCodeAsyncClient client = newAsyncClient();
			Disposable turn = null;
			try {
				client.connect().block(ARRIVAL_TIMEOUT);
				turn = client.query(SENTINEL).messages().subscribe();

				assertThat(awaitPrompts(1)).describedAs("first prompt the CLI ever sees").containsExactly(SENTINEL);
				quietPeriod();
				assertThat(prompts()).containsExactly(SENTINEL);
			}
			finally {
				dispose(turn);
				closeQuietly(client);
			}
		}

		@Test
		@DisplayName("connect(prompt) 只投递调用方的提示词，且仅一次")
		void explicitConnectSendsOnlyTheCallersPrompt() throws Exception {
			String prompt = "Summarise the build failure";
			SolonCodeAsyncClient client = newAsyncClient();
			Disposable turn = null;
			try {
				turn = client.connect(prompt).messages().subscribe();

				assertThat(awaitPrompts(1)).containsExactly(prompt);
				quietPeriod();
				assertThat(prompts()).containsExactly(prompt);
			}
			finally {
				dispose(turn);
				closeQuietly(client);
			}
		}

	}

	// ---------------------------------------------------------------- helpers

	private SolonCodeSyncClient newSyncClient() {
		return SolonCodeClient.sync().workingDirectory(tempDir).stdio(stubCli).timeout(ARRIVAL_TIMEOUT).build();
	}

	private SolonCodeAsyncClient newAsyncClient() {
		return SolonCodeClient.async().workingDirectory(tempDir).stdio(stubCli).timeout(ARRIVAL_TIMEOUT).build();
	}

	/**
	 * Writes a stand-in for the SolonCode CLI. It appends its own argv (one line per
	 * invocation, fields separated by U+0001) to the recording file and produces no
	 * output, so no model is ever contacted.
	 */
	private String writeStubCli() throws IOException {
		Path stub = tempDir.resolve("soloncode-stub.sh");
		String script = "#!/bin/sh\n"
				+ "# Deterministic stand-in for the SolonCode CLI used by NoPromptConnectRegressionTest.\n"
				+ "# Records the full argv of every invocation; contacts nothing.\n" + "line=''\n" + "for a in \"$@\"; do\n"
				+ "    line=\"$line$a$(printf '\\001')\"\n" + "done\n" + "printf '%s\\n' \"$line\" >> '"
				+ recording.toAbsolutePath() + "'\n";
		Files.write(stub, script.getBytes(StandardCharsets.UTF_8));
		Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwxr-xr-x"));
		return stub.toAbsolutePath().toString();
	}

	/** 每次 CLI 调用的 argv（不含程序名），按调用顺序。 */
	private List<List<String>> invocations() throws IOException {
		List<List<String>> result = new ArrayList<>();
		for (String line : Files.readAllLines(recording, StandardCharsets.UTF_8)) {
			if (line.trim().isEmpty()) {
				continue;
			}
			List<String> args = new ArrayList<>();
			for (String part : line.split(FIELD_SEP)) {
				if (!part.isEmpty()) {
					args.add(part);
				}
			}
			result.add(args);
		}
		return result;
	}

	/**
	 * 每次调用投递的提示词：{@code run} 之后的第一个位置参数。
	 *
	 * <p>
	 * 这也顺带钉住了参数顺序 —— Solon argx 对选项做贪心 lookahead，提示词一旦落到某个 {@code --flag}
	 * 后面就会被吃成该选项的值。
	 * </p>
	 */
	private List<String> prompts() throws IOException {
		List<String> result = new ArrayList<>();
		for (List<String> args : invocations()) {
			assertThat(args).describedAs("CLI argv").isNotEmpty();
			assertThat(args.get(0)).describedAs("soloncode 子命令").isEqualTo("run");
			if (args.size() > 1 && !args.get(1).startsWith("--")) {
				result.add(args.get(1));
			}
		}
		return result;
	}

	private List<String> awaitPrompts(int expected) throws Exception {
		long deadline = System.nanoTime() + ARRIVAL_TIMEOUT.toNanos();
		List<String> found = prompts();
		while (found.size() < expected && System.nanoTime() < deadline) {
			Thread.sleep(25);
			found = prompts();
		}
		assertThat(found).describedAs("prompts recorded within %s", ARRIVAL_TIMEOUT)
			.hasSizeGreaterThanOrEqualTo(expected);
		return found;
	}

	private static void dispose(Disposable subscription) {
		if (subscription != null) {
			subscription.dispose();
		}
	}

	/**
	 * {@link SolonCodeAsyncClient} is not {@code AutoCloseable} — its {@code close()}
	 * returns a {@code Mono} — so shutdown is driven explicitly.
	 */
	private static void closeQuietly(SolonCodeAsyncClient client) {
		client.close().block(ARRIVAL_TIMEOUT);
	}

	private void quietPeriod() throws InterruptedException {
		Thread.sleep(SETTLE.toMillis());
	}

}
