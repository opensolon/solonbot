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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Objects;

/**
 * Control response wrapper for bidirectional communication with SolonCode CLI. The SDK sends
 * these responses back to the CLI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ControlResponse {

	@JsonProperty("type")
	private final String type;

	@JsonProperty("response")
	private final ResponsePayload response;

	public ControlResponse(@JsonProperty("type") String type, @JsonProperty("response") ResponsePayload response) {
		this.type = type;
		this.response = response;
	}

	public String type() {
		return type;
	}

	public ResponsePayload response() {
		return response;
	}

	public static final String TYPE = "control_response";

	/**
	 * Create a success response.
	 */
	public static ControlResponse success(String requestId, Object responseData) {
		return new ControlResponse(TYPE, new SuccessPayload("success", requestId, responseData));
	}

	/**
	 * Create an error response.
	 */
	public static ControlResponse error(String requestId, String errorMessage) {
		return new ControlResponse(TYPE, new ErrorPayload("error", requestId, errorMessage));
	}

	/**
	 * Interface for response payload types.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "subtype")
	@JsonSubTypes({ @JsonSubTypes.Type(value = SuccessPayload.class, name = "success"),
			@JsonSubTypes.Type(value = ErrorPayload.class, name = "error") })
	public interface ResponsePayload {

		String subtype();

		String requestId();

	}

	/**
	 * Success response payload.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SuccessPayload implements ResponsePayload {

		@JsonProperty("subtype")
		private final String subtype;

		@JsonProperty("request_id")
		private final String requestId;

		@JsonProperty("response")
		private final Object response;

		public SuccessPayload(@JsonProperty("subtype") String subtype, @JsonProperty("request_id") String requestId,
				@JsonProperty("response") Object response) {
			this.subtype = subtype;
			this.requestId = requestId;
			this.response = response;
		}

		@Override
		public String subtype() {
			return subtype;
		}

		@Override
		public String requestId() {
			return requestId;
		}

		public Object response() {
			return response;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SuccessPayload)) {
				return false;
			}
			SuccessPayload that = (SuccessPayload) o;
			return Objects.equals(subtype, that.subtype) && Objects.equals(requestId, that.requestId)
					&& Objects.equals(response, that.response);
		}

		@Override
		public int hashCode() {
			return Objects.hash(subtype, requestId, response);
		}

		@Override
		public String toString() {
			return "SuccessPayload[subtype=" + subtype + ", requestId=" + requestId + ", response=" + response + "]";
		}
	}

	/**
	 * Error response payload.
	 */
	public static final class ErrorPayload implements ResponsePayload {

		@JsonProperty("subtype")
		private final String subtype;

		@JsonProperty("request_id")
		private final String requestId;

		@JsonProperty("error")
		private final String error;

		public ErrorPayload(@JsonProperty("subtype") String subtype, @JsonProperty("request_id") String requestId,
				@JsonProperty("error") String error) {
			this.subtype = subtype;
			this.requestId = requestId;
			this.error = error;
		}

		@Override
		public String subtype() {
			return subtype;
		}

		@Override
		public String requestId() {
			return requestId;
		}

		public String error() {
			return error;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ErrorPayload)) {
				return false;
			}
			ErrorPayload that = (ErrorPayload) o;
			return Objects.equals(subtype, that.subtype) && Objects.equals(requestId, that.requestId)
					&& Objects.equals(error, that.error);
		}

		@Override
		public int hashCode() {
			return Objects.hash(subtype, requestId, error);
		}

		@Override
		public String toString() {
			return "ErrorPayload[subtype=" + subtype + ", requestId=" + requestId + ", error=" + error + "]";
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ControlResponse)) {
			return false;
		}
		ControlResponse that = (ControlResponse) o;
		return Objects.equals(type, that.type) && Objects.equals(response, that.response);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, response);
	}

	@Override
	public String toString() {
		return "ControlResponse[type=" + type + ", response=" + response + "]";
	}
}
