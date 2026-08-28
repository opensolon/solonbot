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

package org.noear.soloncode.sdk.types.control;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Control request wrapper for bidirectional communication with SolonCode CLI. The CLI sends
 * these requests to the SDK for permission checks, hook callbacks, etc.
 */
public final class ControlRequest {

	@JsonProperty("type")
	private final String type;

	@JsonProperty("request_id")
	private final String requestId;

	@JsonProperty("request")
	private final ControlRequestPayload request;

	public ControlRequest(@JsonProperty("type") String type, @JsonProperty("request_id") String requestId,
			@JsonProperty("request") ControlRequestPayload request) {
		this.type = type;
		this.requestId = requestId;
		this.request = request;
	}

	public String type() {
		return type;
	}

	public String requestId() {
		return requestId;
	}

	public ControlRequestPayload request() {
		return request;
	}

	public static final String TYPE = "control_request";

	/**
	 * Check if this is a control request by type.
	 */
	public boolean isControlRequest() {
		return TYPE.equals(type);
	}

	/**
	 * Interface for control request payload types.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "subtype")
	@JsonSubTypes({ @JsonSubTypes.Type(value = InitializeRequest.class, name = "initialize"),
			@JsonSubTypes.Type(value = CanUseToolRequest.class, name = "can_use_tool"),
			@JsonSubTypes.Type(value = HookCallbackRequest.class, name = "hook_callback"),
			@JsonSubTypes.Type(value = InterruptRequest.class, name = "interrupt"),
			@JsonSubTypes.Type(value = SetPermissionModeRequest.class, name = "set_permission_mode"),
			@JsonSubTypes.Type(value = SetModelRequest.class, name = "set_model"),
			@JsonSubTypes.Type(value = McpMessageRequest.class, name = "mcp_message") })
	public interface ControlRequestPayload {

		String subtype();

	}

	/**
	 * Initialize request - sent by SDK to register hooks at startup.
	 */
	public static final class InitializeRequest implements ControlRequestPayload {

		@JsonProperty("hooks")
		private final Map<String, List<HookMatcherConfig>> hooks;

		public InitializeRequest(@JsonProperty("hooks") Map<String, List<HookMatcherConfig>> hooks) {
			this.hooks = hooks;
		}

		public Map<String, List<HookMatcherConfig>> hooks() {
			return hooks;
		}

		@Override
		public String subtype() {
			return "initialize";
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof InitializeRequest)) {
				return false;
			}
			InitializeRequest that = (InitializeRequest) o;
			return Objects.equals(hooks, that.hooks);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hooks);
		}

		@Override
		public String toString() {
			return "InitializeRequest[hooks=" + hooks + "]";
		}
	}

	/**
	 * Hook matcher configuration sent during initialization.
	 */
	public static final class HookMatcherConfig {

		@JsonProperty("matcher")
		private final String matcher;

		@JsonProperty("hookCallbackIds")
		private final List<String> hookCallbackIds;

		@JsonProperty("timeout")
		private final Integer timeout;

		public HookMatcherConfig(@JsonProperty("matcher") String matcher,
				@JsonProperty("hookCallbackIds") List<String> hookCallbackIds,
				@JsonProperty("timeout") Integer timeout) {
			this.matcher = matcher;
			this.hookCallbackIds = hookCallbackIds;
			this.timeout = timeout;
		}

		public String matcher() {
			return matcher;
		}

		public List<String> hookCallbackIds() {
			return hookCallbackIds;
		}

		public Integer timeout() {
			return timeout;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof HookMatcherConfig)) {
				return false;
			}
			HookMatcherConfig that = (HookMatcherConfig) o;
			return Objects.equals(matcher, that.matcher) && Objects.equals(hookCallbackIds, that.hookCallbackIds)
					&& Objects.equals(timeout, that.timeout);
		}

		@Override
		public int hashCode() {
			return Objects.hash(matcher, hookCallbackIds, timeout);
		}

		@Override
		public String toString() {
			return "HookMatcherConfig[matcher=" + matcher + ", hookCallbackIds=" + hookCallbackIds + ", timeout="
					+ timeout + "]";
		}
	}

	/**
	 * Permission request - CLI asks SDK if a tool can be used.
	 */
	public static final class CanUseToolRequest implements ControlRequestPayload {

		@JsonProperty("tool_name")
		private final String toolName;

		@JsonProperty("input")
		private final Map<String, Object> input;

		@JsonProperty("permission_suggestions")
		private final List<Map<String, Object>> permissionSuggestions;

		@JsonProperty("blocked_path")
		private final String blockedPath;

		public CanUseToolRequest(@JsonProperty("tool_name") String toolName,
				@JsonProperty("input") Map<String, Object> input,
				@JsonProperty("permission_suggestions") List<Map<String, Object>> permissionSuggestions,
				@JsonProperty("blocked_path") String blockedPath) {
			this.toolName = toolName;
			this.input = input;
			this.permissionSuggestions = permissionSuggestions;
			this.blockedPath = blockedPath;
		}

		public String toolName() {
			return toolName;
		}

		public Map<String, Object> input() {
			return input;
		}

		public List<Map<String, Object>> permissionSuggestions() {
			return permissionSuggestions;
		}

		public String blockedPath() {
			return blockedPath;
		}

		@Override
		public String subtype() {
			return "can_use_tool";
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CanUseToolRequest)) {
				return false;
			}
			CanUseToolRequest that = (CanUseToolRequest) o;
			return Objects.equals(toolName, that.toolName) && Objects.equals(input, that.input)
					&& Objects.equals(permissionSuggestions, that.permissionSuggestions)
					&& Objects.equals(blockedPath, that.blockedPath);
		}

		@Override
		public int hashCode() {
			return Objects.hash(toolName, input, permissionSuggestions, blockedPath);
		}

		@Override
		public String toString() {
			return "CanUseToolRequest[toolName=" + toolName + ", input=" + input + ", permissionSuggestions="
					+ permissionSuggestions + ", blockedPath=" + blockedPath + "]";
		}
	}

	/**
	 * Hook callback request - CLI asks SDK to execute a registered hook.
	 */
	public static final class HookCallbackRequest implements ControlRequestPayload {

		@JsonProperty("callback_id")
		private final String callbackId;

		@JsonProperty("input")
		private final Map<String, Object> input;

		@JsonProperty("tool_use_id")
		private final String toolUseId;

		public HookCallbackRequest(@JsonProperty("callback_id") String callbackId,
				@JsonProperty("input") Map<String, Object> input,
				@JsonProperty("tool_use_id") String toolUseId) {
			this.callbackId = callbackId;
			this.input = input;
			this.toolUseId = toolUseId;
		}

		public String callbackId() {
			return callbackId;
		}

		public Map<String, Object> input() {
			return input;
		}

		public String toolUseId() {
			return toolUseId;
		}

		@Override
		public String subtype() {
			return "hook_callback";
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof HookCallbackRequest)) {
				return false;
			}
			HookCallbackRequest that = (HookCallbackRequest) o;
			return Objects.equals(callbackId, that.callbackId) && Objects.equals(input, that.input)
					&& Objects.equals(toolUseId, that.toolUseId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(callbackId, input, toolUseId);
		}

		@Override
		public String toString() {
			return "HookCallbackRequest[callbackId=" + callbackId + ", input=" + input + ", toolUseId=" + toolUseId
					+ "]";
		}
	}

	/**
	 * Interrupt request - SDK tells CLI to stop execution.
	 */
	public static final class InterruptRequest implements ControlRequestPayload {

		public InterruptRequest() {
		}

		@Override
		public String subtype() {
			return "interrupt";
		}

		@Override
		public boolean equals(Object o) {
			return this == o || o instanceof InterruptRequest;
		}

		@Override
		public int hashCode() {
			return InterruptRequest.class.hashCode();
		}

		@Override
		public String toString() {
			return "InterruptRequest[]";
		}
	}

	/**
	 * Set permission mode request - SDK changes permission mode dynamically.
	 */
	public static final class SetPermissionModeRequest implements ControlRequestPayload {

		@JsonProperty("mode")
		private final String mode;

		public SetPermissionModeRequest(@JsonProperty("mode") String mode) {
			this.mode = mode;
		}

		public String mode() {
			return mode;
		}

		@Override
		public String subtype() {
			return "set_permission_mode";
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SetPermissionModeRequest)) {
				return false;
			}
			SetPermissionModeRequest that = (SetPermissionModeRequest) o;
			return Objects.equals(mode, that.mode);
		}

		@Override
		public int hashCode() {
			return Objects.hash(mode);
		}

		@Override
		public String toString() {
			return "SetPermissionModeRequest[mode=" + mode + "]";
		}
	}

	/**
	 * Set model request - SDK changes model dynamically.
	 */
	public static final class SetModelRequest implements ControlRequestPayload {

		@JsonProperty("model")
		private final String model;

		public SetModelRequest(@JsonProperty("model") String model) {
			this.model = model;
		}

		public String model() {
			return model;
		}

		@Override
		public String subtype() {
			return "set_model";
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SetModelRequest)) {
				return false;
			}
			SetModelRequest that = (SetModelRequest) o;
			return Objects.equals(model, that.model);
		}

		@Override
		public int hashCode() {
			return Objects.hash(model);
		}

		@Override
		public String toString() {
			return "SetModelRequest[model=" + model + "]";
		}
	}

	/**
	 * MCP message request - routes JSON-RPC messages to in-process SDK MCP servers.
	 * <p>
	 * The CLI sends this when it needs to invoke tools on SDK-managed MCP servers. The
	 * message field contains a JSON-RPC 2.0 request (with method, params, id fields).
	 */
	public static final class McpMessageRequest implements ControlRequestPayload {

		@JsonProperty("server_name")
		private final String serverName;

		@JsonProperty("message")
		private final Map<String, Object> message;

		public McpMessageRequest(@JsonProperty("server_name") String serverName,
				@JsonProperty("message") Map<String, Object> message) {
			this.serverName = serverName;
			this.message = message;
		}

		public String serverName() {
			return serverName;
		}

		public Map<String, Object> message() {
			return message;
		}

		@Override
		public String subtype() {
			return "mcp_message";
		}

		/**
		 * Extracts the JSON-RPC method name from the message.
		 * @return the method name, or null if not present
		 */
		public String getMethod() {
			return message != null ? (String) message.get("method") : null;
		}

		/**
		 * Extracts the JSON-RPC request ID from the message.
		 * @return the request ID, or null if not present (notification)
		 */
		public Object getId() {
			return message != null ? message.get("id") : null;
		}

		/**
		 * Extracts the JSON-RPC params from the message.
		 * @return the params map, or null if not present
		 */
		@SuppressWarnings("unchecked")
		public Map<String, Object> getParams() {
			return message != null ? (Map<String, Object>) message.get("params") : null;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof McpMessageRequest)) {
				return false;
			}
			McpMessageRequest that = (McpMessageRequest) o;
			return Objects.equals(serverName, that.serverName) && Objects.equals(message, that.message);
		}

		@Override
		public int hashCode() {
			return Objects.hash(serverName, message);
		}

		@Override
		public String toString() {
			return "McpMessageRequest[serverName=" + serverName + ", message=" + message + "]";
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ControlRequest)) {
			return false;
		}
		ControlRequest that = (ControlRequest) o;
		return Objects.equals(type, that.type) && Objects.equals(requestId, that.requestId)
				&& Objects.equals(request, that.request);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, requestId, request);
	}

	@Override
	public String toString() {
		return "ControlRequest[type=" + type + ", requestId=" + requestId + ", request=" + request + "]";
	}
}
