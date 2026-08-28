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

package org.noear.soloncode.sdk.types;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for content blocks in SolonCode messages. Corresponds to ContentBlock union
 * type in Python SDK.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
		@JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
		@JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
		@JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking") })
public interface ContentBlock {

	/**
	 * Returns the type of this content block.
	 */
	String getType();

}