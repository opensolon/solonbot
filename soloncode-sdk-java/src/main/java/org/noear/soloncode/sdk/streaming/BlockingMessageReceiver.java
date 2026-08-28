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

package org.noear.soloncode.sdk.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.exceptions.TransportException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocking implementation of {@link MessageReceiver} following the Spring RestClient
 * streaming POC pattern.
 *
 * <p>
 * This implementation uses a simple blocking queue with no timeout-based polling. The
 * {@link #next()} method blocks indefinitely until a message is available or the stream
 * ends. This design avoids the race conditions inherent in the two-phase hasNext()/next()
 * pattern.
 * </p>
 *
 * <p>
 * Thread-safety: Messages are offered from the transport inbound thread and consumed from
 * the application thread. The blocking queue handles synchronization.
 * </p>
 *
 * @see MessageReceiver
 */
public class BlockingMessageReceiver implements MessageReceiver {

	private static final Logger logger = LoggerFactory.getLogger(BlockingMessageReceiver.class);

	/**
	 * Sentinel value to signal end of stream.
	 */
	private static final ParsedMessage END_OF_STREAM = ParsedMessage.EndOfStream.INSTANCE;

	private final BlockingQueue<ParsedMessage> queue;

	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final AtomicReference<Throwable> error = new AtomicReference<>();

	/**
	 * Creates a receiver with default queue capacity (1000 messages).
	 */
	public BlockingMessageReceiver() {
		this(1000);
	}

	/**
	 * Creates a receiver with the specified queue capacity.
	 * @param queueCapacity maximum number of buffered messages
	 */
	public BlockingMessageReceiver(int queueCapacity) {
		this.queue = new LinkedBlockingQueue<>(queueCapacity);
	}

	/**
	 * Offers a message to the receiver. Called by the transport when a message arrives.
	 * @param message the message to offer
	 * @return true if accepted, false if queue is full or receiver is closed
	 */
	public boolean offer(ParsedMessage message) {
		if (closed.get()) {
			return false;
		}
		return queue.offer(message);
	}

	/**
	 * Signals that the stream has completed normally. No more messages will be offered.
	 */
	public void complete() {
		if (!closed.get()) {
			logger.debug("Stream completed");
			queue.offer(END_OF_STREAM);
		}
	}

	/**
	 * Signals that the stream has failed with an error.
	 * @param throwable the error that caused the failure
	 */
	public void completeWithError(Throwable throwable) {
		if (!closed.get()) {
			logger.debug("Stream completed with error: {}", throwable.getMessage());
			error.set(throwable);
			queue.offer(END_OF_STREAM);
		}
	}

	@Override
	public ParsedMessage next() throws SolonCodeSDKException, InterruptedException {
		if (closed.get()) {
			return null;
		}

		// Block indefinitely until a message is available
		ParsedMessage message = queue.take();

		// Check for end-of-stream sentinel
		if (message == END_OF_STREAM) {
			// Check for error
			Throwable err = error.get();
			if (err != null) {
				if (err instanceof SolonCodeSDKException) {
					SolonCodeSDKException sdkException = (SolonCodeSDKException) err;
					throw sdkException;
				}
				throw new TransportException("Stream failed", err);
			}
			return null;
		}

		return message;
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			logger.debug("Receiver closed");
			queue.clear();
		}
	}

	/**
	 * Checks if the receiver is still active (not closed).
	 * @return true if still accepting messages
	 */
	public boolean isActive() {
		return !closed.get();
	}

	/**
	 * Gets the number of messages currently buffered.
	 * @return buffered message count
	 */
	public int getBufferedCount() {
		return queue.size();
	}

}
