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

package org.noear.soloncode.sdk.parsing;

import org.noear.soloncode.sdk.exceptions.MessageParseException;
import org.noear.soloncode.sdk.types.*;
import org.noear.soloncode.sdk.util.SdkCollections;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses JSON messages from SolonCode CLI output into typed Message objects. Handles the
 * stream JSON format and converts to domain objects.
 */
public class MessageParser {

	private static final Logger logger = LoggerFactory.getLogger(MessageParser.class);

	private final ObjectMapper objectMapper;

	public MessageParser() {
		this.objectMapper = new ObjectMapper();
	}

	/**
	 * Parses a JSON string into a Message object.
	 */
	public Message parseMessage(String json) throws MessageParseException {
		try {
			JsonNode root = objectMapper.readTree(json);
			return parseMessageFromNode(root);
		}
		catch (JsonProcessingException e) {
			throw MessageParseException.jsonDecodeError(json, e);
		}
	}

	/**
	 * Parses a JsonNode into a Message object.
	 */
	public Message parseMessageFromNode(JsonNode node) throws MessageParseException {
		String type = getStringField(node, "type");
		if (type == null) {
			throw new MessageParseException("Missing 'type' field in message");
		}

		if ("user".equals(type)) {
			return parseUserMessage(node);
		}
		else if ("assistant".equals(type)) {
			return parseAssistantMessage(node);
		}
		else if ("system".equals(type)) {
			return parseSystemMessage(node);
		}
		else if ("result".equals(type)) {
			return parseResultMessage(node);
		}
		else if ("error".equals(type)) {
			// run-headless-mode-http.md：/web/run 在异常退出且流中无 result 事件时补发
			// error 事件（HTTP 通道下它是服务端故障的唯一通知）；CLI stream-json 同样可能
			// 出现顶层 error 事件。按 SystemMessage(subtype="error") 投递，向前兼容。
			return parseErrorMessage(node);
		}
		else {
			logger.error(
					"Unrecognized message type '{}' — skipping. "
							+ "This may indicate the SolonCode CLI has added a new message type. " + "Raw JSON: {}",
					type, node);
			return null;
		}
	}

	private UserMessage parseUserMessage(JsonNode node) throws MessageParseException {
		JsonNode messageNode = node.get("message");
		if (messageNode == null) {
			throw new MessageParseException("Missing 'message' field in user message");
		}

		JsonNode contentNode = messageNode.get("content");
		if (contentNode == null) {
			throw new MessageParseException("Missing 'content' field in user message");
		}

		if (contentNode.isTextual()) {
			return UserMessage.of(contentNode.asText());
		}
		else if (contentNode.isArray()) {
			List<ContentBlock> blocks = parseContentBlocks(contentNode);
			return UserMessage.of(blocks);
		}
		else {
			throw new MessageParseException("Invalid content format in user message");
		}
	}

	private AssistantMessage parseAssistantMessage(JsonNode node) throws MessageParseException {
		JsonNode messageNode = node.get("message");
		if (messageNode == null) {
			throw new MessageParseException("Missing 'message' field in assistant message");
		}

		JsonNode contentNode = messageNode.get("content");
		if (contentNode == null || !contentNode.isArray()) {
			throw new MessageParseException("Missing or invalid 'content' field in assistant message");
		}

		List<ContentBlock> blocks = parseContentBlocks(contentNode);
		return AssistantMessage.of(blocks);
	}

	private SystemMessage parseSystemMessage(JsonNode node) throws MessageParseException {
		// System messages have data directly in the root node, not nested under "message"
		String subtype = getStringField(node, "subtype");
		if (subtype == null) {
			throw new MessageParseException("Missing 'subtype' field in system message");
		}

		// Parse all fields as data (excluding type and subtype)
		Map<String, Object> data = parseDataMap(node);
		data.remove("type"); // Remove these metadata fields from data
		data.remove("subtype");

		return SystemMessage.of(subtype, data);
	}

	/**
	 * 顶层 error 事件：{"type":"error","message":...[,"code":...]}，无 subtype 字段。
	 * 以 SystemMessage(subtype="error") 投递，message/code 进 data。
	 */
	private SystemMessage parseErrorMessage(JsonNode node) {
		Map<String, Object> data = parseDataMap(node);
		data.remove("type");
		return SystemMessage.of("error", data);
	}

	private ResultMessage parseResultMessage(JsonNode node) throws MessageParseException {
		// Result messages have data directly in the root node, not nested under "message"
		// soloncode 的 result 事件用 metrics 携带 token/耗时统计（claude 用 usage），两者都兼容。
		JsonNode usageNode = node.get("usage");
		JsonNode metricsNode = node.get("metrics");
		Map<String, Object> usage = parseUsageMap(usageNode != null ? usageNode : metricsNode);

		int durationMs = getIntField(node, "duration_ms", 0);
		if (durationMs == 0 && metricsNode != null && metricsNode.has("duration_ms")) {
			durationMs = metricsNode.get("duration_ms").asInt(0);
		}

		// soloncode 的 result 事件不带 subtype，按 is_error 推导，保证语义与 claude 一致。
		boolean isError = getBooleanField(node, "is_error", false);
		String subtype = getStringField(node, "subtype");
		if (subtype == null) {
			subtype = isError ? "error_during_execution" : "success";
		}

		return ResultMessage.builder()
			.subtype(subtype)
			.durationMs(durationMs)
			.durationApiMs(getIntField(node, "duration_api_ms", 0))
			.isError(isError)
			.numTurns(getIntField(node, "num_turns", 1))
			.sessionId(getStringField(node, "session_id"))
			.totalCostUsd(getDoubleField(node, "total_cost_usd"))
			.usage(usage)
			.result(getStringField(node, "result"))
			.structuredOutput(parseStructuredOutput(node.get("structured_output")))
			.build();
	}

	private Object parseStructuredOutput(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		// Convert JsonNode to native Java types (Map, List, primitives)
		return objectMapper.convertValue(node, Object.class);
	}

	private List<ContentBlock> parseContentBlocks(JsonNode arrayNode) throws MessageParseException {
		List<ContentBlock> blocks = new ArrayList<>();

		for (JsonNode blockNode : arrayNode) {
			String type = getStringField(blockNode, "type");
			if (type == null) {
				throw new MessageParseException("Missing 'type' field in content block");
			}

			ContentBlock block;
			if ("text".equals(type)) {
				block = parseTextBlock(blockNode);
			}
			else if ("tool_use".equals(type)) {
				block = parseToolUseBlock(blockNode);
			}
			else if ("tool_result".equals(type)) {
				block = parseToolResultBlock(blockNode);
			}
			else if ("thinking".equals(type)) {
				block = parseThinkingBlock(blockNode);
			}
			else {
				logger.error("Unrecognized content block type '{}' — skipping", type);
				block = null;
			}

			if (block != null) {
				blocks.add(block);
			}
		}

		return blocks;
	}

	private TextBlock parseTextBlock(JsonNode node) throws MessageParseException {
		String text = getStringField(node, "text");
		if (text == null) {
			throw new MessageParseException("Missing 'text' field in text block");
		}
		return TextBlock.of(text);
	}

	private ThinkingBlock parseThinkingBlock(JsonNode node) {
		String thinking = getStringField(node, "thinking");
		String signature = getStringField(node, "signature");
		return ThinkingBlock.of(thinking != null ? thinking : "", signature);
	}

	private ToolUseBlock parseToolUseBlock(JsonNode node) throws MessageParseException {
		String id = getStringField(node, "id");
		String name = getStringField(node, "name");
		JsonNode inputNode = node.get("input");

		if (id == null || name == null) {
			throw new MessageParseException("Missing required fields in tool_use block");
		}

		Map<String, Object> input = inputNode != null ? parseDataMap(inputNode) : SdkCollections.map();

		return ToolUseBlock.builder().id(id).name(name).input(input).build();
	}

	private ToolResultBlock parseToolResultBlock(JsonNode node) throws MessageParseException {
		String toolUseId = getStringField(node, "tool_use_id");
		if (toolUseId == null) {
			throw new MessageParseException("Missing 'tool_use_id' field in tool_result block");
		}

		JsonNode contentNode = node.get("content");
		Object content = null;
		if (contentNode != null) {
			if (contentNode.isTextual()) {
				content = contentNode.asText();
			}
			else if (contentNode.isArray()) {
				content = parseDataList(contentNode);
			}
		}

		Boolean isError = getBooleanField(node, "is_error");

		ToolResultBlock.Builder builder = ToolResultBlock.builder().toolUseId(toolUseId).isError(isError);

		if (content instanceof String) {
			builder.content((String) content);
		}
		else if (content instanceof List) {
			builder.content((List<Map<String, Object>>) content);
		}

		return builder.build();
	}

	private Map<String, Object> parseDataMap(JsonNode node) {
		Map<String, Object> map = new HashMap<>();
		node.fields().forEachRemaining(entry -> {
			map.put(entry.getKey(), parseJsonValue(entry.getValue()));
		});
		return map;
	}

	private List<Object> parseDataList(JsonNode node) {
		List<Object> list = new ArrayList<>();
		for (JsonNode item : node) {
			list.add(parseJsonValue(item));
		}
		return list;
	}

	private Object parseJsonValue(JsonNode node) {
		if (node.isTextual()) {
			return node.asText();
		}
		else if (node.isNumber()) {
			return node.isInt() ? node.asInt() : node.asDouble();
		}
		else if (node.isBoolean()) {
			return node.asBoolean();
		}
		else if (node.isArray()) {
			return parseDataList(node);
		}
		else if (node.isObject()) {
			return parseDataMap(node);
		}
		else {
			return null;
		}
	}

	private Map<String, Object> parseUsageMap(JsonNode node) {
		return node != null ? parseDataMap(node) : SdkCollections.map();
	}

	// Utility methods for safe field extraction
	private String getStringField(JsonNode node, String fieldName) {
		JsonNode field = node.get(fieldName);
		return field != null && field.isTextual() ? field.asText() : null;
	}

	private int getIntField(JsonNode node, String fieldName, int defaultValue) {
		JsonNode field = node.get(fieldName);
		return field != null && field.isNumber() ? field.asInt() : defaultValue;
	}

	private boolean getBooleanField(JsonNode node, String fieldName, boolean defaultValue) {
		JsonNode field = node.get(fieldName);
		return field != null && field.isBoolean() ? field.asBoolean() : defaultValue;
	}

	private Boolean getBooleanField(JsonNode node, String fieldName) {
		JsonNode field = node.get(fieldName);
		return field != null && field.isBoolean() ? field.asBoolean() : null;
	}

	private Double getDoubleField(JsonNode node, String fieldName) {
		JsonNode field = node.get(fieldName);
		return field != null && field.isNumber() ? field.asDouble() : null;
	}

}