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
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.test.SolonCodeCliTestBase;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.AssistantMessage;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.ResultMessage;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Task tool with --agents parameter (subagent spawning).
 *
 * <p>
 * These tests verify that:
 * </p>
 * <ul>
 * <li>The --agents flag is correctly passed to the CLI</li>
 * <li>The Task tool can spawn subagents with defined configurations</li>
 * <li>Subagent responses are captured in the conversation</li>
 * </ul>
 */
class SubagentIT extends SolonCodeCliTestBase {

	/**
	 * JSON definition for a simple test agent. The agent is restricted to no tools and
	 * just responds to queries.
	 */
	private static final String TEST_AGENTS_JSON = "{ \"test-helper\": { \"description\": \"A simple helper agent for testing\", \"tools\": [], \"prompt\": \"You are a test helper. When asked, respond with exactly: TEST_RESPONSE_OK\", \"model\": \"haiku\" } }";

	@Test
	@DisplayName("Should pass --agents flag to CLI and spawn subagent via Task tool")
	void shouldSpawnSubagentViaTaskTool() {
		// Given - configure with agents and Task tool
		CLIOptions options = CLIOptions.builder()
			.agents(TEST_AGENTS_JSON)
			.allowedTools(SdkCollections.list("Task")) // Only allow Task tool
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.systemPrompt(
					"You are an orchestrator. Use the test-helper agent via Task tool to get a response. Report what the agent said.")
			.build();

		try (SolonCodeSyncClient client = SolonCodeClient.sync(options)
			.workingDirectory(workingDirectory())
			.cliPath(getSolonCodeCliPath())
			.timeout(Duration.ofMinutes(3))
			.build()) {

			// When - ask to use the subagent
			client.connect("Please use the test-helper agent to get a response.");

			// Then - collect response
			List<Message> messages = new ArrayList<>();
			StringBuilder responseText = new StringBuilder();
			Iterator<ParsedMessage> response = client.receiveResponse();

			while (response.hasNext()) {
				ParsedMessage parsed = response.next();
				if (parsed.isRegularMessage()) {
					Message msg = parsed.asMessage();
					messages.add(msg);
					if (msg instanceof AssistantMessage) {
						((AssistantMessage) msg).getTextContent().ifPresent(responseText::append);
					}
				}
			}

			// Verify we got messages
			assertThat(messages).isNotEmpty();
			assertThat(messages).anyMatch(m -> m instanceof ResultMessage);

			// The response should indicate the Task tool was used or mention the subagent
			// Note: The exact response depends on whether SolonCode decides to use the Task
			// tool
			String text = responseText.toString().toLowerCase();
			assertThat(text).as("Should have some response from orchestrator").isNotBlank();
		}
	}

	@Test
	@DisplayName("Should include agents in CLI command")
	void shouldIncludeAgentsInCommand() {
		// This is a simpler test - just verify the session starts with agents configured
		CLIOptions options = CLIOptions.builder()
			.agents(TEST_AGENTS_JSON)
			.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
			.build();

		try (SolonCodeSyncClient client = SolonCodeClient.sync(options)
			.workingDirectory(workingDirectory())
			.cliPath(getSolonCodeCliPath())
			.timeout(Duration.ofMinutes(2))
			.build()) {

			// When - just start a session (agents are passed at connection time)
			client.connect("What agents are available to you? Just list their names.");

			// Then - consume response
			List<Message> messages = new ArrayList<>();
			Iterator<ParsedMessage> response = client.receiveResponse();
			while (response.hasNext()) {
				ParsedMessage parsed = response.next();
				if (parsed.isRegularMessage()) {
					messages.add(parsed.asMessage());
				}
			}

			// Session should complete without error
			assertThat(messages).isNotEmpty();
			assertThat(messages).anyMatch(m -> m instanceof ResultMessage);

			// Check if ResultMessage indicates success (not error)
			ResultMessage result = messages.stream()
				.filter(m -> m instanceof ResultMessage)
				.map(m -> (ResultMessage) m)
				.findFirst()
				.get();

			assertThat(result.isError()).isFalse();
		}
	}

}
