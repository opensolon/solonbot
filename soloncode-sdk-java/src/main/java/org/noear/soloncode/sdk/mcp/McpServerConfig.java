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

package org.noear.soloncode.sdk.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.noear.soloncode.sdk.util.SdkCollections;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP server configuration matching Python SDK types. Supports: stdio, sse, http, sdk
 * (in-process).
 * <p>
 * External servers (stdio, sse, http) are passed to the SolonCode CLI via --mcp-config.
 * In-process SDK servers are managed by the Java SDK and communicate via the mcp_message
 * control protocol.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = McpServerConfig.McpStdioServerConfig.class,
		visible = true)
@JsonSubTypes({ @JsonSubTypes.Type(value = McpServerConfig.McpStdioServerConfig.class, name = "stdio"),
		@JsonSubTypes.Type(value = McpServerConfig.McpSseServerConfig.class, name = "sse"),
		@JsonSubTypes.Type(value = McpServerConfig.McpHttpServerConfig.class, name = "http"),
		@JsonSubTypes.Type(value = McpServerConfig.McpSdkServerConfig.class, name = "sdk") })
public interface McpServerConfig {

	/**
	 * Returns the server type identifier.
	 * @return the type string ("stdio", "sse", "http", or "sdk")
	 */
	String type();

	/**
	 * Returns true if this is an in-process SDK server that requires mcp_message control
	 * protocol handling.
	 * @return true for SDK servers, false for external servers
	 */
	@JsonIgnore
	default boolean isSdkServer() {
		return false;
	}

	/**
	 * Stdio-based MCP server configuration. The server is started as a subprocess with
	 * the specified command and arguments.
	 *
	 * @param command the command to execute (e.g., "npx", "node", "python")
	 * @param args command arguments
	 * @param env environment variables to set for the process
	 */
	public static final class McpStdioServerConfig implements McpServerConfig {

		@JsonProperty("type")
		private final String type;

		@JsonProperty("command")
		private final String command;

		@JsonProperty("args")
		private final List<String> args;

		@JsonProperty("env")
		private final Map<String, String> env;

		public McpStdioServerConfig(@JsonProperty("type") String type, @JsonProperty("command") String command,
				@JsonProperty("args") List<String> args, @JsonProperty("env") Map<String, String> env) {
			this.type = type;
			this.command = command;
			this.args = args;
			this.env = env;
		}

		public McpStdioServerConfig(String command, List<String> args, Map<String, String> env) {
			this("stdio", command, args, env);
		}

		public McpStdioServerConfig(String command, List<String> args) {
			this(command, args, SdkCollections.map());
		}

		public McpStdioServerConfig(String command) {
			this(command, SdkCollections.list(), SdkCollections.map());
		}

		public String type() {
			return "stdio";
		}

		public String command() {
			return command;
		}

		public List<String> args() {
			return args;
		}

		public Map<String, String> env() {
			return env;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpStdioServerConfig)) {
				return false;
			}
			McpStdioServerConfig that = (McpStdioServerConfig) o;
			return Objects.equals(type, that.type) && Objects.equals(command, that.command)
					&& Objects.equals(args, that.args) && Objects.equals(env, that.env);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, command, args, env);
		}

		@Override
		public String toString() {
			return "McpStdioServerConfig[type=" + type + ", command=" + command + ", args=" + args + ", env=" + env
					+ "]";
		}

	}

	/**
	 * Server-Sent Events (SSE) based MCP server configuration. Connects to a remote
	 * server via HTTP SSE transport.
	 *
	 * @param url the SSE endpoint URL
	 * @param headers HTTP headers to include in requests
	 */
	public static final class McpSseServerConfig implements McpServerConfig {

		@JsonProperty("type")
		private final String type;

		@JsonProperty("url")
		private final String url;

		@JsonProperty("headers")
		private final Map<String, String> headers;

		public McpSseServerConfig(@JsonProperty("type") String type, @JsonProperty("url") String url,
				@JsonProperty("headers") Map<String, String> headers) {
			this.type = type;
			this.url = url;
			this.headers = headers;
		}

		public McpSseServerConfig(String url, Map<String, String> headers) {
			this("sse", url, headers);
		}

		public McpSseServerConfig(String url) {
			this(url, SdkCollections.map());
		}

		public String type() {
			return "sse";
		}

		public String url() {
			return url;
		}

		public Map<String, String> headers() {
			return headers;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpSseServerConfig)) {
				return false;
			}
			McpSseServerConfig that = (McpSseServerConfig) o;
			return Objects.equals(type, that.type) && Objects.equals(url, that.url)
					&& Objects.equals(headers, that.headers);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, url, headers);
		}

		@Override
		public String toString() {
			return "McpSseServerConfig[type=" + type + ", url=" + url + ", headers=" + headers + "]";
		}

	}

	/**
	 * HTTP-based MCP server configuration. Connects to a remote server via HTTP
	 * transport.
	 *
	 * @param url the HTTP endpoint URL
	 * @param headers HTTP headers to include in requests
	 */
	public static final class McpHttpServerConfig implements McpServerConfig {

		@JsonProperty("type")
		private final String type;

		@JsonProperty("url")
		private final String url;

		@JsonProperty("headers")
		private final Map<String, String> headers;

		public McpHttpServerConfig(@JsonProperty("type") String type, @JsonProperty("url") String url,
				@JsonProperty("headers") Map<String, String> headers) {
			this.type = type;
			this.url = url;
			this.headers = headers;
		}

		public McpHttpServerConfig(String url, Map<String, String> headers) {
			this("http", url, headers);
		}

		public McpHttpServerConfig(String url) {
			this(url, SdkCollections.map());
		}

		public String type() {
			return "http";
		}

		public String url() {
			return url;
		}

		public Map<String, String> headers() {
			return headers;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpHttpServerConfig)) {
				return false;
			}
			McpHttpServerConfig that = (McpHttpServerConfig) o;
			return Objects.equals(type, that.type) && Objects.equals(url, that.url)
					&& Objects.equals(headers, that.headers);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, url, headers);
		}

		@Override
		public String toString() {
			return "McpHttpServerConfig[type=" + type + ", url=" + url + ", headers=" + headers + "]";
		}

	}

	/**
	 * In-process SDK MCP server configuration. The server is managed by the Java SDK and
	 * communicates via the mcp_message control protocol.
	 * <p>
	 * The instance field is @JsonIgnore because it cannot be serialized to the CLI. Only
	 * the type and name are passed to the CLI; the instance is used internally for
	 * handling mcp_message requests.
	 *
	 * @param name the server name (used in tool naming: mcp__{name}__{tool})
	 * @param instance the MCP server instance (not serialized to CLI)
	 */
	public static final class McpSdkServerConfig implements McpServerConfig {

		@JsonProperty("type")
		private final String type;

		@JsonProperty("name")
		private final String name;

		@JsonIgnore
		private final McpSyncServer instance;

		public McpSdkServerConfig(@JsonProperty("type") String type, @JsonProperty("name") String name,
				McpSyncServer instance) {
			this.type = type;
			this.name = name;
			this.instance = instance;
		}

		public McpSdkServerConfig(String name, McpSyncServer instance) {
			this("sdk", name, instance);
		}

		public String type() {
			return "sdk";
		}

		public String name() {
			return name;
		}

		@JsonIgnore
		public McpSyncServer instance() {
			return instance;
		}

		@Override
		public boolean isSdkServer() {
			return true;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpSdkServerConfig)) {
				return false;
			}
			McpSdkServerConfig that = (McpSdkServerConfig) o;
			return Objects.equals(type, that.type) && Objects.equals(name, that.name)
					&& Objects.equals(instance, that.instance);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, name, instance);
		}

		@Override
		public String toString() {
			return "McpSdkServerConfig[type=" + type + ", name=" + name + ", instance=" + instance + "]";
		}

	}

}
