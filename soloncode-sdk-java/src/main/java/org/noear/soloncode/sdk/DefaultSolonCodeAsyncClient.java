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

import org.noear.soloncode.sdk.util.SdkJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.hooks.HookCallback;
import org.noear.soloncode.sdk.hooks.HookRegistry;
import org.noear.soloncode.sdk.mcp.McpMessageHandler;
import org.noear.soloncode.sdk.mcp.McpServerConfig;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.transport.Transport;
import org.noear.soloncode.sdk.transport.TransportSpec;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.util.SdkCollections;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.types.control.ControlRequest;
import org.noear.soloncode.sdk.types.control.ControlResponse;
import org.noear.soloncode.sdk.types.control.HookEvent;
import org.noear.soloncode.sdk.types.control.HookInput;
import org.noear.soloncode.sdk.types.control.HookOutput;
import org.noear.soloncode.sdk.util.SdkCollections;
import org.noear.soloncode.sdk.permission.PermissionResult;
import org.noear.soloncode.sdk.permission.ToolPermissionCallback;
import org.noear.soloncode.sdk.permission.ToolPermissionContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Default implementation of {@link SolonCodeAsyncClient} providing reactive multi-turn
 * conversation support.
 *
 * <p>
 * This implementation maintains a persistent connection to the SolonCode CLI, allowing
 * multi-turn conversations where context is preserved across queries. All operations
 * return reactive types ({@link Mono} and {@link Flux}) for non-blocking execution.
 * </p>
 *
 * <p>
 * Thread-safety: This class is thread-safe. The underlying reactive streams handle
 * concurrency automatically with proper backpressure support.
 * </p>
 *
 * @see SolonCodeAsyncClient
 * @see SolonCodeClient
 * @see Transport
 */
public class DefaultSolonCodeAsyncClient implements SolonCodeAsyncClient {

	private static final Logger logger = LoggerFactory.getLogger(DefaultSolonCodeAsyncClient.class);

	private static final String DEFAULT_SESSION_ID = "default";

	private final Path workingDirectory;

	private final CLIOptions options;

	private final Duration timeout;

	private final TransportSpec transportSpec;

	private final HookRegistry hookRegistry;


	// MCP message handler for in-process SDK servers
	private final McpMessageHandler mcpMessageHandler;

	// Session state
	private final AtomicBoolean connected = new AtomicBoolean(false);

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final AtomicReference<Map<String, Object>> serverInfo = new AtomicReference<>(Collections.emptyMap());

	private final AtomicReference<String> currentSessionId = new AtomicReference<>(DEFAULT_SESSION_ID);

	/** SDK 侧固定的会话 ID：首轮 --session-id，后续轮 --resume。 */
	private final String sdkSessionId;

	/** 是否已经跑过至少一轮。 */
	private final AtomicBoolean turnStarted = new AtomicBoolean(false);

	// Runtime state tracking
	private final AtomicReference<String> currentModel = new AtomicReference<>();

	private final AtomicReference<String> currentPermissionMode = new AtomicReference<>();

	// Tool permission callback
	private volatile ToolPermissionCallback toolPermissionCallback;

	// Transport (set once during connect, read afterward)
	private final AtomicReference<Transport> transportRef = new AtomicReference<>();

	/**
	 * Per-turn unicast sink for streaming messages to the current receiveResponse()
	 * subscriber.
	 *
	 * <p>
	 * <b>Design Decision:</b> We use a per-turn unicast sink instead of a shared
	 * multicast sink to solve the multi-turn conversation problem. With a shared
	 * multicast sink, when {@code takeUntil(ResultMessage)} cancels after the first turn,
	 * the sink enters a corrupted state and subsequent subscriptions complete
	 * immediately.
	 * </p>
	 *
	 * <p>
	 * <b>Pattern:</b> Each {@link #receiveResponse()} call creates a fresh unicast sink
	 * via {@link Sinks.Many#unicast()}. The {@link #handleMessage} callback routes
	 * messages to whatever sink is currently active, and naturally completes the sink
	 * when a {@link ResultMessage} arrives (no {@code takeUntil} operator needed).
	 * </p>
	 *
	 * <p>
	 * AtomicReference enables thread-safe sink swapping between turns while ensuring only
	 * one turn is active at a time.
	 * </p>
	 *
	 * @see #receiveResponse()
	 * @see #handleMessage(ParsedMessage)
	 */
	private final AtomicReference<Sinks.Many<Message>> currentTurnSink = new AtomicReference<>();

	/**
	 * Sink for raw parsed messages (including control messages). Used by
	 * {@link #receiveMessages()} for low-level access.
	 */
	private volatile Sinks.Many<ParsedMessage> rawMessageSink;

	// Control request handling (MCP SDK pattern using MonoSink for correlation)
	private final AtomicInteger requestCounter = new AtomicInteger(0);

	private final String sessionPrefix = UUID.randomUUID().toString().substring(0, 8);

	private final ConcurrentHashMap<String, MonoSink<Map<String, Object>>> pendingResponses = new ConcurrentHashMap<>();

	// Cross-turn message handlers (thread-safe for concurrent registration)
	private final List<Consumer<Message>> messageHandlers = new CopyOnWriteArrayList<>();

	private final List<Consumer<ResultMessage>> resultHandlers = new CopyOnWriteArrayList<>();

	/**
	 * Creates a new DefaultSolonCodeAsyncClient with the specified configuration.
	 * @param workingDirectory the working directory for SolonCode CLI
	 * @param options CLI options
	 * @param timeout default operation timeout
	 * @param transportSpec 通讯通道声明（null 表示默认的本机 stdio 通道）
	 * @param hookRegistry optional hook registry
	 */
	public DefaultSolonCodeAsyncClient(Path workingDirectory, CLIOptions options, Duration timeout,
			TransportSpec transportSpec, HookRegistry hookRegistry) {
		this.workingDirectory = workingDirectory;
		this.options = options != null ? options : CLIOptions.builder().build();
		this.timeout = timeout != null ? timeout : Duration.ofMinutes(10);
		this.transportSpec = transportSpec != null ? transportSpec : TransportSpec.stdio();
		this.hookRegistry = hookRegistry != null ? hookRegistry : new HookRegistry();
		this.mcpMessageHandler = new McpMessageHandler();

		// 会话 ID：优先用调用方指定的 --session-id，否则自生成（用于多轮 --resume 串接）
		String explicitSessionId = this.options.getSessionId();
		this.sdkSessionId = (explicitSessionId != null && !explicitSessionId.trim().isEmpty()) ? explicitSessionId
				: "sdk-" + UUID.randomUUID().toString().substring(0, 8);

		// Initialize runtime state from options
		if (this.options.model() != null) {
			this.currentModel.set(this.options.model());
		}
		if (this.options.permissionMode() != null) {
			this.currentPermissionMode.set(this.options.permissionMode().getValue());
		}

		// Register SDK MCP servers for mcp_message handling
		registerMcpServers();
	}

	private void registerMcpServers() {
		Map<String, McpServerConfig> servers = this.options.mcpServers();
		if (servers == null || servers.isEmpty()) {
			return;
		}

		for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
			McpServerConfig.McpSdkServerConfig sdkConfig;
			if (entry.getValue() instanceof McpServerConfig.McpSdkServerConfig) {
				sdkConfig = (McpServerConfig.McpSdkServerConfig) entry.getValue();
				if (sdkConfig.instance() != null) {
					mcpMessageHandler.registerServer(entry.getKey(), sdkConfig.instance());
					logger.info("Registered SDK MCP server: {}", entry.getKey());
				}
				else {
					logger.warn("SDK MCP server {} has null instance", entry.getKey());
				}
			}
		}
	}

	@Override
	public Mono<Void> connect() {
		return doConnect(null);
	}

	@Override
	public TurnSpec connect(String initialPrompt) {
		return new DefaultTurnSpec(() -> doConnect(initialPrompt));
	}

	/**
	 * Internal connect implementation that returns Mono<Void>.
	 */
	private Mono<Void> doConnect(String initialPrompt) {
		return Mono.<Void>create(sink -> {
			if (closed.get()) {
				sink.error(new TransportException("Client has been closed"));
				return;
			}
			if (connected.get()) {
				sink.error(new TransportException("Client is already connected"));
				return;
			}

			try {
				// Create raw message sink for receiveMessages() (low-level access)
				rawMessageSink = Sinks.many().multicast().onBackpressureBuffer();

				// soloncode run 一次性语义：无提示词时不拉起进程，延迟到首次 query()。
				connected.set(true);

				if (initialPrompt != null) {
					startTurn(initialPrompt);
					// Length only; see DefaultSolonCodeSyncClient for the rationale.
					logger.info("Client connected with prompt ({} chars)", initialPrompt.length());
				}
				else {
					logger.info("Client connected without an initial prompt (CLI 进程将在首次 query() 时启动)");
				}

				sink.success();
			}
			catch (Exception e) {
				cleanup();
				sink.error(new TransportException("Failed to connect client", e));
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private void sendInitialize() throws SolonCodeSDKException {
		Map<String, List<ControlRequest.HookMatcherConfig>> hookConfig = hookRegistry.buildHookConfig();

		if (hookConfig.isEmpty()) {
			logger.debug("No hooks to initialize");
			return;
		}

		Map<String, Object> request = new LinkedHashMap<>();
		request.put("subtype", "initialize");
		request.put("hooks", hookConfig);

		logger.debug("Sending initialize with {} hook event types", hookConfig.size());
		sendControlRequest(request);
		logger.info("Hook configuration sent to CLI: {} event types", hookConfig.size());
	}

	@Override
	public TurnSpec query(String prompt) {
		return new DefaultTurnSpec(() -> doQuery(prompt));
	}

	/**
	 * Internal query implementation that returns Mono<Void>.
	 */
	private Mono<Void> doQuery(String prompt) {
		return Mono.<Void>create(sink -> {
			if (!connected.get() || closed.get()) {
				sink.error(new IllegalStateException("Client is not connected"));
				return;
			}

			try {
				// 一次性执行模型：每轮提问重开一个 soloncode run 进程，
				// 上下文靠 --session-id/--resume 串接，而不是向长连接 stdin 写 JSON 信封。
				startTurn(prompt);

				logger.debug("Sent query in session {}: {}", currentSessionId.get(),
						prompt.substring(0, Math.min(50, prompt.length())));

				sink.success();
			}
			catch (Exception e) {
				sink.error(new TransportException("Failed to send query", e));
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	/** 启动一轮：常驻 stdio 复用进程，其它通道保留一次性执行。 */
	private synchronized void startTurn(String prompt) throws SolonCodeSDKException {
		if (prompt == null || prompt.isEmpty()) {
			throw new TransportException("prompt 不能为空：soloncode run 无提示词时以退出码 3 终止");
		}

		if (transportSpec.isPersistent()) {
			Transport current = transportRef.get();
			if (current == null) {
				turnStarted.set(true);
				current = transportSpec.create(workingDirectory, timeout);
				current.setTurnSession(sdkSessionId, null);
				transportRef.set(current);
				current.startSession(prompt, options, this::handleMessage, this::handleControlRequest,
						this::handleControlResponse);
			}
			else {
				current.sendUserMessage(prompt, sdkSessionId);
			}
		}
		else {
			Transport previous = transportRef.getAndSet(null);
			if (previous != null) {
				previous.close();
			}

			boolean firstTurn = !turnStarted.getAndSet(true);
			Transport transport = transportSpec.create(workingDirectory, timeout);
			transport.setTurnSession(sdkSessionId, firstTurn ? null : sdkSessionId);
			transportRef.set(transport);
			transport.startSession(prompt, options, this::handleMessage, this::handleControlRequest,
					this::handleControlResponse);
		}
		currentSessionId.set(sdkSessionId);
	}

	@Override
	public Flux<ParsedMessage> receiveMessages() {
		// Use defer to delay the connected check until subscription time
		return Flux.defer(() -> {
			if (!connected.get() || closed.get()) {
				return Flux.error(new IllegalStateException("Client is not connected"));
			}
			// Subscribe to raw message sink for low-level access
			return rawMessageSink.asFlux();
		});
	}

	/**
	 * Receives response messages for the current turn as a reactive stream.
	 *
	 * <p>
	 * <b>Per-Turn Unicast Sink Pattern:</b> Each call creates a fresh unicast sink that
	 * receives messages until a {@link ResultMessage} arrives. This design solves the
	 * multi-turn problem where shared multicast sinks become corrupted after
	 * {@code takeUntil} cancellation.
	 * </p>
	 *
	 * <p>
	 * <b>How it works:</b>
	 * </p>
	 * <ol>
	 * <li>{@code Flux.defer()} delays sink creation until subscription</li>
	 * <li>A fresh {@link Sinks.Many#unicast()} sink is created for this turn</li>
	 * <li>The sink is atomically swapped into {@link #currentTurnSink}</li>
	 * <li>{@link #handleMessage} routes messages to the active sink</li>
	 * <li>When {@link ResultMessage} arrives, {@code handleMessage} completes the
	 * sink</li>
	 * <li>{@code doFinally} clears the reference to allow the next turn</li>
	 * </ol>
	 *
	 * <p>
	 * <b>Why no takeUntil:</b> The {@code takeUntil} operator cancels upstream on
	 * predicate match, which corrupts shared sinks. Instead, we complete the sink
	 * directly in {@code handleMessage} when we see {@code ResultMessage}.
	 * </p>
	 * @return Flux of messages that completes after ResultMessage
	 */
	@Override
	public Flux<Message> receiveResponse() {
		return Flux.defer(() -> {
			if (!connected.get() || closed.get()) {
				return Flux.error(new IllegalStateException("Client is not connected"));
			}
			Sinks.Many<Message> turnSink = installTurnSink();
			return turnFlux(turnSink);
		});
	}

	private Sinks.Many<Message> installTurnSink() {
		Sinks.Many<Message> turnSink = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<Message> previous = currentTurnSink.getAndSet(turnSink);
		if (previous != null) {
			previous.tryEmitComplete();
		}
		return turnSink;
	}

	private Flux<Message> turnFlux(Sinks.Many<Message> turnSink) {
		return turnSink.asFlux()
				.doFinally(signal -> currentTurnSink.compareAndSet(turnSink, null));
	}

	/** 在发送前安装轮次 sink，消除常驻进程快速响应导致的消息丢失窗口。 */
	private Flux<Message> sendAndReceive(java.util.function.Supplier<Mono<Void>> sendAction) {
		return Flux.defer(() -> {
			// connect(prompt) 的 sendAction 本身负责建立连接，因此这里不能预先要求 connected。
			Sinks.Many<Message> turnSink = installTurnSink();
			return sendAction.get()
					.thenMany(turnFlux(turnSink))
					.doOnError(e -> {
						if (currentTurnSink.compareAndSet(turnSink, null)) {
							turnSink.tryEmitError(e);
						}
					});
		});
	}

	@Override
	public Mono<Void> interrupt() {
		return Mono.<Void>create(sink -> {
			if (!connected.get() || closed.get()) {
				sink.error(new IllegalStateException("Client is not connected"));
				return;
			}
			try {
				// 常驻 stdio 发送控制帧并保留进程；其它 transport 保持原有中断语义。
				Transport transport = transportRef.get();
				if (transport != null) {
					transport.interrupt();
				}
				else {
					sendControlRequest(SdkCollections.map("subtype", "interrupt"));
				}
				sink.success();
			}
			catch (Exception e) {
				sink.error(new TransportException("Failed to send interrupt", e));
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> setPermissionMode(String mode) {
		return Mono.<Void>create(sink -> {
			if (!connected.get() || closed.get()) {
				sink.error(new IllegalStateException("Client is not connected"));
				return;
			}
			try {
				sendControlRequest(SdkCollections.map("subtype", "set_permission_mode", "mode", mode));
				currentPermissionMode.set(mode);
				sink.success();
			}
			catch (Exception e) {
				sink.error(new TransportException("Failed to set permission mode", e));
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> setModel(String model) {
		return Mono.<Void>create(sink -> {
			if (!connected.get() || closed.get()) {
				sink.error(new IllegalStateException("Client is not connected"));
				return;
			}
			try {
				Map<String, Object> request = new LinkedHashMap<>();
				request.put("subtype", "set_model");
				request.put("model", model);
				sendControlRequest(request);
				currentModel.set(model);
				sink.success();
			}
			catch (Exception e) {
				sink.error(new TransportException("Failed to set model", e));
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Optional<Map<String, Object>> getServerInfo() {
		Map<String, Object> info = serverInfo.get();
		return info.isEmpty() ? Optional.empty() : Optional.of(info);
	}

	@Override
	public void setToolPermissionCallback(ToolPermissionCallback callback) {
		this.toolPermissionCallback = callback;
	}

	@Override
	public ToolPermissionCallback getToolPermissionCallback() {
		return toolPermissionCallback;
	}

	@Override
	public boolean isConnected() {
		Transport transport = transportRef.get();
		return connected.get() && !closed.get() && transport != null && transport.isRunning();
	}

	@Override
	public Mono<Void> close() {
		return Mono.<Void>fromRunnable(() -> {
			if (closed.compareAndSet(false, true)) {
				connected.set(false);
				cleanup();
				logger.info("Client closed");
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public SolonCodeAsyncClient onMessage(Consumer<Message> handler) {
		if (handler != null) {
			messageHandlers.add(handler);
		}
		return this;
	}

	@Override
	public SolonCodeAsyncClient onResult(Consumer<ResultMessage> handler) {
		if (handler != null) {
			resultHandlers.add(handler);
		}
		return this;
	}

	/**
	 * Registers a hook callback for a specific event and tool pattern.
	 * @param event the hook event type
	 * @param toolPattern regex pattern for tool names, or null for all tools
	 * @param callback the callback to execute
	 * @return this client for chaining
	 */
	public DefaultSolonCodeAsyncClient registerHook(HookEvent event, String toolPattern, HookCallback callback) {
		hookRegistry.register(event, toolPattern, callback);
		return this;
	}

	/**
	 * Gets the current session ID.
	 * @return the current session ID
	 */
	public String getCurrentSessionId() {
		return currentSessionId.get();
	}

	/**
	 * Gets the current model.
	 * @return the current model
	 */
	public String getCurrentModel() {
		return currentModel.get();
	}

	/**
	 * Gets the current permission mode.
	 * @return the current permission mode
	 */
	public String getCurrentPermissionMode() {
		return currentPermissionMode.get();
	}

	// ========================================================================
	// Internal Methods
	// ========================================================================

	/**
	 * Routes incoming messages to handlers and sinks.
	 *
	 * <p>
	 * <b>Message Routing Order:</b>
	 * </p>
	 * <ol>
	 * <li>All messages go to {@link #rawMessageSink} for low-level subscribers</li>
	 * <li>Cross-turn handlers are notified (session-scoped concerns)</li>
	 * <li>Regular messages go to {@link #currentTurnSink} for turn-scoped
	 * subscribers</li>
	 * <li>{@link ResultMessage} triggers natural sink completion (no takeUntil
	 * needed)</li>
	 * </ol>
	 *
	 * <p>
	 * <b>Why ResultMessage completes the sink:</b> In the per-turn unicast pattern, we
	 * complete the sink directly when ResultMessage arrives rather than using
	 * {@code takeUntil}. This avoids the upstream cancellation that corrupts shared
	 * sinks.
	 * </p>
	 * @param message the parsed message from the CLI
	 */
	private void handleMessage(ParsedMessage message) {
		// Route to raw sink for low-level access
		if (rawMessageSink != null) {
			rawMessageSink.tryEmitNext(message);
		}

		// Route regular messages to handlers and turn sink
		if (message.isRegularMessage()) {
			Message msg = message.asMessage();

			// Notify cross-turn handlers (session-scoped) before turn sink
			for (Consumer<Message> handler : messageHandlers) {
				try {
					handler.accept(msg);
				}
				catch (Exception e) {
					logger.warn("Message handler threw exception", e);
				}
			}

			// Notify result handlers specifically for ResultMessage
			if (msg instanceof ResultMessage) {
				ResultMessage resultMsg = (ResultMessage) msg;
				for (Consumer<ResultMessage> handler : resultHandlers) {
					try {
						handler.accept(resultMsg);
					}
					catch (Exception e) {
						logger.warn("Result handler threw exception", e);
					}
				}
			}

			// Emit to turn sink (per-turn)
			Sinks.Many<Message> sink = currentTurnSink.get();
			if (sink != null) {
				Sinks.EmitResult result = sink.tryEmitNext(msg);
				if (result.isSuccess()) {
					logger.debug("handleMessage: emitted {} to turn sink", msg.getClass().getSimpleName());
				}
				else {
					logger.warn("handleMessage: failed to emit {} - result={}", msg.getClass().getSimpleName(), result);
				}

				// Complete the sink when ResultMessage arrives (natural completion)
				if (msg instanceof ResultMessage) {
					logger.debug("handleMessage: ResultMessage received, completing turn sink");
					sink.tryEmitComplete();
				}
			}
			else {
				logger.debug("handleMessage: no turn sink active, skipping {}", msg.getClass().getSimpleName());
			}
		}
	}

	private ControlResponse handleControlRequest(ControlRequest request) {
		String requestId = request.requestId();
		ControlRequest.ControlRequestPayload payload = request.request();

		logger.debug("Handling control request: type={}, requestId={}", payload != null ? payload.subtype() : "null",
				requestId);

		try {
			if (payload instanceof ControlRequest.HookCallbackRequest) {
				ControlRequest.HookCallbackRequest hookCallback = (ControlRequest.HookCallbackRequest) payload;
				return handleHookCallback(requestId, hookCallback);
			}
			else if (payload instanceof ControlRequest.CanUseToolRequest) {
				ControlRequest.CanUseToolRequest canUseTool = (ControlRequest.CanUseToolRequest) payload;
				return handleCanUseTool(requestId, canUseTool);
			}
			else if (payload instanceof ControlRequest.InitializeRequest) {
				ControlRequest.InitializeRequest init = (ControlRequest.InitializeRequest) payload;
				serverInfo.set(SdkCollections.map("hooks", init.hooks() != null ? init.hooks() : Collections.<String, Object>emptyMap()));
				return ControlResponse.success(requestId, SdkCollections.map("status", "ok"));
			}
			else if (payload instanceof ControlRequest.McpMessageRequest) {
				ControlRequest.McpMessageRequest mcpMessage = (ControlRequest.McpMessageRequest) payload;
				return handleMcpMessage(requestId, mcpMessage);
			}
			else {
				return ControlResponse.success(requestId, SdkCollections.map());
			}
		}
		catch (Exception e) {
			logger.error("Error handling control request", e);
			return ControlResponse.error(requestId, e.getMessage());
		}
	}

	private ControlResponse handleHookCallback(String requestId, ControlRequest.HookCallbackRequest hookCallback) {
		try {
			String callbackId = hookCallback.callbackId();
			Map<String, Object> inputMap = hookCallback.input();

			// 多态：按 hook_event_name 分派（见 HookInput.HookInputCreator）
			HookInput input = SdkJson.convert(inputMap, HookInput.class);
			HookOutput output = hookRegistry.executeHook(callbackId, input);

			Map<String, Object> responsePayload = new LinkedHashMap<>();
			responsePayload.put("continue", output.continueExecution());
			if (output.decision() != null) {
				responsePayload.put("decision", output.decision());
			}
			if (output.reason() != null) {
				responsePayload.put("reason", output.reason());
			}
			if (output.hookSpecificOutput() != null) {
				HookOutput.HookSpecificOutput specific = output.hookSpecificOutput();
				if (specific.permissionDecision() != null) {
					responsePayload.put("permission_decision", specific.permissionDecision());
				}
				if (specific.permissionDecisionReason() != null) {
					responsePayload.put("permission_decision_reason", specific.permissionDecisionReason());
				}
			}

			return ControlResponse.success(requestId, responsePayload);
		}
		catch (Exception e) {
			logger.error("Hook callback failed", e);
			return ControlResponse.error(requestId, "Hook execution failed: " + e.getMessage());
		}
	}

	private ControlResponse handleCanUseTool(String requestId, ControlRequest.CanUseToolRequest canUseTool) {
		if (toolPermissionCallback == null) {
			// No callback registered, allow by default
			return ControlResponse.success(requestId, SdkCollections.map("behavior", "allow"));
		}

		try {
			String toolName = canUseTool.toolName();
			Map<String, Object> input = canUseTool.input();
			ToolPermissionContext context = new ToolPermissionContext(canUseTool.permissionSuggestions(),
					canUseTool.blockedPath(), requestId);

			PermissionResult result = toolPermissionCallback.checkPermission(toolName, input, context);

			Map<String, Object> response = new LinkedHashMap<>();
			if (result.isAllowed()) {
				response.put("behavior", "allow");
				if (result instanceof PermissionResult.Allow) {
					PermissionResult.Allow allow = (PermissionResult.Allow) result;
					if (allow.hasUpdatedInput()) {
						response.put("updatedInput", allow.updatedInput());
					}
				}
			}
			else {
				response.put("behavior", "deny");
				if (result instanceof PermissionResult.Deny) {
					PermissionResult.Deny deny = (PermissionResult.Deny) result;
					if (deny.hasMessage()) {
						response.put("message", deny.message());
					}
				}
			}

			return ControlResponse.success(requestId, response);
		}
		catch (Exception e) {
			logger.error("Permission callback failed", e);
			return ControlResponse.error(requestId, "Permission check failed: " + e.getMessage());
		}
	}

	private ControlResponse handleMcpMessage(String requestId, ControlRequest.McpMessageRequest mcpMessage) {
		try {
			String serverName = mcpMessage.serverName();
			Map<String, Object> message = mcpMessage.message();

			Map<String, Object> response = mcpMessageHandler.handleMcpMessage(serverName, message);
			return ControlResponse.success(requestId, response);
		}
		catch (Exception e) {
			logger.error("MCP message handling failed", e);
			return ControlResponse.error(requestId, "MCP message handling failed: " + e.getMessage());
		}
	}

	private void handleControlResponse(ControlResponse response) {
		if (response.response() == null) {
			logger.warn("Received control response with null payload");
			return;
		}

		String requestId = response.response().requestId();
		if (requestId == null) {
			logger.warn("Received control response without request_id");
			return;
		}

		logger.debug("Handling control response: requestId={}, subtype={}", requestId, response.response().subtype());

		MonoSink<Map<String, Object>> sink = pendingResponses.remove(requestId);
		if (sink == null) {
			logger.warn("Unexpected response for unknown request id {}", requestId);
			return;
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("subtype", response.response().subtype());

		if (response.response() instanceof ControlResponse.SuccessPayload) {
			ControlResponse.SuccessPayload success = (ControlResponse.SuccessPayload) response.response();
			if (success.response() instanceof Map) {
				Map<?, ?> responseMap = (Map<?, ?>) success.response();
				@SuppressWarnings("unchecked")
				Map<String, Object> typedMap = (Map<String, Object>) responseMap;
				payload.putAll(typedMap);
			}
			sink.success(payload);
			logger.debug("Control response delivered for requestId={}", requestId);
		}
		else if (response.response() instanceof ControlResponse.ErrorPayload) {
		ControlResponse.ErrorPayload error = (ControlResponse.ErrorPayload) response.response();
			sink.error(new SolonCodeSDKException("Control request failed: " + error.error()));
			logger.debug("Control response error delivered for requestId={}", requestId);
		}
		else {
			sink.success(payload);
		}
	}

	private void sendControlRequest(Map<String, Object> request) throws SolonCodeSDKException {
		try {
			String requestId = sessionPrefix + "_" + requestCounter.incrementAndGet();

			Map<String, Object> fullRequest = new LinkedHashMap<>();
			fullRequest.put("type", "control");
			fullRequest.put("request_id", requestId);
			fullRequest.putAll(request);

			String json = SdkJson.toJsonWithNulls(fullRequest);
			transportRef.get().sendMessage(json);

			logger.debug("Sent control request: id={}, subtype={}", requestId, request.get("subtype"));
		}
		catch (Exception e) {
			throw new TransportException("Failed to send control request", e);
		}
	}

	private void cleanup() {
		Transport transport = transportRef.getAndSet(null);
		if (transport != null) {
			try {
				transport.close();
			}
			catch (Exception e) {
				logger.warn("Error closing transport", e);
			}
		}

		// Complete and clear the current turn sink
		Sinks.Many<Message> turnSink = currentTurnSink.getAndSet(null);
		if (turnSink != null) {
			turnSink.tryEmitComplete();
		}

		// Complete and clear the raw message sink
		if (rawMessageSink != null) {
			rawMessageSink.tryEmitComplete();
			rawMessageSink = null;
		}

		pendingResponses.clear();
	}

	// ========================================================================
	// DefaultTurnSpec - WebClient-inspired response handling
	// ========================================================================

	/**
	 * Default implementation of {@link TurnSpec} providing lazy response handling.
	 *
	 * <p>
	 * Inspired by Spring WebClient's ResponseSpec pattern. All operations are lazy - the
	 * actual send (connect/query) is triggered when you subscribe to a terminal operation
	 * ({@link #text()}, {@link #textStream()}, or {@link #messages()}).
	 * </p>
	 */
	private class DefaultTurnSpec implements TurnSpec {

		private final java.util.function.Supplier<Mono<Void>> sendAction;

		/**
		 * Creates a TurnSpec with the given send action.
		 * @param sendAction supplier that returns the Mono<Void> to execute the send
		 */
		DefaultTurnSpec(java.util.function.Supplier<Mono<Void>> sendAction) {
			this.sendAction = sendAction;
		}

		@Override
		public Mono<String> text() {
			return sendAndReceive(sendAction)
				.ofType(AssistantMessage.class)
				.map(AssistantMessage::text)
				.filter(text -> !text.isEmpty())
				.reduce(String::concat)
				.defaultIfEmpty("");
		}

		@Override
		public Flux<String> textStream() {
			return sendAndReceive(sendAction)
				.ofType(AssistantMessage.class)
				.map(AssistantMessage::text)
				.filter(text -> !text.isEmpty());
		}

		@Override
		public Flux<Message> messages() {
			return sendAndReceive(sendAction);
		}

	}

}
