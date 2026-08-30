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

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.Message;
import org.noear.soloncode.sdk.types.QueryResult;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link SolonCodeRequestDesc} 默认实现：把「客户端怎么建」交给工厂，自己只负责
 * 一轮对话的执行与收束（阻塞聚合 or 真流式下发）。
 */
class DefaultSolonCodeRequestDesc implements SolonCodeRequestDesc {

	/** 客户端工厂：由入口决定通道（stdio / http）与选项 */
	interface ClientFactory {

		SolonCodeSyncClient create(QueryOptions options) throws SolonCodeSDKException;

	}

	private final String prompt;

	private final ClientFactory clientFactory;

	private QueryOptions options = QueryOptions.defaults();

	DefaultSolonCodeRequestDesc(String prompt, ClientFactory clientFactory) {
		if (prompt == null || prompt.trim().isEmpty()) {
			throw new IllegalArgumentException("prompt must not be empty");
		}
		this.prompt = prompt;
		this.clientFactory = clientFactory;
	}

	@Override
	public SolonCodeRequestDesc options(QueryOptions options) {
		this.options = options == null ? QueryOptions.defaults() : options;
		return this;
	}

	@Override
	public QueryResult call() throws SolonCodeSDKException {
		try (SolonCodeSyncClient client = clientFactory.create(options)) {
			client.connect(prompt);

			List<Message> messages = new ArrayList<>();
			Iterator<ParsedMessage> response = client.receiveResponse();
			while (response.hasNext()) {
				ParsedMessage parsed = response.next();
				if (parsed.isRegularMessage()) {
					messages.add(parsed.asMessage());
				}
			}
			// 元信息取「客户端实际生效的选项」而非 requestDesc 上的 QueryOptions：
			// 后者可能与真正传给 CLI 的配置不一致，直接回显会让 metadata 谎报（如 model）。
			// 客户端拿不到生效选项时（自定义实现）退回请求级选项。
			CLIOptions effective = client.getOptions();
			return Query.buildQueryResult(messages, effective != null ? effective : options.toCLIOptions());
		}
		catch (SolonCodeSDKException e) {
			throw e;
		}
		catch (Exception e) {
			throw new SolonCodeSDKException("Failed to execute request", e);
		}
	}

	@Override
	public Flux<Message> stream() {
		// 冷流：每次订阅起一轮新执行；阻塞迭代放到 boundedElastic，不占调用方线程
		return Flux.<Message>create(sink -> {
			AtomicReference<SolonCodeSyncClient> clientRef = new AtomicReference<>();
			AtomicBoolean cancellationRequested = new AtomicBoolean(false);
			AtomicBoolean terminated = new AtomicBoolean(false);
			AtomicBoolean interruptSent = new AtomicBoolean(false);
			AtomicBoolean closeSent = new AtomicBoolean(false);

			Runnable close = () -> {
				SolonCodeSyncClient client = clientRef.get();
				if (client != null && closeSent.compareAndSet(false, true)) {
					closeQuietly(client);
				}
			};
			Runnable interruptAndClose = () -> {
				SolonCodeSyncClient client = clientRef.get();
				if (client != null && interruptSent.compareAndSet(false, true)) {
					try {
						client.interrupt();
					}
					catch (Throwable ignored) {
						// 取消路径不覆盖下游原有的取消信号，随后仍要释放客户端。
					}
				}
				close.run();
			};

			// 不能只在 hasNext() 返回后检查 isCancelled()：真实 HTTP/stdio 迭代器
			// 可能一直阻塞等待下一条消息。取消必须主动 interrupt 并关闭底层通道。
			sink.onCancel(() -> {
				cancellationRequested.set(true);
				if (terminated.compareAndSet(false, true)) {
					interruptAndClose.run();
				}
			});

			SolonCodeSyncClient client = null;
			try {
				client = clientFactory.create(options);
				clientRef.set(client);
				// 取消可能发生在 factory.create() 期间；客户端发布后补发中断。
				if (cancellationRequested.get()) {
					interruptAndClose.run();
					return;
				}

				client.connect(prompt);
				if (cancellationRequested.get()) {
					interruptAndClose.run();
					return;
				}

				Iterator<ParsedMessage> response = client.receiveResponse();
				while (!cancellationRequested.get() && !sink.isCancelled() && response.hasNext()) {
					ParsedMessage parsed = response.next();
					if (parsed.isRegularMessage()) {
						sink.next(parsed.asMessage());
					}
				}

				if (!cancellationRequested.get() && !sink.isCancelled()
						&& terminated.compareAndSet(false, true)) {
					close.run();
					sink.complete();
				}
				else {
					close.run();
				}
			}
			catch (Throwable e) {
				close.run();
				if (!sink.isCancelled()) {
					sink.error(e instanceof SolonCodeSDKException ? e
							: new SolonCodeSDKException("Failed to stream request", e));
				}
			}
			finally {
				close.run();
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private static void closeQuietly(SolonCodeSyncClient client) {
		if (client == null) {
			return;
		}
		try {
			client.close();
		}
		catch (Exception ignored) {
			// 关闭失败不应盖掉正常/异常终止信号
		}
	}

}
