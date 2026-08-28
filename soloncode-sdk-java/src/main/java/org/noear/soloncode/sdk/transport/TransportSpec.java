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

package org.noear.soloncode.sdk.transport;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 通讯通道声明：回答「用哪条通道连到 soloncode 无头执行端」。
 *
 * <p>与「可执行文件在哪」（{@link #stdio(String)} 的参数）是两个不同维度的问题：
 * 通道是必选项（有默认值），可执行文件路径只是 stdio 通道下的可选微调。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * TransportSpec.stdio()                              // 默认：本机子进程，自动发现 CLI
 * TransportSpec.stdio("/usr/local/bin/soloncode")    // 本机子进程，指定可执行文件
 * }</pre>
 *
 * <p>客户端每轮执行都会调用 {@link #create(Path, Duration)} 新建一个传输实例——
 * {@code soloncode run} 是一次性语义，传输实例不可复用。</p>
 *
 * <h2>HTTP 通道（规划中）</h2>
 * <p>服务端 {@code /web/run} 端点落地后，这里会增加 {@code http(String url)} 工厂，
 * 返回携带 URL 与凭证的 spec。届时 {@link #create(Path, Duration)} 的
 * {@code workingDirectory} 语义会变化（工作目录在服务端，客户端传的是工作区标识），
 * 详见 {@code soloncode-cli/docs/run-headless-mode-http.md}。</p>
 *
 * @see Transport
 * @see StdioTransport
 */
public abstract class TransportSpec {

	TransportSpec() {
	}

	/**
	 * 本机子进程通道，CLI 可执行文件由 {@link org.noear.soloncode.sdk.config.SolonCodeCliDiscovery}
	 * 自动发现。
	 * @return stdio 通道声明
	 */
	public static TransportSpec stdio() {
		return new StdioSpec(null);
	}

	/**
	 * 本机子进程通道，使用指定的 CLI 可执行文件。
	 * @param cliPath soloncode 可执行文件路径；传 null 等价于 {@link #stdio()}
	 * @return stdio 通道声明
	 */
	public static TransportSpec stdio(String cliPath) {
		return new StdioSpec(cliPath);
	}

	/**
	 * 创建一个传输实例，承载一轮执行。
	 * @param workingDirectory 工作目录
	 * @param timeout 默认超时
	 * @return 新的传输实例
	 */
	public abstract Transport create(Path workingDirectory, Duration timeout);

	/**
	 * 通道描述，用于日志与诊断。
	 * @return 形如 {@code stdio} / {@code stdio(/usr/local/bin/soloncode)} 的描述
	 */
	public abstract String describe();

	@Override
	public String toString() {
		return describe();
	}

	/**
	 * stdio 通道：在本机拉起 {@code soloncode run} 子进程。
	 */
	static final class StdioSpec extends TransportSpec {

		private final String cliPath;

		StdioSpec(String cliPath) {
			this.cliPath = (cliPath != null && !cliPath.trim().isEmpty()) ? cliPath : null;
		}

		/**
		 * 指定的 CLI 可执行文件路径，未指定时为 null（交由自动发现）。
		 * @return 路径或 null
		 */
		String cliPath() {
			return cliPath;
		}

		@Override
		public Transport create(Path workingDirectory, Duration timeout) {
			return new StdioTransport(workingDirectory, timeout, cliPath);
		}

		@Override
		public String describe() {
			return cliPath == null ? "stdio" : "stdio(" + cliPath + ")";
		}

	}

}
