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
 *
 * Adapted from claude-agent-sdk-java (Apache License 2.0).
 */

package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Main entry point for one-shot SolonCode Code queries. Provides a simple, stateless API for
 * sending prompts and receiving responses.
 *
 * <p>
 * This class corresponds to the {@code query()} function in the Python SDK. For
 * multi-turn conversations, hooks, or MCP integration, use {@link SolonCodeSyncClient} or
 * {@link SolonCodeAsyncClient} instead.
 *
 * <h2>Quick Start</h2>
 *
 * <pre>{@code
 * // Simplest usage - get text response
 * String answer = Query.text("What is 2+2?");
 *
 * // Iterate over messages (for streaming-style processing)
 * for (Message msg : Query.query("Explain recursion")) {
 *     if (msg instanceof AssistantMessage assistant) {
 *         assistant.getTextContent().ifPresent(System.out::print);
 *     }
 * }
 *
 * // Full result with metadata
 * QueryResult result = Query.execute("Write a haiku");
 * System.out.println("Cost: $" + result.metadata().cost().calculateTotal());
 * }</pre>
 *
 * <h2>With Options</h2>
 *
 * <pre>{@code
 * String response = Query.text("Explain quantum computing",
 *     QueryOptions.builder()
 *         .model("claude-sonnet-4-5-20250929")
 *         .systemPrompt("Be concise")
 *         .timeout(Duration.ofMinutes(5))
 *         .build());
 * }</pre>
 *
 * @see QueryOptions
 * @see QueryResult
 * @see SolonCodeSyncClient
 * @see SolonCodeAsyncClient
 */
public class Query {

	private static final Logger logger = LoggerFactory.getLogger(Query.class);

	// ========================================================================
	// Simple API - text()
	// ========================================================================

	/**
	 * Executes a query and returns just the text response. This is the simplest way to
	 * use SolonCode.
	 *
	 * <pre>{@code
	 * String answer = Query.text("What is 2+2?");
	 * System.out.println(answer);  // "4"
	 * }</pre>
	 * @param prompt the prompt to send to SolonCode
	 * @return the text response, or empty string if no response
	 * @throws SolonCodeSDKException if the query fails
	 */
	public static String text(String prompt) throws SolonCodeSDKException {
		return text(prompt, QueryOptions.defaults());
	}

	/**
	 * Executes a query with options and returns just the text response.
	 * @param prompt the prompt to send to SolonCode
	 * @param options configuration options
	 * @return the text response, or empty string if no response
	 * @throws SolonCodeSDKException if the query fails
	 */
	public static String text(String prompt, QueryOptions options) throws SolonCodeSDKException {
		QueryResult result = execute(prompt, options);
		return result.text().orElse("");
	}

	// ========================================================================
	// Streaming API - query()
	// ========================================================================

	/**
	 * Executes a query and returns an iterable over messages. Useful for processing
	 * messages as they arrive (streaming-style).
	 *
	 * <pre>{@code
	 * for (Message msg : Query.query("Explain recursion")) {
	 *     if (msg instanceof AssistantMessage assistant) {
	 *         assistant.getTextContent().ifPresent(System.out::print);
	 *     }
	 * }
	 * }</pre>
	 * @param prompt the prompt to send to SolonCode
	 * @return an iterable over the response messages
	 * @throws SolonCodeSDKException if the query fails
	 */
	public static Iterable<Message> query(String prompt) throws SolonCodeSDKException {
		return query(prompt, QueryOptions.defaults());
	}

	/**
	 * Executes a query with options and returns an iterable over messages.
	 * @param prompt the prompt to send to SolonCode
	 * @param options configuration options
	 * @return an iterable over the response messages
	 * @throws SolonCodeSDKException if the query fails
	 */
	public static Iterable<Message> query(String prompt, QueryOptions options) throws SolonCodeSDKException {
		QueryResult result = execute(prompt, options);
		return result.messages();
	}

	// ========================================================================
	// Stream API - stream()
	// ========================================================================

	/**
	 * Executes a query and returns a Stream of messages. Useful for functional-style
	 * processing with filter, map, collect, etc.
	 *
	 * <pre>{@code
	 * Query.stream("Explain recursion")
	 *     .filter(msg -> msg instanceof AssistantMessage)
	 *     .map(msg -> ((AssistantMessage) msg).getTextContent())
	 *     .filter(Optional::isPresent)
	 *     .map(Optional::get)
	 *     .forEach(System.out::println);
	 * }</pre>
	 * @param prompt the prompt to send to SolonCode
	 * @return a Stream over the response messages
	 * @throws SolonCodeSDKException if the query fails
	 */
	public static Stream<Message> stream(String prompt) throws SolonCodeSDKException {
		return stream(prompt, QueryOptions.defaults());
	}

	/**
	 * Executes a query with options and returns a Stream of messages.
	 * @param prompt the prompt to send to SolonCode
	 * @param options configuration options
	 * @return a Stream over the response messages
	 * @throws SolonCodeSDKException if the query fails
	 */
	public static Stream<Message> stream(String prompt, QueryOptions options) throws SolonCodeSDKException {
		QueryResult result = execute(prompt, options);
		return result.messages().stream();
	}

	// ========================================================================
	// Full API - execute()
	// ========================================================================

	/**
	 * Executes a query and returns the full result with metadata.
	 * @param prompt the prompt to send to SolonCode
	 * @return the complete query result including messages and metadata
	 * @throws SolonCodeSDKException if the query fails
	 */
	public static QueryResult execute(String prompt) throws SolonCodeSDKException {
		return execute(prompt, QueryOptions.defaults());
	}

	/**
	 * Executes a query with options and returns the full result.
	 * @param prompt the prompt to send to SolonCode
	 * @param options configuration options
	 * @return the complete query result including messages and metadata
	 * @throws SolonCodeSDKException if the query fails
	 */
	public static QueryResult execute(String prompt, QueryOptions options) throws SolonCodeSDKException {
		return execute(prompt, options.toCLIOptions(), options.workingDirectory());
	}

	/**
	 * Executes a synchronous query with full CLIOptions.
	 * @deprecated Use {@link #execute(String, QueryOptions)} instead for simpler API
	 */
	@Deprecated
	public static QueryResult execute(String prompt, CLIOptions options) throws SolonCodeSDKException {
		return execute(prompt, options, Paths.get(System.getProperty("user.dir")));
	}

	/**
	 * Executes a synchronous query with specified options and working directory.
	 *
	 * <p>
	 * This method uses {@link SolonCodeSyncClient} internally, providing unified transport
	 * layer handling across all SDK APIs.
	 * </p>
	 */
	public static QueryResult execute(String prompt, CLIOptions options, Path workingDirectory)
			throws SolonCodeSDKException {

		logger.info("Executing query with prompt length: {}", prompt.length());

		try (SolonCodeSyncClient client = SolonCodeClient.sync(options)
			.workingDirectory(workingDirectory)
			.timeout(options.getTimeout())
			.build()) {

			// Connect with prompt
			client.connect(prompt);

			// Collect all messages from response
			List<Message> messages = new ArrayList<>();
			Iterator<ParsedMessage> response = client.receiveResponse();
			while (response.hasNext()) {
				ParsedMessage parsed = response.next();
				if (parsed.isRegularMessage()) {
					messages.add(parsed.asMessage());
				}
			}

			// Process messages to build domain-rich result
			return buildQueryResult(messages, options);

		}
		catch (Exception e) {
			if (e instanceof SolonCodeSDKException) {
				throw e;
			}
			throw new SolonCodeSDKException("Failed to execute query", e);
		}
	}

	/**
	 * Builds a QueryResult from raw messages with domain-rich metadata.
	 */
	private static QueryResult buildQueryResult(List<Message> messages, CLIOptions options) {
		// Find the result message to extract metadata
		Optional<ResultMessage> resultMessage = messages.stream()
			.filter(m -> m instanceof ResultMessage)
			.map(m -> (ResultMessage) m)
			.findFirst();

		// Build metadata from result message
		Metadata metadata = resultMessage
			.map(rm -> rm.toMetadata(options.getModel() != null ? options.getModel() : "unknown"))
			.orElse(createDefaultMetadata(options));

		// Determine result status
		ResultStatus status = determineStatus(messages, resultMessage);

		return QueryResult.builder().messages(messages).metadata(metadata).status(status).build();
	}

	/**
	 * Creates default metadata when no result message is available.
	 */
	private static Metadata createDefaultMetadata(CLIOptions options) {
		return Metadata.builder()
			.model(options.getModel() != null ? options.getModel() : "unknown")
			.cost(Cost.builder()
				.inputTokenCost(0.0)
				.outputTokenCost(0.0)
				.inputTokens(0)
				.outputTokens(0)
				.model(options.getModel() != null ? options.getModel() : "unknown")
				.build())
			.usage(Usage.builder().inputTokens(0).outputTokens(0).thinkingTokens(0).build())
			.durationMs(0)
			.apiDurationMs(0)
			.sessionId("unknown")
			.numTurns(1)
			.build();
	}

	/**
	 * Determines the result status based on messages and result data.
	 */
	private static ResultStatus determineStatus(List<Message> messages, Optional<ResultMessage> resultMessage) {
		if (resultMessage.isPresent()) {
			ResultMessage rm = resultMessage.get();
			if (rm.isBudgetExceeded()) {
				return ResultStatus.BUDGET_EXCEEDED;
			}
			if (rm.isError()) {
				return ResultStatus.ERROR;
			}
		}

		// Check if we have any messages at all
		if (messages.isEmpty()) {
			return ResultStatus.ERROR;
		}

		// Check if we have at least one assistant message
		boolean hasAssistantMessage = messages.stream().anyMatch(m -> m instanceof AssistantMessage);

		return hasAssistantMessage ? ResultStatus.SUCCESS : ResultStatus.PARTIAL;
	}

	// ========================================================================
	// Utility Methods
	// ========================================================================

	/**
	 * Checks if the SolonCode CLI is installed and accessible.
	 *
	 * <pre>{@code
	 * if (!Query.isCliInstalled()) {
	 *     System.err.println("SolonCode CLI not found. Install with: npm install -g @anthropic-ai/claude-code");
	 *     return;
	 * }
	 * }</pre>
	 * @return true if 'claude' command is found in PATH
	 */
	public static boolean isCliInstalled() {
		try {
			ProcessBuilder pb = new ProcessBuilder("which", "claude");
			Process p = pb.start();
			return p.waitFor() == 0;
		}
		catch (Exception e) {
			return false;
		}
	}

}