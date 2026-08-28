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

package org.noear.soloncode.sdk.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.exceptions.SessionClosedException;
import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.parsing.ControlMessageParser;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP 通道传输：把同一组执行选项投递到服务端的 {@code /web/run} 端点，
 * 以 SSE 接收与 CLI stream-json 逐行同构的事件流。
 *
 * <p>与 {@link StdioTransport} 的差异只在承载方式：进程边界换成网络边界、
 * 工作目录换成服务端工作区标识。事件解析复用 {@link ControlMessageParser}，
 * 消息类型层零改动。契约见 {@code soloncode-cli/docs/run-headless-mode-http.md}：</p>
 *
 * <ul>
 * <li>请求体：{@code {"prompt":..., "options":{snake_case 字段}, "workspace":...}}，
 * options 字段与 CLI flag 一一对应，序列化规则对齐 RunRequestService 的白名单</li>
 * <li>响应：SSE 每行 {@code data:} 即 CLI 的一行 JSONL；连接在 result/error 后关闭</li>
 * <li>退出码语义承载在 result 事件（{@code is_error}）与 HTTP 状态码上：
 * 2/4（超轮次/超预算）是执行结论，HTTP 仍为 200</li>
 * <li>中断走独立端点 {@code /web/run/interrupt}（session 维度），one-shot 请求本身不承载取消</li>
 * </ul>
 *
 * <h2>不支持的操作</h2>
 * <ul>
 * <li>{@link #sendUserMessage} / {@link #sendMessage} / {@link #sendResponse} —
 * one-shot 请求模型下没有回写通道（与 StdioTransport 首轮之后的行为一致，抛
 * {@link TransportException}）。控制请求（hooks/can_use_tool 回调）在 HTTP 通道下
 * 不存在，收到的也不会有。</li>
 * </ul>
 *
 * @see Transport
 * @see TransportSpec#http(String, String, String)
 * @see StdioTransport
 */
public class HttpTransport implements Transport {

	private static final Logger logger = LoggerFactory.getLogger(HttpTransport.class);

	// ============================================================
	// Configuration
	// ============================================================

	private final String baseUrl;

	private final String token;

	private final String workspace;

	private final Duration defaultTimeout;

	private final ObjectMapper objectMapper = new ObjectMapper();

	/** SSE 行读取线程（每轮执行一个，覆盖多轮 resume 串接） */
	private final ExecutorService sseReader = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "soloncode-http-sse");
		t.setDaemon(true);
		return t;
	});

	// ============================================================
	// State
	// ============================================================

	private final AtomicInteger state = new AtomicInteger(STATE_DISCONNECTED);

	private final AtomicReference<Throwable> sessionError = new AtomicReference<>();

	private final AtomicReference<String> sessionId = new AtomicReference<>();

	private volatile boolean isClosing = false;

	/** 当前 SSE 连接（interrupt 不需要它，close 需要） */
	private volatile HttpURLConnection connection;

	/** 本轮执行要 resume 的会话 ID。 */
	private volatile String turnResume;

	/** 本轮执行要固定的会话 ID（首轮）。 */
	private volatile String turnSessionId;

	private final Sinks.Many<ParsedMessage> inboundSink = Sinks.many().replay().all();

	/** 已收到 result 事件（区分「执行结论」与「请求故障」）。 */
	private volatile boolean resultReceived = false;

	@Override
	public void setTurnSession(String sessionId, String resume) {
		this.turnSessionId = sessionId;
		this.turnResume = resume;
	}

	/**
	 * @param url /web/run 完整 URL
	 * @param token Bearer token；null 表示不带鉴权头
	 * @param workspace 服务端工作区标识；null 用服务端默认
	 * @param defaultTimeout 本轮执行超时
	 */
	public HttpTransport(String url, String token, String workspace, Duration defaultTimeout) {
		if (url == null || url.trim().isEmpty()) {
			throw new IllegalArgumentException("url must not be null or empty");
		}
		if (defaultTimeout == null) {
			throw new IllegalArgumentException("defaultTimeout must not be null");
		}
		this.baseUrl = url.trim();
		this.token = token;
		this.workspace = workspace;
		this.defaultTimeout = defaultTimeout;
	}

	// ============================================================
	// Session Lifecycle
	// ============================================================

	@Override
	public void startSession(String prompt, CLIOptions options, Consumer<ParsedMessage> messageHandler,
			ControlRequestHandler controlRequestHandler, Consumer<ControlResponse> controlResponseHandler)
			throws SolonCodeSDKException {

		if (!state.compareAndSet(STATE_DISCONNECTED, STATE_CONNECTING)) {
			int current = state.get();
			if (current == STATE_CLOSED) {
				throw new IllegalStateException("Transport has been closed and cannot be reused");
			}
			throw new IllegalStateException("Cannot start session in state: " + stateName(current));
		}

		try {
			String body = buildRequestBody(prompt, options);
			// 与 StdioTransport 同一红线：完整请求体可能携带 json-schema、工具清单，
			// prompt 本身常含敏感内容 —— INFO 只记录长度。
			logger.info("Starting HTTP run: {} ({} chars body)", baseUrl, body.length());
			logger.debug("HTTP run request body: {}", body);

			HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl).openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setConnectTimeout((int) Math.min(defaultTimeout.toMillis(), Integer.MAX_VALUE));
			// 读超时：SSE 长连接不设读超时（事件可能间隔较长），完成等待由 waitForCompletion 承担
			conn.setReadTimeout(0);
			conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			conn.setRequestProperty("Accept", "text/event-stream");
			if (token != null) {
				conn.setRequestProperty("Authorization", "Bearer " + token);
			}

			try (OutputStream os = conn.getOutputStream()) {
				os.write(body.getBytes(StandardCharsets.UTF_8));
				os.flush();
			}

			int status = conn.getResponseCode();
			if (status != 200) {
				String errorBody = readStream(conn.getErrorStream());
				conn.disconnect();
				throw httpStatusException(status, errorBody);
			}

			this.connection = conn;
			if (!state.compareAndSet(STATE_CONNECTING, STATE_CONNECTED)) {
				conn.disconnect();
				throw new TransportException("Failed to complete connection - unexpected state change");
			}

			// SSE 读取循环（独立线程）：data: 行 → ControlMessageParser → sink + handler
			final ControlMessageParser parser = new ControlMessageParser(
					options != null ? options.getEffectiveMaxBufferSize() : CLIOptions.DEFAULT_MAX_BUFFER_SIZE);
			sseReader.submit(() -> readSseStream(conn, parser, messageHandler));
		}
		catch (SolonCodeSDKException e) {
			state.set(STATE_DISCONNECTED);
			sessionError.compareAndSet(null, e);
			throw e;
		}
		catch (Exception e) {
			state.set(STATE_DISCONNECTED);
			sessionError.compareAndSet(null, e);
			throw new TransportException("Failed to start HTTP session: " + baseUrl, e);
		}
	}

	/**
	 * SSE 流读取：每个 {@code data:} 行即 CLI 的一行 JSONL。
	 */
	private void readSseStream(HttpURLConnection conn, ControlMessageParser parser,
			Consumer<ParsedMessage> messageHandler) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (!isClosing && (line = reader.readLine()) != null) {
				if (!line.startsWith("data:")) {
					continue; // event:/注释/空行 —— 事件体只在 data: 行
				}
				String payload = line.substring(5).trim();
				if (payload.isEmpty()) {
					continue;
				}
				try {
					ParsedMessage parsed = parser.parse(payload);
					if (parsed == null) {
						continue; // 未识别类型，向前兼容
					}
					if (parsed.isResultMessage()) {
						resultReceived = true;
						String sid = ((org.noear.soloncode.sdk.types.ResultMessage) parsed.asMessage()).sessionId();
						if (sid != null && !sid.isEmpty()) {
							sessionId.set(sid);
						}
					}
					Sinks.EmitResult emit = inboundSink.tryEmitNext(parsed);
					if (!emit.isSuccess() && !isClosing) {
						logger.error("Failed to emit inbound message: {}", emit);
					}
					messageHandler.accept(parsed);
				}
				catch (Exception e) {
					if (!isClosing) {
						logger.error("Failed to process SSE message: {}",
								payload.substring(0, Math.min(200, payload.length())), e);
					}
				}
			}
		}
		catch (IOException e) {
			if (!isClosing) {
				sessionError.compareAndSet(null, e);
				logger.error("Error reading SSE stream", e);
			}
		}
		finally {
			// 流结束（服务端在 result/error 后关闭连接）≈ 进程退出：完成 sink + 通知 handler
			isClosing = true;
			inboundSink.tryEmitComplete();
			try {
				messageHandler.accept(ParsedMessage.EndOfStream.INSTANCE);
			}
			catch (Exception e) {
				logger.debug("Error signaling session end to message handler", e);
			}
			conn.disconnect();
		}
	}

	// ============================================================
	// Request body: CLIOptions → /web/run JSON
	// ============================================================

	/**
	 * 构造请求体。options 字段名与 CLI flag 的映射对齐 RunRequestService 的白名单；
	 * stdio 通道下 CLI 不支持的选项（systemPrompt/mcpServers 等）在这里同样省略并告警，
	 * 与 {@code StdioTransport.buildStreamingCommand} 的告警语义保持一致——
	 * 防止调用方以为某个参数生效了。
	 */
	String buildRequestBody(String prompt, CLIOptions options) throws SolonCodeSDKException {
		Map<String, Object> optionsJson = new LinkedHashMap<>();

		// /web/run 恒以 stream-json 消费（SSE），output_format 不透传调用方设置
		optionsJson.put("output_format", "stream-json");

		if (options.getModel() != null) {
			optionsJson.put("model", options.getModel());
		}
		if (prompt != null && !prompt.isEmpty()) {
			// body 根字段，非 options
		}
		if (!options.getAllowedTools().isEmpty()) {
			optionsJson.put("allowed_tools", options.getAllowedTools());
		}
		if (!options.getDisallowedTools().isEmpty()) {
			optionsJson.put("disallowed_tools", options.getDisallowedTools());
		}
		if (options.getPermissionMode() != null) {
			PermissionMode mode0 = options.getPermissionMode();
			// 服务端收口（run-headless-mode-http.md 安全第 3 条）：bypass 系权限模式一律不接受。
			// 注意 CLIOptions.builder() 默认值就是 BYPASS_PERMISSIONS（stdio 场景合理），
			// 所以这里不能用「仅拦截显式指定」的思路 —— 默认值同样不能透传。
			if (mode0 == PermissionMode.DANGEROUSLY_SKIP_PERMISSIONS || mode0 == PermissionMode.BYPASS_PERMISSIONS) {
				logger.warn("{} is rejected by /web/run (permission-mode 收口，服务端强制); falling back to 'default'",
						mode0.getValue());
				optionsJson.put("permission_mode", PermissionMode.DEFAULT.getValue());
			}
			else {
				optionsJson.put("permission_mode", mode0.getValue());
			}
		}
		if (options.getMaxTurns() != null) {
			optionsJson.put("max_turns", options.getMaxTurns());
		}
		if (options.getMaxBudgetUsd() != null) {
			optionsJson.put("max_budget_usd", options.getMaxBudgetUsd());
		}
		if (options.getFallbackModel() != null && !options.getFallbackModel().isEmpty()) {
			optionsJson.put("fallback_model", options.getFallbackModel());
		}
		if (options.getJsonSchema() != null && !options.getJsonSchema().isEmpty()) {
			optionsJson.put("json_schema", options.getJsonSchema());
		}
		if (options.isBare()) {
			optionsJson.put("bare", true);
		}
		if (options.getAddDirs() != null && !options.getAddDirs().isEmpty()) {
			// 服务端工作区路径语义：客户端本地路径对服务端无意义，需显式传才透传
			logger.warn("add_dirs uses server-side paths; make sure they exist on the /web/run host");
			optionsJson.put("add_dirs", pathStrings(options.getAddDirs()));
		}

		// 会话串接：turn 级优先于 options 级（客户端层多轮 resume 的实现载体）
		String effectiveResume = turnResume != null ? turnResume : options.getResume();
		boolean resuming = effectiveResume != null && !effectiveResume.trim().isEmpty();
		if (resuming) {
			optionsJson.put("resume", effectiveResume);
		}
		else {
			if (options.isContinueConversation()) {
				optionsJson.put("continue", true);
			}
			String effectiveSessionId = turnSessionId != null ? turnSessionId : options.getSessionId();
			if (effectiveSessionId != null && !effectiveSessionId.trim().isEmpty()) {
				optionsJson.put("session_id", effectiveSessionId);
			}
		}

		// CLI/HTTP 均不支持的选项：告警省略（对齐 StdioTransport 的日志语义）
		warnUnsupported(options);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("prompt", prompt);
		body.put("options", optionsJson);
		if (workspace != null) {
			body.put("workspace", workspace);
		}
		try {
			return objectMapper.writeValueAsString(body);
		}
		catch (JsonProcessingException e) {
			throw new TransportException("Failed to serialize /web/run request body", e);
		}
	}

	private static List<String> pathStrings(List<java.nio.file.Path> dirs) {
		java.util.ArrayList<String> out = new java.util.ArrayList<>();
		for (java.nio.file.Path dir : dirs) {
			out.add(dir.toString());
		}
		return out;
	}

	private void warnUnsupported(CLIOptions options) {
		if (options.getSystemPrompt() != null) {
			logger.warn("systemPrompt is not supported by soloncode run; ignoring");
		}
		if (options.getTools() != null) {
			logger.warn("tools is not supported by soloncode run; use allowedTools/disallowedTools instead");
		}
		if (options.getAppendSystemPrompt() != null && !options.getAppendSystemPrompt().isEmpty()) {
			logger.warn("appendSystemPrompt is not supported by soloncode run; ignoring");
		}
		if (options.getMcpServers() != null && !options.getMcpServers().isEmpty()) {
			logger.warn("mcpServers is not supported by soloncode run (register MCP on server side); ignoring");
		}
		if (options.getMaxThinkingTokens() != null) {
			logger.warn("maxThinkingTokens is not supported by soloncode run; ignoring");
		}
		if (options.getExtraArgs() != null && !options.getExtraArgs().isEmpty()) {
			logger.warn("extraArgs is not supported over /web/run; ignoring");
		}
	}

	// ============================================================
	// Interrupt（独立端点）
	// ============================================================

	/**
	 * 中断本轮执行：POST {baseUrl}/interrupt，按 session_id 定位服务端活跃执行。
	 *
	 * <p>session 标识取 turn 级优先（resume 串接时是 resume 的目标会话）。
	 * 与 stdio 的 destroy() 不同，这里只发信号，SSE 流随后以 error(interrupted) 收尾。</p>
	 */
	@Override
	public void interrupt() {
		String sid = turnResume != null ? turnResume
				: (turnSessionId != null ? turnSessionId : sessionId.get());
		if (sid == null || sid.trim().isEmpty()) {
			logger.warn("interrupt() over HTTP requires a session id (session_id/resume); nothing to interrupt");
			return;
		}
		try {
			String interruptUrl = baseUrl.endsWith("/") ? baseUrl + "interrupt" : baseUrl + "/interrupt";
			HttpURLConnection conn = (HttpURLConnection) new URL(interruptUrl).openConnection();
			conn.setRequestMethod("POST");
			conn.setConnectTimeout(10_000);
			conn.setReadTimeout(10_000);
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
			if (token != null) {
				conn.setRequestProperty("Authorization", "Bearer " + token);
			}
			String form = "session_id=" + java.net.URLEncoder.encode(sid, "UTF-8");
			try (OutputStream os = conn.getOutputStream()) {
				os.write(form.getBytes(StandardCharsets.UTF_8));
			}
			int status = conn.getResponseCode();
			if (status == 202) {
				if (state.get() == STATE_CONNECTED) {
					state.set(STATE_CLOSING);
				}
				logger.info("Interrupt requested for session {} (202)", sid);
			}
			else if (status == 404) {
				logger.warn("No active run for session {} on server (404)", sid);
			}
			else {
				logger.warn("Interrupt for session {} returned unexpected status {}", sid, status);
			}
			conn.disconnect();
		}
		catch (Exception e) {
			logger.warn("Failed to interrupt session {} over HTTP: {}", sid, e.getMessage());
		}
	}

	// ============================================================
	// Status / Wait / Close
	// ============================================================

	@Override
	public boolean waitForCompletion(Duration timeout) throws SolonCodeSDKException {
		// 等待 SSE 流终结（complete/timeout），与进程 waitFor 语义对齐
		try {
			Mono<Void> completion = inboundSink.asFlux().then();
			if (timeout != null) {
				completion.block(timeout);
			}
			else {
				completion.block();
			}
		}
		catch (IllegalStateException e) {
			if (e.getCause() != null) {
				throw new TransportException("HTTP session failed", e.getCause());
			}
			throw e;
		}

		Throwable err = sessionError.get();
		if (err != null) {
			throw new TransportException("Session error", err);
		}
		return true;
	}

	@Override
	public boolean isRunning() {
		return state.get() == STATE_CONNECTED && !isClosing;
	}

	@Override
	public Throwable getSessionError() {
		return sessionError.get();
	}

	@Override
	public String getSessionId() {
		return sessionId.get();
	}

	@Override
	public int getState() {
		return state.get();
	}

	@Override
	public String getStateName() {
		return stateName(state.get());
	}

	private static String stateName(int stateValue) {
		switch (stateValue) {
			case STATE_DISCONNECTED:
				return "DISCONNECTED";
			case STATE_CONNECTING:
				return "CONNECTING";
			case STATE_CONNECTED:
				return "CONNECTED";
			case STATE_CLOSING:
				return "CLOSING";
			case STATE_CLOSED:
				return "CLOSED";
			default:
				return "UNKNOWN";
		}
	}

	@Override
	public Flux<ParsedMessage> receiveMessages() {
		return inboundSink.asFlux();
	}

	@Override
	public void close() {
		if (state.get() == STATE_CLOSED) {
			return;
		}
		isClosing = true;
		state.set(STATE_CLOSING);
		inboundSink.tryEmitComplete();
		HttpURLConnection conn = connection;
		if (conn != null) {
			conn.disconnect();
		}
		sseReader.shutdownNow();
		state.set(STATE_CLOSED);
		logger.debug("HttpTransport closed");
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(this::close).subscribeOn(Schedulers.boundedElastic()).then();
	}

	// ============================================================
	// 不支持的操作（one-shot 请求模型）
	// ============================================================

	@Override
	public void sendUserMessage(String content, String sid) throws SolonCodeSDKException {
		throw oneShotUnsupported();
	}

	@Override
	public void sendMessage(String message) throws SolonCodeSDKException {
		throw oneShotUnsupported();
	}

	@Override
	public void sendResponse(ControlResponse response) throws SolonCodeSDKException {
		throw oneShotUnsupported();
	}

	private static TransportException oneShotUnsupported() {
		return new TransportException("/web/run is one-shot: 每轮提问是一次新的 HTTP 请求"
				+ "（客户端层用 session_id/resume 串接多轮），不存在回写通道");
	}

	// ============================================================
	// HTTP status → exception
	// ============================================================

	/**
	 * HTTP 状态码 → 异常。对齐 run-headless-mode-http.md 的退出码映射：
	 * 401/403/404/409/4xx=请求故障；5xx=服务端错误。
	 */
	private static TransportException httpStatusException(int status, String errorBody) {
		String detail = errorBody != null && !errorBody.isEmpty() && errorBody.length() < 500 ? errorBody
				: ("HTTP " + status);
		switch (status) {
			case 401:
				return TransportException.withExitCode("Unauthorized: missing or invalid bearer token (" + detail + ")",
						status);
			case 403:
				return TransportException.withExitCode("Forbidden by server policy (" + detail + ")", status);
			case 404:
				return TransportException.withExitCode("Workspace not found (" + detail + ")", status);
			case 409:
				return TransportException.withExitCode("Session already has an active run (" + detail + ")", status);
			default:
				return TransportException.withExitCode("/web/run request failed with HTTP " + status + ": " + detail,
						status);
		}
	}

	private static String readStream(InputStream in) {
		if (in == null) {
			return null;
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		}
		catch (IOException e) {
			return null;
		}
	}

	Iterator<ParsedMessage> messageIteratorForTest() {
		return inboundSink.asFlux().toIterable().iterator();
	}

}
