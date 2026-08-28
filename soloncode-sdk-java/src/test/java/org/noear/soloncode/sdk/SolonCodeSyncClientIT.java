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
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.JsonSchema;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.SystemMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.noear.soloncode.sdk.util.SdkCollections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SolonCodeSyncClient with the real soloncode CLI.
 *
 * <p>
 * <b>soloncode 语义约束（与 claude 的差异，决定了本类的断言口径）：</b>
 * </p>
 * <ul>
 * <li>{@code soloncode run} 是一次性执行：每轮 {@code query()} 重开一个 CLI 进程，本轮结束进程即退出。
 * 因此「连接状态」只在一轮执行期间成立，不存在长驻双向会话。</li>
 * <li>assistant 事件是 <b>token 级增量帧</b>，单帧只含一个片段；断言必须聚合本轮所有 assistant 文本，
 * 不能假设某一条 AssistantMessage 含完整答案。</li>
 * <li>断言只覆盖 <b>协议契约</b>（init 事件、result 事件、会话 ID 贯通、聚合文本非空、结构化输出形状），
 * 不断言模型的具体答案文字 —— 那依赖外部模型，不具备可复现性。</li>
 * <li>不传 {@code --model}：{@code CLIOptions.MODEL_HAIKU} 是 claude 的模型 ID，soloncode 侧
 * 对未注册模型会回落到默认模型，传入只会引入无意义的不确定性。模型由 CLI 侧配置决定。</li>
 * <li>统一 {@code bare(true)}：跳过技能/MCP/记忆自动发现，冷启动更快、更可复现（同
 * {@link SolonCodeRealCliIT}）。</li>
 * </ul>
 *
 * <p>
 * 每个用例都有 120 秒硬超时（{@link Timeout}）：一次性进程若异常退出，
 * 阻塞式 {@code receiveResponse()} 迭代器由 EndOfStream 终止，但 CLI 挂死时仍需外层兜底，
 * 杜绝整类 IT 无限期挂住。
 * </p>
 */
@DisplayName("soloncode 同步客户端集成")
@Timeout(value = SolonCodeSyncClientIT.TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
class SolonCodeSyncClientIT extends SolonCodeCliTestBase {

	/** 单个用例的硬超时上限（秒）。 */
	static final long TEST_TIMEOUT_SECONDS = 120;

	/** CLI 冷启动 + 一轮推理的时间上限。 */
	private static final Duration CLI_TIMEOUT = Duration.ofMinutes(2);

	private SolonCodeClient.SyncSpec baseClient() {
		return SolonCodeClient.sync()
			.workingDirectory(workingDirectory())
			.cliPath(getSolonCodeCliPath())
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.bare(true)
			.maxTurns(1)
			.timeout(CLI_TIMEOUT);
	}

	@Test
	@DisplayName("单轮：收到 init 与 result 事件，聚合 assistant 文本非空")
	void shouldConnectAndReceiveResponse() {
		try (SolonCodeSyncClient client = baseClient().build()) {

			// When
			client.connect("回复两个字：收到");

			// Then
			Turn turn = drainTurn(client);

			assertThat(turn.messages).describedAs("本轮必须收到消息").isNotEmpty();

			assertThat(turn.initSessionId).describedAs("必须收到 system.init 事件并携带 session_id").isNotBlank();

			assertThat(turn.result).describedAs("必须收到 result 事件（stream-json 尾帧）").isNotNull();
			assertThat(turn.result.sessionId()).describedAs("result 的 session_id 必须与 init 一致")
				.isEqualTo(turn.initSessionId);
			// soloncode 的 result 事件没有 subtype，SDK 按 is_error 推导 success/error_during_execution
			assertThat(turn.result.subtype()).describedAs("SDK 需按 is_error 推导 subtype")
				.isIn("success", "error_during_execution");
			assertThat(turn.result.isError()).describedAs("本轮不应以错误结束（result.error=%s）", turn.result.result())
				.isFalse();

			// assistant 是 token 级增量帧：只能断言聚合后的文本
			assertThat(turn.assistantText()).describedAs("聚合后的 assistant 文本不应为空").isNotBlank();
		}
	}

	@Test
	@DisplayName("多轮：第二轮走 --resume，两轮共用同一会话 ID")
	void shouldMaintainContextAcrossQueries() {
		try (SolonCodeSyncClient client = baseClient().build()) {

			// 第一轮：--session-id
			client.connect("记住数字 7，只回复 OK。");
			Turn first = drainTurn(client);

			// 第二轮：--resume（一次性进程模型下重开进程续接上下文）
			client.query("刚才的数字是几？");
			Turn second = drainTurn(client);

			assertThat(first.result).describedAs("第一轮必须收到 result 事件").isNotNull();
			assertThat(second.result).describedAs("第二轮必须收到 result 事件").isNotNull();

			assertThat(first.initSessionId).describedAs("第一轮 init 必须携带 session_id").isNotBlank();
			assertThat(second.initSessionId).describedAs("第二轮 init 必须携带 session_id").isNotBlank();
			assertThat(second.initSessionId).describedAs("第二轮必须沿用第一轮的会话 ID（--resume 生效）")
				.isEqualTo(first.initSessionId);
			assertThat(second.result.sessionId()).describedAs("result 的 session_id 必须与首轮一致")
				.isEqualTo(first.initSessionId);

			// 说明：原用例断言第二轮答案含 "blue"。上下文记忆属于模型行为，
			// 受模型/温度/限流影响不可复现，因此这里只断言 SDK 与 CLI 的会话贯通契约。
			assertThat(second.assistantText()).describedAs("第二轮聚合 assistant 文本不应为空").isNotBlank();
		}
	}

	@Test
	@DisplayName("连接状态：一次性执行语义下 connect 前后与 close 后的状态")
	void shouldReportConnectedStatusCorrectly() {
		SolonCodeSyncClient client = baseClient().build();
		try {
			// connect 之前：尚无 CLI 进程
			assertThat(client.isConnected()).describedAs("connect 之前不应处于连接状态").isFalse();

			// connect(prompt) 会立即拉起本轮 CLI 进程
			client.connect("回复两个字：收到");
			assertThat(client.isConnected()).describedAs("本轮进程刚启动，应处于连接状态").isTrue();

			Turn turn = drainTurn(client);
			assertThat(turn.result).describedAs("必须收到 result 事件").isNotNull();

			// 注意：本轮 result 之后 CLI 进程自行退出（run 一次性语义），
			// isConnected() 会随之转为 false，这不是错误状态，因此不对该瞬时值做断言。
		}
		finally {
			client.close();
		}

		assertThat(client.isConnected()).describedAs("close 之后必须为未连接").isFalse();
	}

	@Test
	@DisplayName("systemPrompt 被 soloncode 忽略：仅告警，本轮仍正常完成")
	void shouldIgnoreSystemPromptAndStillComplete() {
		// soloncode run 没有 --system-prompt / --append-system-prompt：
		// StreamingTransport 只打印告警并丢弃该配置（见 buildStreamingCommand）。
		// 因此这里断言的是「配置被忽略但会话仍正常完成」，而不是人格化后的措辞。
		// 将来 CLI 若支持 --system-prompt，可恢复对系统提示词生效的断言。
		try (SolonCodeSyncClient client = baseClient().systemPrompt("You are a pirate. Always respond like a pirate.")
			.build()) {

			client.connect("说一句问候");

			Turn turn = drainTurn(client);

			assertThat(turn.result).describedAs("systemPrompt 被忽略后仍必须收到 result 事件").isNotNull();
			assertThat(turn.result.isError()).describedAs("忽略 systemPrompt 不应导致本轮失败").isFalse();
			assertThat(turn.assistantText()).describedAs("聚合 assistant 文本不应为空").isNotBlank();
		}
	}

	@Test
	@DisplayName("关闭：消费完一轮后可安全重复关闭")
	void shouldCloseCleanlyAfterUse() {
		SolonCodeSyncClient client = baseClient().build();

		client.connect("回复两个字：收到");
		Turn turn = drainTurn(client);
		assertThat(turn.result).describedAs("必须收到 result 事件").isNotNull();

		client.close();

		assertThat(client.isConnected()).isFalse();

		// 幂等：重复 close 不应抛异常
		client.close();
		client.close();
	}

	@Test
	@DisplayName("--json-schema 透传：result 事件携带结构化输出时形状正确")
	void shouldReturnStructuredOutputWithJsonSchema() {
		// soloncode run 支持 --json-schema（PrintModeOptions 解析该选项），
		// 但 structured_output 仅在「模型答案整体可解析为 JSON」时才会出现（PrintMode.tryParseJson）。
		// 因此断言分两层：协议层必须有 result；结构化输出存在时才校验其形状（不校验具体数值）。
		JsonSchema schema = JsonSchema.ofObject(
				SdkCollections.map("answer", (Object) SdkCollections.map("type", "number"), "explanation",
						(Object) SdkCollections.map("type", "string")),
				SdkCollections.list("answer", "explanation"));

		CLIOptions options = CLIOptions.builder()
			.jsonSchema(schema.toMap())
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.bare(true)
			.maxTurns(1)
			.build();

		try (SolonCodeSyncClient client = SolonCodeClient.sync(options)
			.workingDirectory(workingDirectory())
			.cliPath(getSolonCodeCliPath())
			.timeout(CLI_TIMEOUT)
			.build()) {

			client.connect("2+2 等于几？请只输出 JSON，包含 answer(number) 与 explanation(string) 两个字段。");

			Turn turn = drainTurn(client);

			ResultMessage resultMessage = turn.result;
			assertThat(resultMessage).describedAs("必须收到 result 事件").isNotNull();
			assertThat(resultMessage.isError()).describedAs("--json-schema 不应导致本轮失败").isFalse();
			assertThat(resultMessage.result()).describedAs("result 文本不应为空").isNotBlank();

			if (resultMessage.hasStructuredOutput()) {
				Map<String, Object> output = resultMessage.getStructuredOutputAsMap();
				assertThat(output).describedAs("structured_output 应被解析为映射").isNotNull();
				assertThat(output).describedAs("structured_output 应含 schema 声明的字段")
					.containsKeys("answer", "explanation");
				assertThat(output.get("answer")).describedAs("answer 字段应为数值（schema 约束）")
					.isInstanceOf(Number.class);
				assertThat(String.valueOf(output.get("explanation"))).isNotBlank();
			}
		}
	}

	// ========================================================================
	// Helpers
	// ========================================================================

	/**
	 * 阻塞消费一轮响应。
	 *
	 * <p>
	 * {@code receiveResponse()} 的迭代器在收到 result 事件或 CLI 进程退出（EndOfStream）时终止，
	 * 因此这里不会因缺失 result 事件而无限等待；外层 {@link Timeout} 兜底 CLI 挂死的极端情况。
	 * </p>
	 */
	private static Turn drainTurn(SolonCodeSyncClient client) {
		Turn turn = new Turn();
		Iterator<ParsedMessage> response = client.receiveResponse();
		while (response.hasNext()) {
			ParsedMessage parsed = response.next();
			if (!parsed.isRegularMessage()) {
				continue;
			}
			turn.accept(parsed.asMessage());
		}
		return turn;
	}

	/** 一轮响应的聚合视图：init 会话 ID、result 事件、拼接后的 assistant 文本。 */
	private static final class Turn {

		private final List<Message> messages = new ArrayList<>();

		private final StringBuilder text = new StringBuilder();

		private String initSessionId;

		private ResultMessage result;

		void accept(Message message) {
			messages.add(message);

			if (message instanceof SystemMessage) {
				SystemMessage system = (SystemMessage) message;
				if ("init".equals(system.subtype()) && system.data() != null) {
					Object sessionId = system.data().get("session_id");
					if (sessionId != null && initSessionId == null) {
						initSessionId = String.valueOf(sessionId);
					}
				}
			}
			else if (message instanceof AssistantMessage) {
				// token 级增量帧：必须累加
				((AssistantMessage) message).getTextContent().ifPresent(text::append);
			}
			else if (message instanceof ResultMessage) {
				result = (ResultMessage) message;
			}
		}

		String assistantText() {
			return text.toString();
		}

	}

}
