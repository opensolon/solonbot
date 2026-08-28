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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.config.PermissionMode;
import org.noear.soloncode.sdk.hooks.HookRegistry;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.control.HookEvent;
import org.noear.soloncode.sdk.types.control.HookOutput;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SolonCodeClient factory and SolonCodeSyncClient.
 */
class SolonCodeSyncClientTest {

	private Path workingDirectory;

	@BeforeEach
	void setUp() {
		workingDirectory = Paths.get(System.getProperty("user.dir"));
	}

	@Nested
	@DisplayName("SolonCodeClient.sync() Factory Tests")
	class FactoryTests {

		@Test
		@DisplayName("should create SyncSpec from factory")
		void shouldCreateSyncSpec() {
			SolonCodeClient.SyncSpec spec = SolonCodeClient.sync();
			assertThat(spec).isNotNull();
		}

		@Test
		@DisplayName("should build client with required parameters")
		void shouldBuildWithRequiredParams() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThat(client).isNotNull();
			assertThat(client.isConnected()).isFalse();
			client.close();
		}

		@Test
		@DisplayName("should build client with all parameters")
		void shouldBuildWithAllParams() {
			HookRegistry registry = new HookRegistry();

			SolonCodeSyncClient client = SolonCodeClient.sync()
				.workingDirectory(workingDirectory)
				.timeout(Duration.ofMinutes(5))
				.cliPath("/usr/bin/claude")
				.hookRegistry(registry)
				.model("claude-sonnet-4-20250514")
				.systemPrompt("You are a helpful assistant")
				.maxTokens(1000)
				.maxThinkingTokens(500)
				.allowedTools(SdkCollections.list("Read", "Write"))
				.disallowedTools(SdkCollections.list("Bash"))
				.permissionMode(PermissionMode.ACCEPT_EDITS)
				.maxTurns(10)
				.maxBudgetUsd(1.0)
				.build();

			assertThat(client).isNotNull();
			assertThat(client.getServerInfo()).isEmpty();
			client.close();
		}

		@Test
		@DisplayName("should throw when working directory is null")
		void shouldThrowWhenWorkingDirNull() {
			assertThatThrownBy(() -> SolonCodeClient.sync().build()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("workingDirectory");
		}

	}

	@Nested
	@DisplayName("SolonCodeClient.sync(CLIOptions) Factory Tests")
	class FactoryWithOptionsTests {

		@Test
		@DisplayName("should create SyncSpecWithOptions from factory")
		void shouldCreateSyncSpecWithOptions() {
			CLIOptions options = CLIOptions.builder().model("claude-haiku-4-5-20251001").build();

			SolonCodeClient.SyncSpecWithOptions spec = SolonCodeClient.sync(options);
			assertThat(spec).isNotNull();
		}

		@Test
		@DisplayName("should build client with CLIOptions and required parameters")
		void shouldBuildWithCLIOptionsAndRequiredParams() {
			CLIOptions options = CLIOptions.builder()
				.model("claude-haiku-4-5-20251001")
				.systemPrompt("Be concise")
				.build();

			SolonCodeSyncClient client = SolonCodeClient.sync(options).workingDirectory(workingDirectory).build();

			assertThat(client).isNotNull();
			assertThat(client.isConnected()).isFalse();
			client.close();
		}

		@Test
		@DisplayName("should build client with CLIOptions and all session parameters")
		void shouldBuildWithCLIOptionsAndAllSessionParams() {
			CLIOptions options = CLIOptions.builder()
				.model("claude-haiku-4-5-20251001")
				.systemPrompt("Be concise")
				.maxTokens(1000)
				.build();
			HookRegistry registry = new HookRegistry();

			SolonCodeSyncClient client = SolonCodeClient.sync(options)
				.workingDirectory(workingDirectory)
				.timeout(Duration.ofMinutes(5))
				.cliPath("/usr/bin/claude")
				.hookRegistry(registry)
				.build();

			assertThat(client).isNotNull();
			client.close();
		}

		@Test
		@DisplayName("should throw when working directory is null with CLIOptions")
		void shouldThrowWhenWorkingDirNullWithCLIOptions() {
			CLIOptions options = CLIOptions.builder().model("claude-haiku-4-5-20251001").build();

			assertThatThrownBy(() -> SolonCodeClient.sync(options).build()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("workingDirectory");
		}

		@Test
		@DisplayName("SyncSpecWithOptions should not expose CLI option setters")
		void shouldNotExposeCLIOptionSetters() {
			// Verify that SyncSpecWithOptions only has session-level methods
			// by checking it doesn't have model(), systemPrompt(), etc.
			// This is a compile-time guarantee, but we can verify the available methods
			CLIOptions options = CLIOptions.builder().model("claude-haiku-4-5-20251001").build();

			SolonCodeClient.SyncSpecWithOptions spec = SolonCodeClient.sync(options);

			// These methods should exist (session-level)
			assertThat(spec.workingDirectory(workingDirectory)).isSameAs(spec);
			assertThat(spec.timeout(Duration.ofMinutes(5))).isSameAs(spec);
			assertThat(spec.cliPath("/usr/bin/claude")).isSameAs(spec);
			assertThat(spec.hookRegistry(new HookRegistry())).isSameAs(spec);

			// Note: model(), systemPrompt(), etc. don't exist on SyncSpecWithOptions
			// This is enforced at compile time
		}

	}

	@Nested
	@DisplayName("Client State Tests")
	class ClientStateTests {

		@Test
		@DisplayName("should not be connected after creation")
		void shouldNotBeConnectedAfterCreation() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThat(client.isConnected()).isFalse();
			client.close();
		}

		@Test
		@DisplayName("should throw when querying without connection")
		void shouldThrowWhenQueryingWithoutConnection() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThatThrownBy(() -> client.query("test")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not connected");
			client.close();
		}

		@Test
		@DisplayName("should throw when interrupting without connection")
		void shouldThrowWhenInterruptingWithoutConnection() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThatThrownBy(() -> client.interrupt()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not connected");
			client.close();
		}

		@Test
		@DisplayName("should throw when setting permission mode without connection")
		void shouldThrowWhenSettingPermissionModeWithoutConnection() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThatThrownBy(() -> client.setPermissionMode("acceptEdits")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not connected");
			client.close();
		}

		@Test
		@DisplayName("should throw when setting model without connection")
		void shouldThrowWhenSettingModelWithoutConnection() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThatThrownBy(() -> client.setModel("claude-opus-4-20250514"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not connected");
			client.close();
		}

	}

	@Nested
	@DisplayName("Hook Registration Tests")
	class HookRegistrationTests {

		@Test
		@DisplayName("should register hook via DefaultSolonCodeSyncClient")
		void shouldRegisterHook() {
			DefaultSolonCodeSyncClient client = (DefaultSolonCodeSyncClient) SolonCodeClient.sync()
				.workingDirectory(workingDirectory)
				.build();

			client.registerHook(HookEvent.PRE_TOOL_USE, "Bash", input -> HookOutput.allow());

			// No exception thrown = success
			client.close();
		}

		@Test
		@DisplayName("should support fluent hook registration")
		void shouldSupportFluentRegistration() {
			DefaultSolonCodeSyncClient client = (DefaultSolonCodeSyncClient) SolonCodeClient.sync()
				.workingDirectory(workingDirectory)
				.build();

			DefaultSolonCodeSyncClient result = client
				.registerHook(HookEvent.PRE_TOOL_USE, "Bash", input -> HookOutput.allow())
				.registerHook(HookEvent.POST_TOOL_USE, "Edit", input -> HookOutput.allow());

			assertThat(result).isSameAs(client);
			client.close();
		}

	}

	@Nested
	@DisplayName("Close Tests")
	class CloseTests {

		@Test
		@DisplayName("should be idempotent on close")
		void shouldBeIdempotentOnClose() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			// Multiple closes should not throw
			client.close();
			client.close();
			client.close();
		}

		@Test
		@DisplayName("disconnect should be alias for close")
		void disconnectShouldAliasClose() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			client.disconnect();
			assertThat(client.isConnected()).isFalse();
		}

	}

	@Nested
	@DisplayName("Server Info Tests")
	class ServerInfoTests {

		@Test
		@DisplayName("should return empty server info when not connected")
		void shouldReturnEmptyServerInfoWhenNotConnected() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThat(client.getServerInfo()).isEmpty();
			client.close();
		}

	}

	@Nested
	@DisplayName("Tool Permission Callback Tests")
	class ToolPermissionCallbackTests {

		@Test
		@DisplayName("should get null callback by default")
		void shouldGetNullCallbackByDefault() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThat(client.getToolPermissionCallback()).isNull();
			client.close();
		}

		@Test
		@DisplayName("should set and get tool permission callback")
		void shouldSetAndGetCallback() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			client.setToolPermissionCallback((toolName, input, context) -> {
				return org.noear.soloncode.sdk.permission.PermissionResult.allow();
			});

			assertThat(client.getToolPermissionCallback()).isNotNull();
			client.close();
		}

	}

	@Nested
	@DisplayName("Convenience Method Tests")
	class ConvenienceMethodTests {

		@Test
		@DisplayName("messages should throw when not connected")
		void messagesShouldThrowWhenNotConnected() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThatThrownBy(() -> client.messages()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not connected");
			client.close();
		}

		@Test
		@DisplayName("queryAndReceive should throw when not connected")
		void queryAndReceiveShouldThrowWhenNotConnected() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThatThrownBy(() -> client.queryAndReceive("test")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not connected");
			client.close();
		}

		@Test
		@DisplayName("queryText should throw when not connected")
		void queryTextShouldThrowWhenNotConnected() {
			SolonCodeSyncClient client = SolonCodeClient.sync().workingDirectory(workingDirectory).build();

			assertThatThrownBy(() -> client.queryText("test")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not connected");
			client.close();
		}

	}

}
