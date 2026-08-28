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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.SystemMessage;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SolonCodeAsyncClient with the real soloncode CLI.
 *
 * <p>
 * <b>本类此前会在全量 IT 运行时永久挂住，根因有两条，均已在用例侧修正：</b>
 * </p>
 * <ol>
 * <li><b>无上限的阻塞订阅</b>：原用例统一使用 {@code StepVerifier#verifyComplete()}，该方法
 * 不带超时，会无限期阻塞 JUnit 线程。现全部改为 {@code verify(VERIFY_TIMEOUT)}，
 * 并在消息流上叠加 {@code timeout(TURN_TIMEOUT)}，外层再加 {@link Timeout} 兜底。</li>
 * <li><b>每轮消息流只由 result 事件终结</b>：{@code DefaultSolonCodeAsyncClient.handleMessage}
 * 只在收到 {@link ResultMessage} 时 {@code tryEmitComplete()} 当前轮 sink，
 * 并不处理传输层的 {@code ParsedMessage.EndOfStream}（同步实现是处理的）。于是 CLI 进程
 * 若异常退出而没有输出 result 尾帧，该 Flux 永远不会完成 —— 叠加第 1 条即为永久挂住。
 * 用例侧无法修改主代码，故一律给等待设上限，并在 {@code finally} 中显式 {@code close()}：
 * 传输层的 inbound/outbound/error 调度线程来自
 * {@code Executors.newSingleThreadExecutor}（非守护线程），不关闭会让 failsafe 的 JVM
 * 在测试结束后也无法退出。</li>
 * </ol>
 *
 * <p>
 * <b>soloncode 语义约束：</b>{@code run} 是一次性执行，每轮 {@code query()} 重开进程，
 * 首轮 {@code --session-id}、后续轮 {@code --resume}；assistant 是 token 级增量帧，
 * 断言必须聚合本轮全部文本；断言只覆盖协议契约，不涉及模型答案文字。
 * </p>
 */
@DisplayName("soloncode 异步客户端集成")
@Timeout(value = SolonCodeAsyncClientIT.TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
class SolonCodeAsyncClientIT extends SolonCodeCliTestBase {

	/** 单个用例的硬超时上限（秒）。 */
	static final long TEST_TIMEOUT_SECONDS = 120;

	/** CLI 冷启动 + 一轮推理的时间上限。 */
	private static final Duration CLI_TIMEOUT = Duration.ofMinutes(2);

	/** 单轮消息流的静默上限：超过即以 TimeoutException 终止，绝不无限等待。 */
	private static final Duration TURN_TIMEOUT = Duration.ofSeconds(100);

	/** StepVerifier 的阻塞上限。 */
	private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(110);

	/** close() 的阻塞上限。 */
	private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

	private SolonCodeAsyncClient newClient() {
		return newClientSpec().build();
	}

	private SolonCodeClient.AsyncSpec newClientSpec() {
		// 不传 --model：模型名/别名由工作区配置注册，传未注册的名字只会回落到默认模型，引入无意义的
		// 不确定性。bare(true)/maxTurns(1) 让冷启动更快、更可复现。
		return SolonCodeClient.async()
			.workingDirectory(workingDirectory())
			.cliPath(getSolonCodeCliPath())
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.bare(true)
			.maxTurns(1)
			.timeout(CLI_TIMEOUT);
	}

	@Test
	@DisplayName("单轮：messages() 在 result 事件后完成，聚合 assistant 文本非空")
	void shouldConnectAndReceiveResponse() {
		SolonCodeAsyncClient client = newClient();
		try {
			StepVerifier.create(bounded(client.connect("回复两个字：收到").messages()).collectList())
				.assertNext(messages -> {
					assertThat(messages).describedAs("本轮必须收到消息").isNotEmpty();

					ResultMessage result = lastResult(messages);
					assertThat(result).describedAs("必须收到 result 事件（stream-json 尾帧）").isNotNull();
					assertThat(result.sessionId()).describedAs("result 必须携带非空 session_id").isNotBlank();
					// soloncode 的 result 事件没有 subtype，SDK 按 is_error 推导
					assertThat(result.subtype()).isIn("success", "error_during_execution");
					assertThat(result.isError()).describedAs("本轮不应以错误结束").isFalse();

					// assistant 是 token 级增量帧：断言聚合结果，不断言单帧内容
					assertThat(assistantText(messages)).describedAs("聚合后的 assistant 文本不应为空").isNotBlank();
				})
				.expectComplete().verify(VERIFY_TIMEOUT);
		}
		finally {
			closeQuietly(client);
		}
	}

	@Test
	@DisplayName("多轮：第二轮走 --resume，两轮共用同一会话 ID")
	void shouldMaintainContextAcrossQueries() {
		SolonCodeAsyncClient client = newClient();

		// 跨轮 handler 在 connect 之前注册：它不受「每轮 sink 只在订阅时创建」的时序影响，
		// 因此是采集 system.init 事件最可靠的通道。
		List<String> initSessionIds = new CopyOnWriteArrayList<>();
		client.onMessage(message -> {
			if (message instanceof SystemMessage) {
				SystemMessage system = (SystemMessage) message;
				if ("init".equals(system.subtype()) && system.data() != null) {
					Object sessionId = system.data().get("session_id");
					if (sessionId != null) {
						initSessionIds.add(String.valueOf(sessionId));
					}
				}
			}
		});

		try {
			// 第一轮：--session-id
			StepVerifier.create(bounded(client.connect("记住数字 7，只回复 OK。").messages()).collectList())
				.assertNext(messages -> assertThat(lastResult(messages)).describedAs("第一轮必须收到 result 事件")
					.isNotNull())
				.expectComplete().verify(VERIFY_TIMEOUT);

			// 第二轮：重开进程 + --resume 续接（不是长驻会话的中途注入）
			StepVerifier.create(bounded(client.query("刚才的数字是几？").messages()).collectList())
				.assertNext(messages -> {
					ResultMessage result = lastResult(messages);
					assertThat(result).describedAs("第二轮必须收到 result 事件").isNotNull();
					assertThat(result.sessionId()).describedAs("第二轮 result 必须携带非空 session_id").isNotBlank();
					assertThat(assistantText(messages)).describedAs("第二轮聚合 assistant 文本不应为空").isNotBlank();
				})
				.expectComplete().verify(VERIFY_TIMEOUT);

			assertThat(initSessionIds).describedAs("两轮都应收到 system.init 事件").hasSize(2);
			assertThat(initSessionIds.get(1)).describedAs("第二轮必须沿用第一轮的会话 ID（--resume 生效）")
				.isEqualTo(initSessionIds.get(0));

			// 说明：原用例断言第二轮答案含 "blue"（模型记忆行为），不可复现，改为断言会话贯通契约。
		}
		finally {
			closeQuietly(client);
		}
	}

	@Test
	@DisplayName("连接状态：一次性执行语义下 connect 前、消息到达时与 close 后的状态")
	void shouldReportConnectedStatusCorrectly() {
		SolonCodeAsyncClient client = newClient();
		AtomicBoolean connectedWhileStreaming = new AtomicBoolean(false);
		try {
			assertThat(client.isConnected()).describedAs("订阅之前不应处于连接状态").isFalse();

			StepVerifier
				.create(bounded(client.connect("回复两个字：收到").messages())
					.doOnNext(message -> connectedWhileStreaming.compareAndSet(false, client.isConnected()))
					.collectList())
				.assertNext(messages -> assertThat(lastResult(messages)).describedAs("必须收到 result 事件").isNotNull())
				.expectComplete().verify(VERIFY_TIMEOUT);

			assertThat(connectedWhileStreaming).describedAs("本轮消息到达期间 CLI 进程应存活").isTrue();

			// 注意：result 之后 CLI 进程自行退出（run 一次性语义），isConnected() 会转为 false，
			// 这是预期行为而非错误，故不对该瞬时值做断言。
		}
		finally {
			closeQuietly(client);
		}

		assertThat(client.isConnected()).describedAs("close 之后必须为未连接").isFalse();
	}

	@Test
	@DisplayName("systemPrompt 被 soloncode 忽略：仅告警，本轮仍正常完成")
	void shouldIgnoreSystemPromptAndStillComplete() {
		// soloncode run 没有 --system-prompt：StreamingTransport 只打印告警并丢弃该配置。
		// 因此断言「配置被忽略但会话仍正常完成」，而不是人格化后的措辞。
		// 将来 CLI 若支持 --system-prompt，可恢复对系统提示词生效的断言。
		SolonCodeAsyncClient client = newClientSpec().systemPrompt("You are a pirate. Always respond like a pirate.")
			.build();
		try {
			StepVerifier.create(bounded(client.connect("说一句问候").messages()).collectList()).assertNext(messages -> {
				ResultMessage result = lastResult(messages);
				assertThat(result).describedAs("systemPrompt 被忽略后仍必须收到 result 事件").isNotNull();
				assertThat(result.isError()).describedAs("忽略 systemPrompt 不应导致本轮失败").isFalse();
				assertThat(assistantText(messages)).describedAs("聚合 assistant 文本不应为空").isNotBlank();
			}).expectComplete().verify(VERIFY_TIMEOUT);
		}
		finally {
			closeQuietly(client);
		}
	}

	@Test
	@DisplayName("关闭：消费完一轮后可安全重复关闭")
	void shouldCloseCleanlyAfterUse() {
		SolonCodeAsyncClient client = newClient();

		StepVerifier.create(bounded(client.connect("回复两个字：收到").messages()).collectList())
			.assertNext(messages -> assertThat(lastResult(messages)).describedAs("必须收到 result 事件").isNotNull())
			.expectComplete().verify(VERIFY_TIMEOUT);

		StepVerifier.create(client.close()).expectComplete().verify(CLOSE_TIMEOUT);
		assertThat(client.isConnected()).isFalse();

		// 幂等：重复 close 应立即完成
		StepVerifier.create(client.close()).expectComplete().verify(CLOSE_TIMEOUT);
		StepVerifier.create(client.close()).expectComplete().verify(CLOSE_TIMEOUT);
	}

	@Test
	@DisplayName("响应式算子：可在 token 级增量帧上做 filter/map/take")
	void shouldSupportReactiveStreamOperations() {
		SolonCodeAsyncClient client = newClient();
		try {
			// take(1) 会取消上游订阅：一次性进程仍在运行，靠 finally 的 close() 收尾。
			StepVerifier
				.create(bounded(client.connect("说一句问候").messages()).ofType(AssistantMessage.class)
					.map(AssistantMessage::text)
					.filter(text -> !text.isEmpty())
					.take(1))
				.assertNext(text -> assertThat(text).describedAs("首个非空 assistant 增量帧").isNotEmpty())
				.expectComplete().verify(VERIFY_TIMEOUT);
		}
		finally {
			closeQuietly(client);
		}
	}

	// ========================================================================
	// Helpers
	// ========================================================================

	/** 给消息流套上静默超时：CLI 异常退出而无 result 尾帧时以 TimeoutException 终止，绝不挂住。 */
	private static Flux<Message> bounded(Flux<Message> messages) {
		return messages.timeout(TURN_TIMEOUT);
	}

	private static ResultMessage lastResult(List<Message> messages) {
		ResultMessage result = null;
		for (Message message : messages) {
			if (message instanceof ResultMessage) {
				result = (ResultMessage) message;
			}
		}
		return result;
	}

	/** 聚合本轮所有 assistant 增量帧的文本（单帧只含一个 token 片段）。 */
	private static String assistantText(List<Message> messages) {
		StringBuilder text = new StringBuilder();
		for (Message message : messages) {
			if (message instanceof AssistantMessage) {
				((AssistantMessage) message).getTextContent().ifPresent(text::append);
			}
		}
		return text.toString();
	}

	/**
	 * 关闭客户端并终止本轮 CLI 进程。
	 *
	 * <p>
	 * 必须执行：传输层的 inbound/outbound/error 调度线程是非守护线程，不关闭会让测试 JVM 无法退出。
	 * </p>
	 */
	private static void closeQuietly(SolonCodeAsyncClient client) {
		client.close().block(CLOSE_TIMEOUT);
	}

}
