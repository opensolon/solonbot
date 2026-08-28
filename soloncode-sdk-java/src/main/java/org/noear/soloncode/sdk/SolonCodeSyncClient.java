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

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.permission.ToolPermissionCallback;
import org.noear.soloncode.sdk.streaming.MessageReceiver;
import org.noear.soloncode.sdk.types.Message;

import java.util.Iterator;
import java.util.Map;

/**
 * Synchronous (blocking) client for multi-turn conversations with SolonCode CLI.
 *
 * <p>
 * This is the blocking counterpart to {@link SolonCodeAsyncClient}. It maintains a
 * persistent connection to the SolonCode CLI process, allowing multiple queries within the
 * same context. SolonCode remembers previous messages in the session.
 * </p>
 *
 * <p>
 * Create instances using the {@link SolonCodeClient} factory:
 * </p>
 * <pre>{@code
 * try (SolonCodeSyncClient client = SolonCodeClient.sync()
 *         .workingDirectory(Path.of("."))
 *         .build()) {
 *
 *     client.connect("My favorite color is blue. Remember this.");
 *     for (Message msg : client.receiveResponse()) {
 *         // Process first response
 *     }
 *
 *     client.query("What is my favorite color?");
 *     for (Message msg : client.receiveResponse()) {
 *         // SolonCode remembers: "blue"
 *     }
 * }
 * }</pre>
 *
 * <p>
 * This interface follows the MCP Java SDK naming convention where {@code McpSyncClient}
 * is the blocking counterpart to {@code McpAsyncClient}.
 * </p>
 *
 * @see SolonCodeClient
 * @see SolonCodeAsyncClient
 */
public interface SolonCodeSyncClient extends AutoCloseable {

	/**
	 * Connects to the SolonCode CLI without an initial prompt. The client is ready for
	 * queries after this call.
	 *
	 * <p>
	 * No user message is sent: the session is started and initialised, and the first
	 * turn is whatever the caller passes to {@link #query(String)}. Use
	 * {@link #connect(String)} to open the conversation with a prompt.
	 * </p>
	 * @throws SolonCodeSDKException if connection fails
	 */
	void connect() throws SolonCodeSDKException;

	/**
	 * Connects to the SolonCode CLI with an initial prompt.
	 * @param initialPrompt the first prompt to send
	 * @throws SolonCodeSDKException if connection fails
	 */
	void connect(String initialPrompt) throws SolonCodeSDKException;

	/**
	 * Sends a follow-up query in the existing session context. The query will be
	 * processed in the context of previous messages.
	 * @param prompt the prompt to send
	 * @throws SolonCodeSDKException if sending fails
	 */
	void query(String prompt) throws SolonCodeSDKException;

	/**
	 * Sends a follow-up query with a specific session ID.
	 * @param prompt the prompt to send
	 * @param sessionId the session ID to use
	 * @throws SolonCodeSDKException if sending fails
	 */
	void query(String prompt, String sessionId) throws SolonCodeSDKException;

	/**
	 * Returns an iterator over all messages from the CLI. This iterator yields messages
	 * indefinitely until the session ends.
	 * @return iterator over parsed messages
	 */
	Iterator<ParsedMessage> receiveMessages();

	/**
	 * Returns an iterator that yields messages until a ResultMessage is received. This is
	 * useful for processing a single response before sending another query.
	 * @return iterator over parsed messages, stops after ResultMessage
	 */
	Iterator<ParsedMessage> receiveResponse();

	// ========== Convenience Methods for Elegant Multi-Turn ==========

	/**
	 * Returns an iterable of messages from the current response. This is a convenience
	 * wrapper around {@link #receiveResponse()} that filters to regular messages and
	 * unwraps them, enabling for-each loop usage.
	 *
	 * <p>
	 * Example:
	 * </p>
	 * <pre>{@code
	 * client.connect("Hello");
	 * for (Message msg : client.messages()) {
	 *     System.out.println(msg);
	 * }
	 * }</pre>
	 * @return iterable of messages, stops after ResultMessage
	 */
	Iterable<Message> messages();

	/**
	 * Connects with initial prompt and returns an iterable of response messages. This
	 * combines {@link #connect(String)} and {@link #messages()} for concise multi-turn
	 * conversations.
	 *
	 * <p>
	 * Example:
	 * </p>
	 * <pre>{@code
	 * for (Message msg : client.connectAndReceive("My name is Alice")) {
	 *     System.out.println(msg);
	 * }
	 * }</pre>
	 * @param prompt the initial prompt to send
	 * @return iterable of response messages
	 */
	Iterable<Message> connectAndReceive(String prompt);

	/**
	 * Sends a query and returns an iterable of response messages. This combines
	 * {@link #query(String)} and {@link #messages()} for concise multi-turn
	 * conversations.
	 *
	 * <p>
	 * Example:
	 * </p>
	 * <pre>{@code
	 * client.connect("My name is Alice");
	 * for (Message msg : client.messages()) { ... }
	 *
	 * for (Message msg : client.queryAndReceive("What's my name?")) {
	 *     System.out.println(msg);  // SolonCode remembers: "Alice"
	 * }
	 * }</pre>
	 * @param prompt the follow-up prompt
	 * @return iterable of response messages
	 */
	Iterable<Message> queryAndReceive(String prompt);

	// ========== Text-Only Convenience Methods (80% Use Case) ==========

	/**
	 * Connects with initial prompt and returns just the text response. This is the
	 * simplest way to get SolonCode's answer as a string.
	 *
	 * <p>
	 * Example:
	 * </p>
	 * <pre>{@code
	 * String answer = client.connectText("What is 2+2?");
	 * System.out.println(answer);  // "4"
	 * }</pre>
	 * @param prompt the initial prompt
	 * @return concatenated text from all AssistantMessages
	 */
	String connectText(String prompt);

	/**
	 * Sends a query and returns just the text response. Use this for follow-up questions
	 * when you only need the text content.
	 *
	 * <p>
	 * Example:
	 * </p>
	 * <pre>{@code
	 * client.connectText("My name is Alice");
	 * String answer = client.queryText("What's my name?");
	 * System.out.println(answer);  // "Alice"
	 * }</pre>
	 * @param prompt the follow-up prompt
	 * @return concatenated text from all AssistantMessages
	 */
	String queryText(String prompt);

	/**
	 * Returns a message receiver for all messages from the CLI. The receiver yields
	 * messages indefinitely until the session ends.
	 *
	 * <p>
	 * Usage:
	 * </p>
	 * <pre>{@code
	 * try (MessageReceiver receiver = client.messageReceiver()) {
	 *     ParsedMessage msg;
	 *     while ((msg = receiver.next()) != null) {
	 *         handleMessage(msg);
	 *     }
	 * }
	 * }</pre>
	 * @return message receiver that yields all messages
	 */
	MessageReceiver messageReceiver();

	/**
	 * Returns a message receiver that yields messages until a ResultMessage is received.
	 * This is useful for processing a single response before sending another query.
	 *
	 * <p>
	 * Usage:
	 * </p>
	 * <pre>{@code
	 * client.query("What is 2+2?");
	 * try (MessageReceiver receiver = client.responseReceiver()) {
	 *     ParsedMessage msg;
	 *     while ((msg = receiver.next()) != null) {
	 *         handleMessage(msg);
	 *     }
	 * }
	 * // Can now send another query
	 * }</pre>
	 * @return message receiver that stops after ResultMessage
	 */
	MessageReceiver responseReceiver();

	/**
	 * Interrupts the current operation. Sends an interrupt signal to the CLI to stop the
	 * current processing.
	 * @throws SolonCodeSDKException if interrupt fails
	 */
	void interrupt() throws SolonCodeSDKException;

	/**
	 * Changes the permission mode mid-session.
	 * @param mode the new permission mode (e.g., "default", "acceptEdits", "plan")
	 * @throws SolonCodeSDKException if setting mode fails
	 */
	void setPermissionMode(String mode) throws SolonCodeSDKException;

	/**
	 * Changes the model mid-session.
	 * @param model the new model name (e.g., "sonnet")
	 * @throws SolonCodeSDKException if setting model fails
	 */
	void setModel(String model) throws SolonCodeSDKException;

	/**
	 * Returns information about the server/CLI from initialization.
	 * @return map of server information, or empty map if not available
	 */
	Map<String, Object> getServerInfo();

	/**
	 * Gets the current model being used by this client. This reflects any runtime changes
	 * made via {@link #setModel(String)}.
	 * @return the current model ID, or null if not explicitly set
	 */
	String getCurrentModel();

	/**
	 * Gets the current permission mode for this client. This reflects any runtime changes
	 * made via {@link #setPermissionMode(String)}.
	 * @return the current permission mode, or null if not explicitly set
	 */
	String getCurrentPermissionMode();

	/**
	 * Sets a callback to handle tool permission requests. When SolonCode attempts to use a
	 * tool, this callback is invoked to determine whether the tool should be allowed and
	 * optionally modify the tool's input.
	 *
	 * <p>
	 * Example:
	 * </p>
	 *
	 * <pre>{@code
	 * client.setToolPermissionCallback((toolName, input, context) -> {
	 *     if (toolName.equals("Bash") && input.get("command").toString().contains("rm")) {
	 *         return PermissionResult.deny("Dangerous command blocked");
	 *     }
	 *     return PermissionResult.allow();
	 * });
	 * }</pre>
	 * @param callback the callback to handle permission requests, or null to use default
	 * (allow all)
	 */
	void setToolPermissionCallback(ToolPermissionCallback callback);

	/**
	 * Gets the current tool permission callback.
	 * @return the current callback, or null if using default behavior
	 */
	ToolPermissionCallback getToolPermissionCallback();

	/**
	 * Checks if the client is currently connected.
	 * @return true if connected and ready for queries
	 */
	boolean isConnected();

	/**
	 * Disconnects the client and releases resources. This is an alias for
	 * {@link #close()} for API clarity.
	 */
	void disconnect();

	/**
	 * Closes the client and releases all resources. After calling this method, the client
	 * cannot be reused.
	 */
	@Override
	void close();

}
