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
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SolonCodeClient.async() factory and SolonCodeAsyncClient.
 */
class SolonCodeAsyncClientTest {

	private Path workingDirectory;

	@BeforeEach
	void setUp() {
		workingDirectory = Paths.get(System.getProperty("user.dir"));
	}

	@Nested
	@DisplayName("SolonCodeClient.async() Factory Tests")
	class FactoryTests {

		@Test
		@DisplayName("should create AsyncSpec from factory")
		void shouldCreateAsyncSpec() {
			SolonCodeClient.AsyncSpec spec = SolonCodeClient.async();
			assertThat(spec).isNotNull();
		}

		@Test
		@DisplayName("should build client with required parameters")
		void shouldBuildWithRequiredParams() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			assertThat(client).isNotNull();
			assertThat(client.isConnected()).isFalse();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("should build client with all parameters")
		void shouldBuildWithAllParams() {
			HookRegistry registry = new HookRegistry();

			SolonCodeAsyncClient client = SolonCodeClient.async()
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

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("should throw when working directory is null")
		void shouldThrowWhenWorkingDirNull() {
			assertThatThrownBy(() -> SolonCodeClient.async().build()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("workingDirectory");
		}

	}

	@Nested
	@DisplayName("SolonCodeClient.async(CLIOptions) Factory Tests")
	class FactoryWithOptionsTests {

		@Test
		@DisplayName("should create AsyncSpecWithOptions from factory")
		void shouldCreateAsyncSpecWithOptions() {
			CLIOptions options = CLIOptions.builder().model("claude-haiku-4-5-20251001").build();

			SolonCodeClient.AsyncSpecWithOptions spec = SolonCodeClient.async(options);
			assertThat(spec).isNotNull();
		}

		@Test
		@DisplayName("should build client with CLIOptions and required parameters")
		void shouldBuildWithCLIOptionsAndRequiredParams() {
			CLIOptions options = CLIOptions.builder()
				.model("claude-haiku-4-5-20251001")
				.systemPrompt("Be concise")
				.build();

			SolonCodeAsyncClient client = SolonCodeClient.async(options).workingDirectory(workingDirectory).build();

			assertThat(client).isNotNull();
			assertThat(client.isConnected()).isFalse();

			StepVerifier.create(client.close()).verifyComplete();
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

			SolonCodeAsyncClient client = SolonCodeClient.async(options)
				.workingDirectory(workingDirectory)
				.timeout(Duration.ofMinutes(5))
				.cliPath("/usr/bin/claude")
				.hookRegistry(registry)
				.build();

			assertThat(client).isNotNull();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("should throw when working directory is null with CLIOptions")
		void shouldThrowWhenWorkingDirNullWithCLIOptions() {
			CLIOptions options = CLIOptions.builder().model("claude-haiku-4-5-20251001").build();

			assertThatThrownBy(() -> SolonCodeClient.async(options).build()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("workingDirectory");
		}

		@Test
		@DisplayName("AsyncSpecWithOptions should not expose CLI option setters")
		void shouldNotExposeCLIOptionSetters() {
			// Verify that AsyncSpecWithOptions only has session-level methods
			// by checking it doesn't have model(), systemPrompt(), etc.
			// This is a compile-time guarantee, but we can verify the available methods
			CLIOptions options = CLIOptions.builder().model("claude-haiku-4-5-20251001").build();

			SolonCodeClient.AsyncSpecWithOptions spec = SolonCodeClient.async(options);

			// These methods should exist (session-level)
			assertThat(spec.workingDirectory(workingDirectory)).isSameAs(spec);
			assertThat(spec.timeout(Duration.ofMinutes(5))).isSameAs(spec);
			assertThat(spec.cliPath("/usr/bin/claude")).isSameAs(spec);
			assertThat(spec.hookRegistry(new HookRegistry())).isSameAs(spec);

			// Note: model(), systemPrompt(), etc. don't exist on AsyncSpecWithOptions
			// This is enforced at compile time
		}

	}

	@Nested
	@DisplayName("Client State Tests")
	class ClientStateTests {

		@Test
		@DisplayName("should not be connected after creation")
		void shouldNotBeConnectedAfterCreation() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			assertThat(client.isConnected()).isFalse();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("should error when querying without connection")
		void shouldErrorWhenQueryingWithoutConnection() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			// TurnSpec is lazy - error happens when we subscribe to a terminal method
			StepVerifier.create(client.query("test").text())
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("should error when interrupting without connection")
		void shouldErrorWhenInterruptingWithoutConnection() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			StepVerifier.create(client.interrupt())
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("should error when setting permission mode without connection")
		void shouldErrorWhenSettingPermissionModeWithoutConnection() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			StepVerifier.create(client.setPermissionMode("acceptEdits"))
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("should error when setting model without connection")
		void shouldErrorWhenSettingModelWithoutConnection() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			StepVerifier.create(client.setModel("claude-opus-4-20250514"))
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			StepVerifier.create(client.close()).verifyComplete();
		}

	}

	@Nested
	@DisplayName("Hook Registration Tests")
	class HookRegistrationTests {

		@Test
		@DisplayName("should register hook via DefaultSolonCodeAsyncClient")
		void shouldRegisterHook() {
			DefaultSolonCodeAsyncClient client = (DefaultSolonCodeAsyncClient) SolonCodeClient.async()
				.workingDirectory(workingDirectory)
				.build();

			client.registerHook(HookEvent.PRE_TOOL_USE, "Bash", input -> HookOutput.allow());

			// No exception thrown = success
			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("should support fluent hook registration")
		void shouldSupportFluentRegistration() {
			DefaultSolonCodeAsyncClient client = (DefaultSolonCodeAsyncClient) SolonCodeClient.async()
				.workingDirectory(workingDirectory)
				.build();

			DefaultSolonCodeAsyncClient result = client
				.registerHook(HookEvent.PRE_TOOL_USE, "Bash", input -> HookOutput.allow())
				.registerHook(HookEvent.POST_TOOL_USE, "Edit", input -> HookOutput.allow());

			assertThat(result).isSameAs(client);

			StepVerifier.create(client.close()).verifyComplete();
		}

	}

	@Nested
	@DisplayName("Close Tests")
	class CloseTests {

		@Test
		@DisplayName("should be idempotent on close")
		void shouldBeIdempotentOnClose() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			// Multiple closes should not error
			StepVerifier.create(client.close()).verifyComplete();
			StepVerifier.create(client.close()).verifyComplete();
			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("disconnect should be alias for close")
		void disconnectShouldAliasClose() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			StepVerifier.create(client.disconnect()).verifyComplete();
			assertThat(client.isConnected()).isFalse();
		}

	}

	@Nested
	@DisplayName("Flux.defer() Regression Tests")
	class FluxDeferRegressionTests {

		@Test
		@DisplayName("receiveResponse should defer connected check until subscription")
		void receiveResponseShouldDeferConnectedCheck() {
			// This test verifies the Flux.defer() fix for the race condition where
			// receiveResponse() is called when building a reactive chain BEFORE
			// connect() completes.
			//
			// Without Flux.defer(), this pattern would fail:
			// client.connect("...").thenMany(client.receiveResponse())
			//
			// Because receiveResponse() is evaluated when the chain is built,
			// not when thenMany subscribes after connect completes.

			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			// Build a Flux from receiveResponse() - this should NOT throw/error yet
			// even though not connected, because of Flux.defer()
			reactor.core.publisher.Flux<?> responseFlux = client.receiveResponse();

			// The Flux should be created successfully (deferred check)
			assertThat(responseFlux).isNotNull();

			// But subscribing should error because we're not connected
			StepVerifier.create(responseFlux)
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("receiveMessages should defer connected check until subscription")
		void receiveMessagesShouldDeferConnectedCheck() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			// Build a Flux from receiveMessages() - should NOT error yet
			reactor.core.publisher.Flux<?> messagesFlux = client.receiveMessages();

			assertThat(messagesFlux).isNotNull();

			// Subscribing should error
			StepVerifier.create(messagesFlux)
				.expectErrorMatches(e -> e instanceof IllegalStateException && e.getMessage().contains("not connected"))
				.verify();

			StepVerifier.create(client.close()).verifyComplete();
		}

	}

	@Nested
	@DisplayName("Server Info Tests")
	class ServerInfoTests {

		@Test
		@DisplayName("should return empty server info when not connected")
		void shouldReturnEmptyServerInfoWhenNotConnected() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			assertThat(client.getServerInfo()).isEmpty();

			StepVerifier.create(client.close()).verifyComplete();
		}

	}

	@Nested
	@DisplayName("Convenience Method Tests")
	class ConvenienceMethodTests {

		@Test
		@DisplayName("connectAndReceive should defer connected check")
		void connectAndReceiveShouldDeferConnectedCheck() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			// Building the Flux should not error (deferred)
			reactor.core.publisher.Flux<?> flux = client.connectAndReceive("test");
			assertThat(flux).isNotNull();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("queryAndReceive should defer connected check")
		void queryAndReceiveShouldDeferConnectedCheck() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			reactor.core.publisher.Flux<?> flux = client.queryAndReceive("test");
			assertThat(flux).isNotNull();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("connectText should defer connected check")
		void connectTextShouldDeferConnectedCheck() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			reactor.core.publisher.Flux<?> flux = client.connectText("test");
			assertThat(flux).isNotNull();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("queryText should defer connected check")
		void queryTextShouldDeferConnectedCheck() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			reactor.core.publisher.Flux<?> flux = client.queryText("test");
			assertThat(flux).isNotNull();

			StepVerifier.create(client.close()).verifyComplete();
		}

	}

	@Nested
	@DisplayName("Tool Permission Callback Tests")
	class ToolPermissionCallbackTests {

		@Test
		@DisplayName("should get null callback by default")
		void shouldGetNullCallbackByDefault() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			assertThat(client.getToolPermissionCallback()).isNull();

			StepVerifier.create(client.close()).verifyComplete();
		}

		@Test
		@DisplayName("should set and get tool permission callback")
		void shouldSetAndGetCallback() {
			SolonCodeAsyncClient client = SolonCodeClient.async().workingDirectory(workingDirectory).build();

			client.setToolPermissionCallback((toolName, input, context) -> {
				return org.noear.soloncode.sdk.permission.PermissionResult.allow();
			});

			assertThat(client.getToolPermissionCallback()).isNotNull();

			StepVerifier.create(client.close()).verifyComplete();
		}

	}

}
