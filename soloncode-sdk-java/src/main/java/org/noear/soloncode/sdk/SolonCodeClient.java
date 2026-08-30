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

import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.hooks.HookRegistry;
import org.noear.soloncode.sdk.mcp.McpServerConfig;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.transport.HttpOptions;
import org.noear.soloncode.sdk.transport.TransportSpec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory class for creating SolonCode SDK clients.
 *
 * <p>
 * This class serves as the main entry point for creating clients to interact with the
 * SolonCode CLI. It follows the MCP Java SDK pattern of providing factory methods for both
 * synchronous and asynchronous clients.
 * </p>
 *
 * <h2>Option 1: Fluent Builder API</h2>
 * <p>
 * Use {@link #sync()} to configure all options via the fluent builder:
 * </p>
 * <pre>{@code
 * try (SolonCodeSyncClient client = SolonCodeClient.sync()
 *         .workingDirectory(Path.of("."))
 *         .model("sonnet")
 *         .systemPrompt("Be concise")
 *         .timeout(Duration.ofMinutes(5))
 *         .build()) {
 *
 *     client.connect("Hello!");
 *     for (var msg : client.receiveResponse()) {
 *         // Process response
 *     }
 * }
 * }</pre>
 *
 * <h2>Option 2: Pre-built CLIOptions</h2>
 * <p>
 * Use {@link #sync(CLIOptions)} when you have pre-configured CLI options:
 * </p>
 * <pre>{@code
 * CLIOptions options = CLIOptions.builder()
 *     .model("sonnet")
 *     .systemPrompt("Be concise")
 *     .build();
 *
 * try (SolonCodeSyncClient client = SolonCodeClient.sync(options)
 *         .workingDirectory(Path.of("."))
 *         .timeout(Duration.ofMinutes(5))
 *         .build()) {
 *     // Only session-level config available, CLI options already set
 * }
 * }</pre>
 *
 * <h2>With Hooks</h2> <pre>{@code
 * HookRegistry hooks = new HookRegistry();
 * hooks.registerPreToolUse("Bash", input -> {
 *     String cmd = input.getArgument("command", String.class).orElse("");
 *     if (cmd.contains("rm -rf")) {
 *         return HookOutput.block("Dangerous command blocked");
 *     }
 *     return HookOutput.allow();
 * });
 *
 * try (SolonCodeSyncClient client = SolonCodeClient.sync()
 *         .workingDirectory(Path.of("."))
 *         .hookRegistry(hooks)
 *         .build()) {
 *     // Hooks intercept tool usage
 * }
 * }</pre>
 *
 * @see SolonCodeSyncClient
 * @see SolonCodeAsyncClient
 */
public interface SolonCodeClient extends AutoCloseable {

	/**
	 * Start building a synchronous SolonCode client with fluent configuration.
	 *
	 * <p>
	 * Use this method when you want to configure CLI options (model, system prompt,
	 * tools, etc.) via the fluent builder API. For pre-built CLIOptions, use
	 * {@link #sync(CLIOptions)} instead.
	 * </p>
	 * @return A new builder instance for configuring the synchronous client
	 * @see #sync(CLIOptions)
	 */
	static SyncSpec sync() {
		return new SyncSpec();
	}

	/**
	 * Start building a synchronous SolonCode client with pre-configured CLI options.
	 *
	 * <p>
	 * Use this method when you have a pre-built {@link CLIOptions} object. The returned
	 * builder only exposes session-level configuration (working directory, timeout,
	 * hooks) since CLI options are already provided.
	 * </p>
	 * @param options the pre-configured CLI options
	 * @return A new builder instance for session-level configuration only
	 * @see #sync()
	 */
	static SyncSpecWithOptions sync(CLIOptions options) {
		return new SyncSpecWithOptions(options);
	}

	/** Creates the unified request-oriented client; call() or stream() selects the mode. */
	static Builder builder() {
		return new Builder();
	}

	/**
	 * Fluent builder for creating a {@link SolonCodeSyncClient} with full configuration
	 * control.
	 *
	 * <p>
	 * Use this builder when you want to configure all options inline using method
	 * chaining. This is the recommended approach for most use cases.
	 * </p>
	 *
	 * <h2>Configuration Categories</h2>
	 *
	 * <h3>Session Configuration</h3>
	 * <ul>
	 * <li>{@link #workingDirectory(Path)} - Directory where SolonCode CLI operates
	 * (required)</li>
	 * <li>{@link #timeout(Duration)} - Operation timeout (default: 10 minutes)</li>
	 * <li>{@link #stdio(String)} - 通讯通道：本机子进程（默认），可指定 CLI 可执行文件路径</li>
	 * <li>{@link #hookRegistry(HookRegistry)} - Hook registry for intercepting tool
	 * calls</li>
	 * </ul>
	 *
	 * <h3>Model Configuration</h3>
	 * <ul>
	 * <li>{@link #model(String)} - SolonCode model to use (e.g.,
	 * "sonnet")</li>
	 * <li>{@link #systemPrompt(String)} - System prompt for the conversation</li>
	 * <li>{@link #appendSystemPrompt(String)} - Text to append to the system prompt</li>
	 * <li>{@link #maxTokens(Integer)} - Maximum response tokens</li>
	 * <li>{@link #maxThinkingTokens(Integer)} - Maximum thinking tokens (extended
	 * thinking)</li>
	 * </ul>
	 *
	 * <h3>Tool Configuration</h3>
	 * <ul>
	 * <li>{@link #tools(List)} - Base set of tools to enable</li>
	 * <li>{@link #allowedTools(List)} - Explicitly allow specific tools (only these are
	 * available)</li>
	 * <li>{@link #disallowedTools(List)} - Block specific tools (all others remain
	 * available)</li>
	 * <li>{@link #permissionMode(PermissionMode)} - Tool permission mode</li>
	 * </ul>
	 *
	 * <h3>Limits and Budget</h3>
	 * <ul>
	 * <li>{@link #maxTurns(Integer)} - Maximum conversation turns</li>
	 * <li>{@link #maxBudgetUsd(Double)} - Maximum spend in USD</li>
	 * </ul>
	 *
	 * <h3>MCP Servers</h3>
	 * <ul>
	 * <li>{@link #mcpServer(String, McpServerConfig)} - Add a single MCP server</li>
	 * <li>{@link #mcpServers(Map)} - Set all MCP server configurations</li>
	 * </ul>
	 *
	 * <h2>Example</h2> <pre>{@code
	 * SolonCodeSyncClient client = SolonCodeClient.sync()
	 *     .workingDirectory(Path.of("."))
	 *     .model("sonnet")
	 *     .systemPrompt("You are a helpful assistant")
	 *     .maxTokens(4096)
	 *     .timeout(Duration.ofMinutes(5))
	 *     .build();
	 * }</pre>
	 *
	 * @see #sync()
	 * @see SyncSpecWithOptions
	 */
	class SyncSpec {

		private Path workingDirectory;

		private Duration timeout = Duration.ofMinutes(10);

		private TransportSpec transportSpec = TransportSpec.stdio();

		/** http 通道的 Bearer token（仅 http 通道使用） */
		private String authToken;

		/** http 通道的服务端工作区标识（仅 http 通道使用，替代 workingDirectory） */
		private String httpWorkspace;

		/** http 通道的网络层选项：代理与 SSL/TLS（仅 http 通道使用） */
		private HttpOptions httpOptions;

		private HookRegistry hookRegistry;

		// CLIOptions fields
		private String model;

		private String systemPrompt;

		private String appendSystemPrompt;

		private Integer maxTokens;

		private Integer maxThinkingTokens;

		private List<String> tools;

		private List<String> allowedTools = new ArrayList<>();

		private List<String> disallowedTools = new ArrayList<>();

		private PermissionMode permissionMode = PermissionMode.DEFAULT;

		private Integer maxTurns;

		// ===== soloncode 特有选项 =====

		/** 固定会话 ID（多轮 --resume 串接的锚点）。 */
		private String sessionId;

		/** bare 模式：跳过技能/子代理挂载、MCP 服务与记忆自动发现。 */
		private boolean bare;

		/** 主模型不可用时的回退模型。 */
		private String fallbackModel;

		private Double maxBudgetUsd;

		private Map<String, McpServerConfig> mcpServers = new HashMap<>();

		SyncSpec() {
		}

		/**
		 * Sets the working directory for SolonCode CLI execution. This is where SolonCode will
		 * operate and have access to files.
		 * @param workingDirectory the working directory path (required)
		 * @return this builder instance for method chaining
		 */
		public SyncSpec workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		/**
		 * Sets the duration to wait for operations before timing out.
		 * @param timeout the timeout duration (default: 10 minutes)
		 * @return this builder instance for method chaining
		 */
		public SyncSpec timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * 使用本机 stdio 通道：拉起常驻 {@code soloncode stream} 子进程，CLI 可执行文件自动发现。
		 *
		 * <p>这是默认通道，显式调用仅用于表达意图。</p>
		 * @return this builder instance for method chaining
		 */
		public SyncSpec stdio() {
			this.transportSpec = TransportSpec.stdio();
			return this;
		}

		/**
		 * 使用本机 stdio 通道，并指定 soloncode 可执行文件路径。
		 * @param cliPath soloncode 可执行文件路径；传 null 表示自动发现
		 * @return this builder instance for method chaining
		 */
		public SyncSpec stdio(String cliPath) {
			this.transportSpec = TransportSpec.stdio(cliPath);
			return this;
		}

		/** 兼容模式：每轮启动一次 {@code soloncode run} 子进程。 */
		public SyncSpec stdioOneShot(String cliPath) {
			this.transportSpec = TransportSpec.stdioOneShot(cliPath);
			return this;
		}

		/**
		 * 使用 HTTP 通道：投递到服务端 {@code /web/run} 端点（SSE 接收同构事件流）。
		 *
		 * <p>HTTP 通道下工作目录在服务端：改用 {@link #workspace(String)} 指定工作区标识，
		 * {@link #workingDirectory(Path)} 会被拒绝；token 用 {@link #authToken(String)}。</p>
		 *
		 * @param url {@code /web/run} 完整 URL，如 {@code http://127.0.0.1:18080/web/run}
		 * @return this builder instance for method chaining
		 */
		public SyncSpec http(String url) {
			this.transportSpec = TransportSpec.http(url);
			return this;
		}

		/**
		 * HTTP 通道的 Bearer token（服务端 {@code ~/.soloncode/run.token}）。
		 * @param token Bearer token
		 * @return this builder instance for method chaining
		 */
		public SyncSpec authToken(String token) {
			this.authToken = token;
			return this;
		}

		/**
		 * HTTP 通道的服务端工作区标识（{@code /web/workspace/list} 返回的 name/id），
		 * 替代 stdio 通道的 workingDirectory。
		 * @param workspace 工作区标识
		 * @return this builder instance for method chaining
		 */
		public SyncSpec workspace(String workspace) {
			this.httpWorkspace = workspace;
			return this;
		}

		/**
		 * HTTP 通道的网络层选项：代理与 SSL/TLS（{@code HttpOptions.proxy(...).tlsTrustStore(...)}）。
		 * 仅 http 通道有效，stdio 通道设置会在 build 时报错。
		 * @param options 网络层选项；null 表示默认直连（无代理、JVM 默认 SSL）
		 * @return this builder instance for method chaining
		 * @see HttpOptions
		 */
		public SyncSpec httpOptions(HttpOptions options) {
			this.httpOptions = options;
			return this;
		}

		/**
		 * Sets the hook registry for intercepting tool execution.
		 * @param hookRegistry the hook registry
		 * @return this builder instance for method chaining
		 */
		public SyncSpec hookRegistry(HookRegistry hookRegistry) {
			this.hookRegistry = hookRegistry;
			return this;
		}

		/**
		 * Sets the SolonCode model to use.
		 * @param model the model ID (e.g., "sonnet")
		 * @return this builder instance for method chaining
		 */
		public SyncSpec model(String model) {
			this.model = model;
			return this;
		}

		/**
		 * Sets the system prompt.
		 * @param systemPrompt the system prompt text
		 * @return this builder instance for method chaining
		 */
		public SyncSpec systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		/**
		 * Sets text to append to the system prompt.
		 * @param appendSystemPrompt text to append
		 * @return this builder instance for method chaining
		 */
		public SyncSpec appendSystemPrompt(String appendSystemPrompt) {
			this.appendSystemPrompt = appendSystemPrompt;
			return this;
		}

		/**
		 * Sets the maximum tokens for responses.
		 * @param maxTokens maximum tokens
		 * @return this builder instance for method chaining
		 */
		public SyncSpec maxTokens(Integer maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		/**
		 * Sets the maximum thinking tokens for extended thinking.
		 * @param maxThinkingTokens maximum thinking tokens
		 * @return this builder instance for method chaining
		 */
		public SyncSpec maxThinkingTokens(Integer maxThinkingTokens) {
			this.maxThinkingTokens = maxThinkingTokens;
			return this;
		}

		/**
		 * Sets the base set of tools to enable.
		 * @param tools list of tool names
		 * @return this builder instance for method chaining
		 */
		public SyncSpec tools(List<String> tools) {
			this.tools = tools;
			return this;
		}

		/**
		 * Sets the allowed tools list.
		 * @param allowedTools list of allowed tool names
		 * @return this builder instance for method chaining
		 */
		public SyncSpec allowedTools(List<String> allowedTools) {
			this.allowedTools = new ArrayList<>(allowedTools);
			return this;
		}

		/**
		 * Sets the disallowed tools list.
		 * @param disallowedTools list of disallowed tool names
		 * @return this builder instance for method chaining
		 */
		public SyncSpec disallowedTools(List<String> disallowedTools) {
			this.disallowedTools = new ArrayList<>(disallowedTools);
			return this;
		}

		/**
		 * Sets the permission mode.
		 * @param permissionMode the permission mode
		 * @return this builder instance for method chaining
		 */
		public SyncSpec permissionMode(PermissionMode permissionMode) {
			this.permissionMode = permissionMode;
			return this;
		}

		/**
		 * Sets the maximum number of turns.
		 * @param maxTurns maximum turns
		 * @return this builder instance for method chaining
		 */
		public SyncSpec maxTurns(Integer maxTurns) {
			this.maxTurns = maxTurns;
			return this;
		}

		/**
		 * Sets the maximum budget in USD.
		 * @param maxBudgetUsd maximum budget
		 * @return this builder instance for method chaining
		 */
		public SyncSpec maxBudgetUsd(Double maxBudgetUsd) {
			this.maxBudgetUsd = maxBudgetUsd;
			return this;
		}

		/**
		 * 设置固定会话 ID（soloncode {@code --session-id}）。
		 *
		 * <p>不设时 SDK 自生成一个；多轮 {@code query()} 会基于它用 {@code --resume} 续接上下文。</p>
		 * @param sessionId 会话 ID
		 * @return this builder instance for method chaining
		 */
		public SyncSpec sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		/**
		 * 开启 bare 模式（soloncode {@code --bare}）：跳过技能/子代理挂载、MCP 服务与记忆自动发现。
		 *
		 * <p>CI 场景推荐开启：冷启动更快、行为更可复现。</p>
		 * @param bare 是否开启
		 * @return this builder instance for method chaining
		 */
		public SyncSpec bare(boolean bare) {
			this.bare = bare;
			return this;
		}

		/**
		 * 设置回退模型（soloncode {@code --fallback-model}）。
		 * @param fallbackModel 主模型不可用时使用的模型
		 * @return this builder instance for method chaining
		 */
		public SyncSpec fallbackModel(String fallbackModel) {
			this.fallbackModel = fallbackModel;
			return this;
		}

		/**
		 * Adds an MCP server configuration.
		 * @param name the server name
		 * @param config the server configuration
		 * @return this builder instance for method chaining
		 */
		public SyncSpec mcpServer(String name, McpServerConfig config) {
			this.mcpServers.put(name, config);
			return this;
		}

		/**
		 * Sets all MCP server configurations.
		 * @param mcpServers map of server names to configurations
		 * @return this builder instance for method chaining
		 */
		public SyncSpec mcpServers(Map<String, McpServerConfig> mcpServers) {
			this.mcpServers = new HashMap<>(mcpServers);
			return this;
		}

		/**
		 * prompt 风格入口：把当前 builder 配置（包括通道）绑定到一次请求，
		 * 用 {@code call()} / {@code stream()} 收束。
		 *
		 * <pre>{@code
		 * SolonCodeClient.sync()
		 *     .http(url).authToken(token).workspace(ws)
		 *     .prompt("分析这个模块")
		 *     .stream()
		 *     .subscribe(System.out::println);
		 * }</pre>
		 * @param prompt 提示语；不可为空
		 * @return 请求描述
		 */
		public SolonCodeRequestDesc prompt(String prompt) {
			// 通道与凭证由 builder 已经配好；这里只在每次执行时建一个新 client
			return new DefaultSolonCodeRequestDesc(prompt, options -> build());
		}

		/**
		 * Builds and returns the configured SolonCodeSyncClient.
		 * @return a new SolonCodeSyncClient instance
		 * @throws IllegalArgumentException if workingDirectory is not set
		 */
		public SolonCodeSyncClient build() {
			if (transportSpec.isHttp()) {
				// http 通道：workspace 标识替代 workingDirectory，互斥校验在 builder 层
				if (workingDirectory != null) {
					throw new IllegalArgumentException(
							"workingDirectory is not applicable to the http transport; use workspace(String) instead");
				}
				if (authToken != null || httpWorkspace != null) {
					transportSpec = transportSpec.withHttpCredentials(authToken, httpWorkspace);
				}
				if (httpOptions != null) {
					transportSpec = transportSpec.withHttpOptions(httpOptions);
				}
			}
			else {
				if (httpOptions != null) {
					throw new IllegalArgumentException("httpOptions is only applicable to the http transport");
				}
				if (workingDirectory == null) {
					throw new IllegalArgumentException("workingDirectory is required");
				}
			}

			// Build CLIOptions from individual settings
			CLIOptions options = CLIOptions.builder()
				.model(model)
				.systemPrompt(systemPrompt)
				.appendSystemPrompt(appendSystemPrompt)
				.maxTokens(maxTokens)
				.maxThinkingTokens(maxThinkingTokens)
				.tools(tools)
				.allowedTools(allowedTools)
				.disallowedTools(disallowedTools)
				.permissionMode(permissionMode)
				.maxTurns(maxTurns)
				.maxBudgetUsd(maxBudgetUsd)
				.mcpServers(mcpServers)
				.sessionId(sessionId)
				.bare(bare)
				.fallbackModel(fallbackModel)
				.build();

			return new DefaultSolonCodeSyncClient(workingDirectory, options, timeout, transportSpec, hookRegistry);
		}

	}

	/**
	 * Builder for creating a {@link SolonCodeSyncClient} with pre-configured CLI options.
	 *
	 * <p>
	 * Use this builder when you have a pre-built {@link CLIOptions} object and only need
	 * to configure session-level settings. This approach is useful when:
	 * </p>
	 * <ul>
	 * <li>CLI options are loaded from configuration files</li>
	 * <li>CLI options are shared across multiple client instances</li>
	 * <li>CLI options are constructed programmatically elsewhere</li>
	 * </ul>
	 *
	 * <h2>Available Configuration</h2>
	 *
	 * <p>
	 * Only session-level configuration is exposed (CLI options are already set):
	 * </p>
	 * <ul>
	 * <li>{@link #workingDirectory(Path)} - Directory where SolonCode CLI operates
	 * (required)</li>
	 * <li>{@link #timeout(Duration)} - Operation timeout (default: 10 minutes)</li>
	 * <li>{@link #stdio(String)} - 通讯通道：本机子进程（默认），可指定 CLI 可执行文件路径</li>
	 * <li>{@link #hookRegistry(HookRegistry)} - Hook registry for intercepting tool
	 * calls</li>
	 * </ul>
	 *
	 * <p>
	 * Model, tool, and budget configuration are <strong>not available</strong> on this
	 * builder since they are already defined in the {@link CLIOptions} passed to
	 * {@link #sync(CLIOptions)}.
	 * </p>
	 *
	 * <h2>Example</h2> <pre>{@code
	 * // Create CLI options (can be loaded from config, shared, etc.)
	 * CLIOptions options = CLIOptions.builder()
	 *     .model("sonnet")
	 *     .systemPrompt("You are a helpful assistant")
	 *     .maxTokens(4096)
	 *     .build();
	 *
	 * // Build client with pre-configured options
	 * SolonCodeSyncClient client = SolonCodeClient.sync(options)
	 *     .workingDirectory(Path.of("."))
	 *     .timeout(Duration.ofMinutes(5))
	 *     .build();
	 * }</pre>
	 *
	 * @see #sync(CLIOptions)
	 * @see SyncSpec
	 * @see CLIOptions
	 */
	class SyncSpecWithOptions {

		private final CLIOptions options;

		private Path workingDirectory;

		private Duration timeout = Duration.ofMinutes(10);

		private TransportSpec transportSpec = TransportSpec.stdio();

		/** http 通道的 Bearer token（仅 http 通道使用） */
		private String authToken;

		/** http 通道的服务端工作区标识（仅 http 通道使用，替代 workingDirectory） */
		private String httpWorkspace;

		/** http 通道的网络层选项：代理与 SSL/TLS（仅 http 通道使用） */
		private HttpOptions httpOptions;

		private HookRegistry hookRegistry;

		SyncSpecWithOptions(CLIOptions options) {
			this.options = options;
		}

		/**
		 * Sets the working directory for SolonCode CLI execution.
		 * @param workingDirectory the working directory path (required)
		 * @return this builder instance for method chaining
		 */
		public SyncSpecWithOptions workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		/**
		 * Sets the duration to wait for operations before timing out.
		 * @param timeout the timeout duration (default: 10 minutes)
		 * @return this builder instance for method chaining
		 */
		public SyncSpecWithOptions timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * 使用本机 stdio 通道：拉起常驻 {@code soloncode stream} 子进程，CLI 可执行文件自动发现。
		 * @return this builder instance for method chaining
		 */
		public SyncSpecWithOptions stdio() {
			this.transportSpec = TransportSpec.stdio();
			return this;
		}

		/**
		 * 使用本机 stdio 通道，并指定 soloncode 可执行文件路径。
		 * @param cliPath soloncode 可执行文件路径；传 null 表示自动发现
		 * @return this builder instance for method chaining
		 */
		public SyncSpecWithOptions stdio(String cliPath) {
			this.transportSpec = TransportSpec.stdio(cliPath);
			return this;
		}

		/** 兼容模式：每轮启动一次 {@code soloncode run} 子进程。 */
		public SyncSpecWithOptions stdioOneShot(String cliPath) {
			this.transportSpec = TransportSpec.stdioOneShot(cliPath);
			return this;
		}

		/** 使用 HTTP 通道：投递到服务端 {@code /web/run} 端点（详见 {@link SyncSpec#http(String)}）。 */
		public SyncSpecWithOptions http(String url) {
			this.transportSpec = TransportSpec.http(url);
			return this;
		}

		/** HTTP 通道的 Bearer token。 */
		public SyncSpecWithOptions authToken(String token) {
			this.authToken = token;
			return this;
		}

		/** HTTP 通道的服务端工作区标识，替代 workingDirectory。 */
		public SyncSpecWithOptions workspace(String workspace) {
			this.httpWorkspace = workspace;
			return this;
		}

		/** HTTP 通道的网络层选项：代理与 SSL/TLS（仅 http 通道有效，详见 {@link SyncSpec#httpOptions(HttpOptions)}）。 */
		public SyncSpecWithOptions httpOptions(HttpOptions options) {
			this.httpOptions = options;
			return this;
		}

		/**
		 * Sets the hook registry for intercepting tool execution.
		 * @param hookRegistry the hook registry
		 * @return this builder instance for method chaining
		 */
		public SyncSpecWithOptions hookRegistry(HookRegistry hookRegistry) {
			this.hookRegistry = hookRegistry;
			return this;
		}

		/**
		 * prompt 风格入口：把当前 builder 配置（包括通道与 CLIOptions）绑定到一次请求。
		 * @param prompt 提示语；不可为空
		 * @return 请求描述，用 call() / stream() 收束
		 */
		public SolonCodeRequestDesc prompt(String prompt) {
			return new DefaultSolonCodeRequestDesc(prompt, options -> build());
		}

		/**
		 * Builds and returns the configured SolonCodeSyncClient.
		 * @return a new SolonCodeSyncClient instance
		 * @throws IllegalArgumentException if workingDirectory is not set
		 */
		public SolonCodeSyncClient build() {
			if (transportSpec.isHttp()) {
				if (workingDirectory != null) {
					throw new IllegalArgumentException(
							"workingDirectory is not applicable to the http transport; use workspace(String) instead");
				}
				if (authToken != null || httpWorkspace != null) {
					transportSpec = transportSpec.withHttpCredentials(authToken, httpWorkspace);
				}
				if (httpOptions != null) {
					transportSpec = transportSpec.withHttpOptions(httpOptions);
				}
			}
			else {
				if (httpOptions != null) {
					throw new IllegalArgumentException("httpOptions is only applicable to the http transport");
				}
				if (workingDirectory == null) {
					throw new IllegalArgumentException("workingDirectory is required");
				}
			}
			return new DefaultSolonCodeSyncClient(workingDirectory, options, timeout, transportSpec, hookRegistry);
		}

	}

	// ========================================================================
	// Async Client Factory Methods
	// ========================================================================

	/**
	 * Start building an asynchronous SolonCode client with fluent configuration.
	 *
	 * <p>
	 * Use this method when you want to configure CLI options (model, system prompt,
	 * tools, etc.) via the fluent builder API. For pre-built CLIOptions, use
	 * {@link #async(CLIOptions)} instead.
	 * </p>
	 *
	 * <p>
	 * The asynchronous client returns reactive types ({@link reactor.core.publisher.Mono}
	 * and {@link reactor.core.publisher.Flux}) for non-blocking operations.
	 * </p>
	 * @return A new builder instance for configuring the asynchronous client
	 * @see #async(CLIOptions)
	 * @see SolonCodeAsyncClient
	 */
	static AsyncSpec async() {
		return new AsyncSpec();
	}

	/**
	 * Start building an asynchronous SolonCode client with pre-configured CLI options.
	 *
	 * <p>
	 * Use this method when you have a pre-built {@link CLIOptions} object. The returned
	 * builder only exposes session-level configuration (working directory, timeout,
	 * hooks) since CLI options are already provided.
	 * </p>
	 * @param options the pre-configured CLI options
	 * @return A new builder instance for session-level configuration only
	 * @see #async()
	 * @see SolonCodeAsyncClient
	 */
	static AsyncSpecWithOptions async(CLIOptions options) {
		return new AsyncSpecWithOptions(options);
	}

	/**
	 * Fluent builder for creating a {@link SolonCodeAsyncClient} with full configuration
	 * control.
	 *
	 * <p>
	 * Use this builder when you want to configure all options inline using method
	 * chaining. This is the recommended approach for most use cases.
	 * </p>
	 *
	 * <p>
	 * The configuration options are identical to {@link SyncSpec} - see that class for
	 * detailed documentation of each option.
	 * </p>
	 *
	 * <h2>Example</h2> <pre>{@code
	 * SolonCodeAsyncClient client = SolonCodeClient.async()
	 *     .workingDirectory(Path.of("."))
	 *     .model("sonnet")
	 *     .systemPrompt("You are a helpful assistant")
	 *     .timeout(Duration.ofMinutes(5))
	 *     .build();
	 *
	 * client.connect("Hello!")
	 *     .thenMany(client.receiveResponse())
	 *     .subscribe(msg -> System.out.println(msg));
	 * }</pre>
	 *
	 * @see #async()
	 * @see AsyncSpecWithOptions
	 * @see SolonCodeAsyncClient
	 */
	class AsyncSpec {

		private Path workingDirectory;

		private Duration timeout = Duration.ofMinutes(10);

		private TransportSpec transportSpec = TransportSpec.stdio();

		/** http 通道的 Bearer token（仅 http 通道使用） */
		private String authToken;

		/** http 通道的服务端工作区标识（仅 http 通道使用，替代 workingDirectory） */
		private String httpWorkspace;

		/** http 通道的网络层选项：代理与 SSL/TLS（仅 http 通道使用） */
		private HttpOptions httpOptions;

		private HookRegistry hookRegistry;

		// CLIOptions fields
		private String model;

		private String systemPrompt;

		private String appendSystemPrompt;

		private Integer maxTokens;

		private Integer maxThinkingTokens;

		private List<String> tools;

		private List<String> allowedTools = new ArrayList<>();

		private List<String> disallowedTools = new ArrayList<>();

		private PermissionMode permissionMode = PermissionMode.DEFAULT;

		private Integer maxTurns;

		// ===== soloncode 特有选项 =====

		/** 固定会话 ID（多轮 --resume 串接的锚点）。 */
		private String sessionId;

		/** bare 模式：跳过技能/子代理挂载、MCP 服务与记忆自动发现。 */
		private boolean bare;

		/** 主模型不可用时的回退模型。 */
		private String fallbackModel;

		private Double maxBudgetUsd;

		private Map<String, McpServerConfig> mcpServers = new HashMap<>();

		AsyncSpec() {
		}

		public AsyncSpec workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public AsyncSpec timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/** 使用本机 stdio 通道（默认）：拉起常驻 {@code soloncode stream} 子进程。 */
		public AsyncSpec stdio() {
			this.transportSpec = TransportSpec.stdio();
			return this;
		}

		/** 使用本机 stdio 通道，并指定 soloncode 可执行文件路径。 */
		public AsyncSpec stdio(String cliPath) {
			this.transportSpec = TransportSpec.stdio(cliPath);
			return this;
		}

		/** 兼容模式：每轮启动一次 {@code soloncode run} 子进程。 */
		public AsyncSpec stdioOneShot(String cliPath) {
			this.transportSpec = TransportSpec.stdioOneShot(cliPath);
			return this;
		}

		/** 使用 HTTP 通道：投递到服务端 {@code /web/run} 端点（详见 {@link SyncSpec#http(String)}）。 */
		public AsyncSpec http(String url) {
			this.transportSpec = TransportSpec.http(url);
			return this;
		}

		/** HTTP 通道的 Bearer token。 */
		public AsyncSpec authToken(String token) {
			this.authToken = token;
			return this;
		}

		/** HTTP 通道的服务端工作区标识，替代 workingDirectory。 */
		public AsyncSpec workspace(String workspace) {
			this.httpWorkspace = workspace;
			return this;
		}

		/** HTTP 通道的网络层选项：代理与 SSL/TLS（仅 http 通道有效，详见 {@link SyncSpec#httpOptions(HttpOptions)}）。 */
		public AsyncSpec httpOptions(HttpOptions options) {
			this.httpOptions = options;
			return this;
		}

		public AsyncSpec hookRegistry(HookRegistry hookRegistry) {
			this.hookRegistry = hookRegistry;
			return this;
		}

		public AsyncSpec model(String model) {
			this.model = model;
			return this;
		}

		public AsyncSpec systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public AsyncSpec appendSystemPrompt(String appendSystemPrompt) {
			this.appendSystemPrompt = appendSystemPrompt;
			return this;
		}

		public AsyncSpec maxTokens(Integer maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		public AsyncSpec maxThinkingTokens(Integer maxThinkingTokens) {
			this.maxThinkingTokens = maxThinkingTokens;
			return this;
		}

		public AsyncSpec tools(List<String> tools) {
			this.tools = tools;
			return this;
		}

		public AsyncSpec allowedTools(List<String> allowedTools) {
			this.allowedTools = new ArrayList<>(allowedTools);
			return this;
		}

		public AsyncSpec disallowedTools(List<String> disallowedTools) {
			this.disallowedTools = new ArrayList<>(disallowedTools);
			return this;
		}

		public AsyncSpec permissionMode(PermissionMode permissionMode) {
			this.permissionMode = permissionMode;
			return this;
		}

		public AsyncSpec maxTurns(Integer maxTurns) {
			this.maxTurns = maxTurns;
			return this;
		}

		public AsyncSpec maxBudgetUsd(Double maxBudgetUsd) {
			this.maxBudgetUsd = maxBudgetUsd;
			return this;
		}

		/** 设置固定会话 ID（soloncode {@code --session-id}）。 */
		public AsyncSpec sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		/** 开启 bare 模式（soloncode {@code --bare}）。 */
		public AsyncSpec bare(boolean bare) {
			this.bare = bare;
			return this;
		}

		/** 设置回退模型（soloncode {@code --fallback-model}）。 */
		public AsyncSpec fallbackModel(String fallbackModel) {
			this.fallbackModel = fallbackModel;
			return this;
		}

		public AsyncSpec mcpServer(String name, McpServerConfig config) {
			this.mcpServers.put(name, config);
			return this;
		}

		public AsyncSpec mcpServers(Map<String, McpServerConfig> mcpServers) {
			this.mcpServers = new HashMap<>(mcpServers);
			return this;
		}

		/**
		 * Builds and returns the configured SolonCodeAsyncClient.
		 * @return a new SolonCodeAsyncClient instance
		 * @throws IllegalArgumentException if workingDirectory is not set
		 */
		public SolonCodeAsyncClient build() {
			if (transportSpec.isHttp()) {
				if (workingDirectory != null) {
					throw new IllegalArgumentException(
							"workingDirectory is not applicable to the http transport; use workspace(String) instead");
				}
				if (authToken != null || httpWorkspace != null) {
					transportSpec = transportSpec.withHttpCredentials(authToken, httpWorkspace);
				}
				if (httpOptions != null) {
					transportSpec = transportSpec.withHttpOptions(httpOptions);
				}
			}
			else {
				if (httpOptions != null) {
					throw new IllegalArgumentException("httpOptions is only applicable to the http transport");
				}
				if (workingDirectory == null) {
					throw new IllegalArgumentException("workingDirectory is required");
				}
			}

			CLIOptions options = CLIOptions.builder()
				.model(model)
				.systemPrompt(systemPrompt)
				.appendSystemPrompt(appendSystemPrompt)
				.maxTokens(maxTokens)
				.maxThinkingTokens(maxThinkingTokens)
				.tools(tools)
				.allowedTools(allowedTools)
				.disallowedTools(disallowedTools)
				.permissionMode(permissionMode)
				.maxTurns(maxTurns)
				.maxBudgetUsd(maxBudgetUsd)
				.mcpServers(mcpServers)
				.sessionId(sessionId)
				.bare(bare)
				.fallbackModel(fallbackModel)
				.build();

			return new DefaultSolonCodeAsyncClient(workingDirectory, options, timeout, transportSpec, hookRegistry);
		}

	}

	/**
	 * Builder for creating a {@link SolonCodeAsyncClient} with pre-configured CLI options.
	 *
	 * <p>
	 * Use this builder when you have a pre-built {@link CLIOptions} object and only need
	 * to configure session-level settings.
	 * </p>
	 *
	 * <h2>Example</h2> <pre>{@code
	 * CLIOptions options = CLIOptions.builder()
	 *     .model("sonnet")
	 *     .build();
	 *
	 * SolonCodeAsyncClient client = SolonCodeClient.async(options)
	 *     .workingDirectory(Path.of("."))
	 *     .timeout(Duration.ofMinutes(5))
	 *     .build();
	 * }</pre>
	 *
	 * @see #async(CLIOptions)
	 * @see AsyncSpec
	 * @see CLIOptions
	 */
	class AsyncSpecWithOptions {

		private final CLIOptions options;

		private Path workingDirectory;

		private Duration timeout = Duration.ofMinutes(10);

		private TransportSpec transportSpec = TransportSpec.stdio();

		/** http 通道的 Bearer token（仅 http 通道使用） */
		private String authToken;

		/** http 通道的服务端工作区标识（仅 http 通道使用，替代 workingDirectory） */
		private String httpWorkspace;

		/** http 通道的网络层选项：代理与 SSL/TLS（仅 http 通道使用） */
		private HttpOptions httpOptions;

		private HookRegistry hookRegistry;

		AsyncSpecWithOptions(CLIOptions options) {
			this.options = options;
		}

		public AsyncSpecWithOptions workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public AsyncSpecWithOptions timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/** 使用本机 stdio 通道（默认）：拉起常驻 {@code soloncode stream} 子进程。 */
		public AsyncSpecWithOptions stdio() {
			this.transportSpec = TransportSpec.stdio();
			return this;
		}

		/** 使用本机 stdio 通道，并指定 soloncode 可执行文件路径。 */
		public AsyncSpecWithOptions stdio(String cliPath) {
			this.transportSpec = TransportSpec.stdio(cliPath);
			return this;
		}

		/** 兼容模式：每轮启动一次 {@code soloncode run} 子进程。 */
		public AsyncSpecWithOptions stdioOneShot(String cliPath) {
			this.transportSpec = TransportSpec.stdioOneShot(cliPath);
			return this;
		}

		/** 使用 HTTP 通道：投递到服务端 {@code /web/run} 端点（详见 {@link SyncSpec#http(String)}）。 */
		public AsyncSpecWithOptions http(String url) {
			this.transportSpec = TransportSpec.http(url);
			return this;
		}

		/** HTTP 通道的 Bearer token。 */
		public AsyncSpecWithOptions authToken(String token) {
			this.authToken = token;
			return this;
		}

		/** HTTP 通道的服务端工作区标识，替代 workingDirectory。 */
		public AsyncSpecWithOptions workspace(String workspace) {
			this.httpWorkspace = workspace;
			return this;
		}

		/** HTTP 通道的网络层选项：代理与 SSL/TLS（仅 http 通道有效，详见 {@link SyncSpec#httpOptions(HttpOptions)}）。 */
		public AsyncSpecWithOptions httpOptions(HttpOptions options) {
			this.httpOptions = options;
			return this;
		}

		public AsyncSpecWithOptions hookRegistry(HookRegistry hookRegistry) {
			this.hookRegistry = hookRegistry;
			return this;
		}

		/**
		 * Builds and returns the configured SolonCodeAsyncClient.
		 * @return a new SolonCodeAsyncClient instance
		 * @throws IllegalArgumentException if workingDirectory is not set
		 */
		public SolonCodeAsyncClient build() {
			if (transportSpec.isHttp()) {
				if (workingDirectory != null) {
					throw new IllegalArgumentException(
							"workingDirectory is not applicable to the http transport; use workspace(String) instead");
				}
				if (authToken != null || httpWorkspace != null) {
					transportSpec = transportSpec.withHttpCredentials(authToken, httpWorkspace);
				}
				if (httpOptions != null) {
					transportSpec = transportSpec.withHttpOptions(httpOptions);
				}
			}
			else {
				if (httpOptions != null) {
					throw new IllegalArgumentException("httpOptions is only applicable to the http transport");
				}
				if (workingDirectory == null) {
					throw new IllegalArgumentException("workingDirectory is required");
				}
			}
			return new DefaultSolonCodeAsyncClient(workingDirectory, options, timeout, transportSpec, hookRegistry);
		}

	}

	/** Request-oriented client API shared by blocking and streaming callers. */
	interface Request {
		org.noear.soloncode.sdk.types.QueryResult call() throws org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
		reactor.core.publisher.Flux<org.noear.soloncode.sdk.types.Message> stream();
	}

	/** Unified builder. Successive requests on the built client are successive turns. */
	class Builder {
		private final SyncSpec delegate = new SyncSpec();
		public Builder workingDirectory(Path v) { delegate.workingDirectory(v); return this; }
		public Builder timeout(Duration v) { delegate.timeout(v); return this; }
		public Builder stdio() { delegate.stdio(); return this; }
		public Builder stdio(String v) { delegate.stdio(v); return this; }
		public Builder stdioOneShot(String v) { delegate.stdioOneShot(v); return this; }
		public Builder http(String v) { delegate.http(v); return this; }
		public Builder authToken(String v) { delegate.authToken(v); return this; }
		public Builder workspace(String v) { delegate.workspace(v); return this; }
		public Builder httpOptions(HttpOptions v) { delegate.httpOptions(v); return this; }
		public Builder hookRegistry(HookRegistry v) { delegate.hookRegistry(v); return this; }
		public Builder model(String v) { delegate.model(v); return this; }
		public Builder systemPrompt(String v) { delegate.systemPrompt(v); return this; }
		public Builder appendSystemPrompt(String v) { delegate.appendSystemPrompt(v); return this; }
		public Builder maxTokens(Integer v) { delegate.maxTokens(v); return this; }
		public Builder maxThinkingTokens(Integer v) { delegate.maxThinkingTokens(v); return this; }
		public Builder tools(List<String> v) { delegate.tools(v); return this; }
		public Builder allowedTools(List<String> v) { delegate.allowedTools(v); return this; }
		public Builder disallowedTools(List<String> v) { delegate.disallowedTools(v); return this; }
		public Builder permissionMode(PermissionMode v) { delegate.permissionMode(v); return this; }
		public Builder maxTurns(Integer v) { delegate.maxTurns(v); return this; }
		public Builder maxBudgetUsd(Double v) { delegate.maxBudgetUsd(v); return this; }
		public Builder sessionId(String v) { delegate.sessionId(v); return this; }
		public Builder bare(boolean v) { delegate.bare(v); return this; }
		public Builder fallbackModel(String v) { delegate.fallbackModel(v); return this; }
		public Builder mcpServer(String name, McpServerConfig v) { delegate.mcpServer(name, v); return this; }
		public Builder mcpServers(Map<String, McpServerConfig> v) { delegate.mcpServers(v); return this; }
		public SolonCodeClient build() {
            // The legacy builder still declares SolonCodeSyncClient for now; its concrete
            // implementation also supplies the internal session contract used here.
            return new DefaultSolonCodeClient((SolonCodeSession) delegate.build());
        }
	}

	Request prompt(String prompt);
	void interrupt() throws org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
	void setModel(String model) throws org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
	void setPermissionMode(String mode) throws org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
	CLIOptions getOptions();
	String getCurrentModel();
	String getCurrentPermissionMode();
	Map<String, Object> getServerInfo();
	boolean isConnected();
	void setToolPermissionCallback(org.noear.soloncode.sdk.permission.ToolPermissionCallback callback);
	org.noear.soloncode.sdk.permission.ToolPermissionCallback getToolPermissionCallback();
	@Override
	void close();

}
