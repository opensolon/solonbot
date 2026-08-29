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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.transport.HttpOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端回归：{@link HttpOptions}（代理 / 自定义头）必须穿过 <b>客户端 builder</b> 落到实际连接上。
 *
 * <p>为什么必须有这组测试：既有的 HttpOptions / HttpTransport 单测都是直接 new
 * {@code HttpTransport(...)}，没有一条路径穿过 {@code SolonCodeClient.sync()...build()}。
 * 结果 {@code SyncSpec.build()} 曾漏调 {@code withHttpOptions}，代理与 TLS 配置在
 * 主用路径上被静默丢弃，而全量测试依然全绿。</p>
 *
 * <p>断言方式与 {@code HttpTransportProxyTest} 一致：起两个端口，一个当「代理」一个当
 * 「真实服务」。对 http:// 目标，HttpURLConnection 走代理时以<b>绝对 URI</b> 发请求，
 * 因此代理端口收到 {@code POST http://host:port/web/run} 即证明流量确实经过代理。</p>
 */
class SolonCodeClientHttpOptionsTest {

	private HttpServer proxyServer;

	private HttpServer originServer;

	private String proxyHost;

	private int proxyPort;

	private String originUrl;

	private final List<String> proxyRequestLines = new CopyOnWriteArrayList<>();

	private final List<String> proxyAuthHeaders = new CopyOnWriteArrayList<>();

	private final List<String> tenantHeaders = new CopyOnWriteArrayList<>();

	private final List<String> originHits = new CopyOnWriteArrayList<>();

	@BeforeEach
	void startServers() throws IOException {
		proxyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

		InetSocketAddress proxyAddr = proxyServer.getAddress();
		proxyHost = proxyAddr.getHostName();
		proxyPort = proxyAddr.getPort();
		originUrl = "http://127.0.0.1:" + originServer.getAddress().getPort() + "/web/run";

		proxyServer.createContext("/", exchange -> {
			proxyRequestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			proxyAuthHeaders.add(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
			tenantHeaders.add(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
			readBody(exchange);
			respondWithResultSse(exchange, "via-proxy", "px-client-1");
		});

		originServer.createContext("/", exchange -> {
			originHits.add(exchange.getRequestURI().toString());
			tenantHeaders.add(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
			readBody(exchange);
			respondWithResultSse(exchange, "direct", "direct-1");
		});

		proxyServer.start();
		originServer.start();
	}

	@AfterEach
	void stopServers() {
		proxyServer.stop(0);
		originServer.stop(0);
	}

	private static void respondWithResultSse(HttpExchange exchange, String result, String sessionId) {
		try {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(("data: {\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\""
						+ result + "\",\"session_id\":\"" + sessionId + "\"}\n\n")
						.getBytes(StandardCharsets.UTF_8));
				os.flush();
			}
		}
		catch (Exception ignored) {
			// 连接被提前关闭不影响断言
		}
	}

	private static void readBody(HttpExchange exchange) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (InputStream in = exchange.getRequestBody()) {
			byte[] chunk = new byte[4096];
			while (in.read(chunk) != -1) {
				// 丢弃：只关心请求行与头
			}
		}
		buffer.close();
	}

	/** 跑完一轮：connect + 收满响应，确保连接真正建立过。 */
	private static void runOneTurn(SolonCodeSyncClient client) throws Exception {
		client.connect("hi");
		Iterator<ParsedMessage> response = client.receiveResponse();
		while (response.hasNext()) {
			response.next();
		}
	}

	// ---------- sync()：流式 builder ----------

	@Test
	void syncBuilderAppliesProxyAndCustomHeader() throws Exception {
		HttpOptions options = HttpOptions.proxy(proxyHost, proxyPort)
				.proxyAuth("corp-user", "corp-pass")
				.header("X-Tenant-Id", "t-1024");

		try (SolonCodeSyncClient client = SolonCodeClient.sync()
				.http(originUrl)
				.authToken("tok")
				.workspace("ws1")
				.httpOptions(options)
				.timeout(Duration.ofSeconds(20))
				.build()) {
			runOneTurn(client);
		}

		// 流量经过代理，真实端口零命中
		assertThat(proxyRequestLines).hasSize(1);
		assertThat(proxyRequestLines.get(0)).startsWith("POST http://");
		assertThat(proxyRequestLines.get(0)).endsWith("/web/run");
		assertThat(originHits).isEmpty();

		// 代理认证头与自定义头都落到了实际请求上
		String expectedProxyAuth = "Basic " + java.util.Base64.getEncoder()
				.encodeToString("corp-user:corp-pass".getBytes(StandardCharsets.UTF_8));
		assertThat(proxyAuthHeaders).containsOnly(expectedProxyAuth);
		assertThat(tenantHeaders).containsOnly("t-1024");
	}

	@Test
	void syncBuilderWithoutHttpOptionsConnectsDirectly() throws Exception {
		try (SolonCodeSyncClient client = SolonCodeClient.sync()
				.http(originUrl)
				.authToken("tok")
				.workspace("ws1")
				.timeout(Duration.ofSeconds(20))
				.build()) {
			runOneTurn(client);
		}

		// 反向对照：不配 httpOptions 时不得意外走代理
		assertThat(proxyRequestLines).isEmpty();
		assertThat(originHits).hasSize(1);
		assertThat(tenantHeaders).containsOnlyNulls();
	}

	@Test
	void syncBuilderRejectsHttpOptionsOnStdioTransport() {
		assertThatThrownBy(() -> SolonCodeClient.sync()
				.workingDirectory(Paths.get("."))
				.httpOptions(HttpOptions.proxy("proxy.invalid", 3128))
				.build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("http transport");
	}

	// ---------- sync(CLIOptions)：预置选项 builder ----------

	@Test
	void syncWithOptionsBuilderAppliesProxy() throws Exception {
		try (SolonCodeSyncClient client = SolonCodeClient
				.sync(org.noear.soloncode.sdk.transport.CLIOptions.builder().build())
				.http(originUrl)
				.authToken("tok")
				.workspace("ws1")
				.httpOptions(HttpOptions.proxy(proxyHost, proxyPort))
				.timeout(Duration.ofSeconds(20))
				.build()) {
			runOneTurn(client);
		}

		assertThat(proxyRequestLines).hasSize(1);
		assertThat(originHits).isEmpty();
	}

	// ---------- async()：两条工厂线同样必须落地 ----------

	@Test
	void asyncBuilderAppliesProxy() throws Exception {
		SolonCodeAsyncClient client = SolonCodeClient.async()
				.http(originUrl)
				.authToken("tok")
				.workspace("ws1")
				.httpOptions(HttpOptions.proxy(proxyHost, proxyPort))
				.timeout(Duration.ofSeconds(20))
				.build();
		try {
			client.connect("hi").messages().blockLast(Duration.ofSeconds(20));
		}
		finally {
			client.close().block(Duration.ofSeconds(10));
		}

		assertThat(proxyRequestLines).hasSize(1);
		assertThat(originHits).isEmpty();
	}

}
