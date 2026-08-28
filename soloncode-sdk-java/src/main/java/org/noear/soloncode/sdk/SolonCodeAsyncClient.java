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

import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.permission.ToolPermissionCallback;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * Reactive client for interacting with SolonCode CLI using Project Reactor.
 *
 * <p>
 * This interface provides non-blocking, backpressure-aware operations for multi-turn
 * conversations with SolonCode. It is the reactive equivalent of {@link SolonCodeSyncClient}
 * and is designed for reactive applications.
 * </p>
 *
 * <h2>Key Features</h2>
 * <ul>
 * <li>Non-blocking operations with {@link Mono} and {@link Flux}</li>
 * <li>Backpressure support for message streaming</li>
 * <li>Multi-turn conversation support with elegant flatMap chaining</li>
 * <li>Hook support for intercepting tool calls</li>
 * <li>MCP server integration</li>
 * <li>Permission callbacks for tool authorization</li>
 * </ul>
 *
 * <h2>Basic Usage - Stream Text</h2> <pre>{@code
 * SolonCodeAsyncClient client = SolonCodeClient.async()
 *     .workingDirectory(Path.of("."))
 *     .model("sonnet")
 *     .build();
 *
 * client.connect("Explain recursion").textStream()
 *     .doOnNext(System.out::print)
 *     .subscribe();
 * }</pre>
 *
 * <h2>Multi-turn Conversation with flatMap Chaining</h2> <pre>{@code
 * client.connect("My favorite color is blue.").text()
 *     .doOnSuccess(System.out::println)
 *     .flatMap(r1 -> client.query("What is my favorite color?").text())
 *     .doOnSuccess(System.out::println)  // SolonCode remembers: "blue"
 *     .flatMap(r2 -> client.query("Spell it backwards").text())
 *     .doOnSuccess(System.out::println)
 *     .subscribe();
 * }</pre>
 *
 * <h2>Full Message Access (20% Use Case)</h2> <pre>{@code
 * client.query("List files").messages()
 *     .doOnNext(msg -> {
 *         if (msg instanceof AssistantMessage am) {
 *             System.out.println("Text: " + am.text());
 *         } else if (msg instanceof ResultMessage rm) {
 *             System.out.printf("Cost: $%.6f%n", rm.totalCostUsd());
 *         }
 *     })
 *     .subscribe();
 * }</pre>
 *
 * @see SolonCodeSyncClient
 * @see SolonCodeClient#async()
 */
public interface SolonCodeAsyncClient {

	// ========================================================================
	// TurnSpec - Response handling for a single turn
	// ========================================================================

	/**
	 * Specification for handling responses from a single conversation turn.
	 *
	 * <p>
	 * Inspired by Spring WebClient's ResponseSpec pattern, TurnSpec provides terminal
	 * operations for different use cases:
	 * </p>
	 * <ul>
	 * <li>{@link #text()} - Collected text as Mono (for flatMap chaining)</li>
	 * <li>{@link #textStream()} - Streaming text as Flux (for SSE/CLI)</li>
	 * <li>{@link #messages()} - All message types as Flux (for full access)</li>
	 * </ul>
	 *
	 * <p>
	 * All operations are lazy - the actual query/connect is triggered on subscription.
	 * </p>
	 */
	interface TurnSpec {

		/**
		 * Returns the collected text response as a single Mono.
		 *
		 * <p>
		 * This is the primary method for multi-turn conversations with flatMap chaining:
		 * </p>
		 * <pre>{@code
		 * client.connect("Hello").text()
		 *     .flatMap(r -> client.query("Follow up").text())
		 *     .block();
		 * }</pre>
		 * @return Mono containing all text from AssistantMessages concatenated
		 */
		Mono<String> text();

		/**
		 * Returns the text response as a streaming Flux.
		 *
		 * <p>
		 * Use this for SSE endpoints or CLI output where you want to stream text as it
		 * arrives:
		 * </p>
		 * <pre>{@code
		 * &#64;GetMapping(value = "/chat", produces = TEXT_EVENT_STREAM_VALUE)
		 * public Flux<String> chat(@RequestParam String message) {
		 *     return client.query(message).textStream();
		 * }
		 * }</pre>
		 * @return Flux of text chunks from AssistantMessages
		 */
		Flux<String> textStream();

		/**
		 * Returns all messages for this turn as a Flux.
		 *
		 * <p>
		 * Use this when you need access to all message types (AssistantMessage,
		 * ResultMessage, etc.) for tool use details, cost information, or debugging:
		 * </p>
		 * <pre>{@code
		 * client.query("List files").messages()
		 *     .doOnNext(msg -> {
		 *         if (msg instanceof ResultMessage rm) {
		 *             System.out.println("Cost: $" + rm.totalCostUsd());
		 *         }
		 *     })
		 *     .subscribe();
		 * }</pre>
		 * @return Flux of all Message types for this turn
		 */
		Flux<Message> messages();

	}

	// ========================================================================
	// Connection Management
	// ========================================================================

	/**
	 * Connects to SolonCode CLI and starts a new session without an initial prompt.
	 *
	 * <p>
	 * No user message is sent: the session is started and initialised, and the first
	 * turn is whatever the caller passes to {@link #query(String)}. Use
	 * {@link #connect(String)} to open the conversation with a prompt.
	 * </p>
	 * @return Mono that completes when the connection is established
	 */
	Mono<Void> connect();

	/**
	 * Connects to SolonCode CLI and starts a new session with an initial prompt.
	 *
	 * <p>
	 * Returns a {@link TurnSpec} for handling the response. All operations are lazy - the
	 * connection and query are triggered on subscription.
	 * </p>
	 *
	 * <p>
	 * Example:
	 * </p>
	 * <pre>{@code
	 * // Get text response
	 * String answer = client.connect("What is 2+2?").text().block();
	 *
	 * // Stream text for SSE
	 * client.connect("Explain recursion").textStream()
	 *     .doOnNext(System.out::print)
	 *     .subscribe();
	 *
	 * // Multi-turn with flatMap
	 * client.connect("My favorite color is blue").text()
	 *     .flatMap(r -> client.query("What is it?").text())
	 *     .block();
	 * }</pre>
	 * @param initialPrompt the initial prompt to send
	 * @return TurnSpec for handling the response
	 */
	TurnSpec connect(String initialPrompt);

	/**
	 * Returns whether the client is currently connected to SolonCode CLI.
	 * @return true if connected, false otherwise
	 */
	boolean isConnected();

	// ========================================================================
	// Query Operations
	// ========================================================================

	/**
	 * Sends a query to SolonCode and returns a spec for handling the response.
	 *
	 * <p>
	 * Returns a {@link TurnSpec} for handling the response. All operations are lazy - the
	 * query is triggered on subscription. Must be connected first.
	 * </p>
	 *
	 * <p>
	 * Example:
	 * </p>
	 * <pre>{@code
	 * // Get text response
	 * String answer = client.query("What is 2+2?").text().block();
	 *
	 * // Stream text for SSE
	 * client.query("Explain recursion").textStream()
	 *     .doOnNext(System.out::print)
	 *     .subscribe();
	 *
	 * // Multi-turn with flatMap
	 * client.connect("My favorite color is blue").text()
	 *     .flatMap(r -> client.query("What is it?").text())
	 *     .block();
	 * }</pre>
	 * @param prompt the prompt to send
	 * @return TurnSpec for handling the response
	 */
	TurnSpec query(String prompt);

	/**
	 * Receives response messages from SolonCode as a reactive stream.
	 *
	 * <p>
	 * The returned Flux emits messages as they arrive and completes when a
	 * {@link org.noear.soloncode.sdk.types.ResultMessage} is received.
	 * </p>
	 * @return Flux of parsed messages
	 */
	Flux<ParsedMessage> receiveMessages();

	/**
	 * Receives response messages, returning only regular messages (not control messages).
	 *
	 * <p>
	 * This is a convenience method that filters to regular messages and converts them to
	 * the {@link Message} type.
	 * </p>
	 * @return Flux of messages
	 */
	Flux<Message> receiveResponse();

	/**
	 * Convenience method combining query and receive into a single turn-scoped operation.
	 *
	 * <p>
	 * Equivalent to {@code client.query(prompt).messages()}.
	 * </p>
	 * @param prompt the query to send
	 * @return Flux of messages for this turn, completing at ResultMessage
	 * @deprecated Use {@code query(prompt).messages()} instead for clearer intent
	 */
	@Deprecated
	default Flux<Message> queryAndReceive(String prompt) {
		return query(prompt).messages();
	}

	/**
	 * Connects with initial prompt and returns response messages as a reactive stream.
	 *
	 * <p>
	 * Equivalent to {@code client.connect(prompt).messages()}.
	 * </p>
	 * @param prompt the initial prompt
	 * @return Flux of messages for this turn
	 * @deprecated Use {@code connect(prompt).messages()} instead for clearer intent
	 */
	@Deprecated
	default Flux<Message> connectAndReceive(String prompt) {
		return connect(prompt).messages();
	}

	/**
	 * Connects with initial prompt and streams just the text response.
	 *
	 * <p>
	 * Equivalent to {@code client.connect(prompt).textStream()}.
	 * </p>
	 * @param prompt the initial prompt
	 * @return Flux of text chunks from AssistantMessages
	 * @deprecated Use {@code connect(prompt).textStream()} instead for clearer intent
	 */
	@Deprecated
	default Flux<String> connectText(String prompt) {
		return connect(prompt).textStream();
	}

	/**
	 * Streams just the text content from SolonCode's response.
	 *
	 * <p>
	 * Equivalent to {@code client.query(prompt).textStream()}.
	 * </p>
	 * @param prompt the query to send
	 * @return Flux of text chunks from AssistantMessages
	 * @deprecated Use {@code query(prompt).textStream()} instead for clearer intent
	 */
	@Deprecated
	default Flux<String> queryText(String prompt) {
		return query(prompt).textStream();
	}

	// ========================================================================
	// Session Control
	// ========================================================================

	/**
	 * Interrupts the current SolonCode operation.
	 * @return Mono that completes when the interrupt has been sent
	 */
	Mono<Void> interrupt();

	/**
	 * Sets the permission mode for tool execution.
	 * @param mode the permission mode (e.g., "default", "acceptEdits",
	 * "bypassPermissions")
	 * @return Mono that completes when the mode has been set
	 */
	Mono<Void> setPermissionMode(String mode);

	/**
	 * Changes the SolonCode model during the session.
	 * @param model the model ID to switch to
	 * @return Mono that completes when the model has been changed
	 */
	Mono<Void> setModel(String model);

	// ========================================================================
	// Server Info
	// ========================================================================

	/**
	 * Returns server information received during initialization.
	 * @return Optional containing server info map, or empty if not yet received
	 */
	Optional<Map<String, Object>> getServerInfo();

	// ========================================================================
	// Permission Callback
	// ========================================================================

	/**
	 * Sets a callback for tool permission decisions.
	 * @param callback the callback to invoke for permission checks
	 */
	void setToolPermissionCallback(ToolPermissionCallback callback);

	/**
	 * Gets the current tool permission callback.
	 * @return the current callback, or null if none set
	 */
	ToolPermissionCallback getToolPermissionCallback();

	// ========================================================================
	// Cross-Turn Handlers
	// ========================================================================

	/**
	 * Registers a handler that receives all messages across all turns.
	 *
	 * <p>
	 * Use handlers for cross-turn concerns like logging, metrics, or tracing. For
	 * per-turn processing, use the Flux from {@link #receiveResponse()} instead.
	 * </p>
	 *
	 * <p>
	 * Handlers are called synchronously before messages are emitted to the turn sink.
	 * Keep handler logic fast to avoid blocking message processing.
	 * </p>
	 * @param handler the handler to receive messages
	 * @return this client for fluent chaining
	 */
	SolonCodeAsyncClient onMessage(java.util.function.Consumer<Message> handler);

	/**
	 * Registers a handler that receives result messages across all turns.
	 *
	 * <p>
	 * Result messages indicate the end of a turn and contain usage/cost metadata. Use
	 * this for tracking conversation statistics or triggering end-of-turn actions.
	 * </p>
	 * @param handler the handler to receive result messages
	 * @return this client for fluent chaining
	 */
	SolonCodeAsyncClient onResult(java.util.function.Consumer<ResultMessage> handler);

	// ========================================================================
	// Lifecycle
	// ========================================================================

	/**
	 * Closes the client and releases all resources.
	 * @return Mono that completes when the client is closed
	 */
	Mono<Void> close();

	/**
	 * Alias for {@link #close()} for semantic clarity.
	 * @return Mono that completes when disconnected
	 */
	default Mono<Void> disconnect() {
		return close();
	}

}
