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

package org.noear.soloncode.sdk.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SolonCodeCliDiscovery utility class.
 *
 * @author Mark Pollack
 */
class SolonCodeCliDiscoveryTest {

	@Test
	@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
	void testGetDiscoveredPath() {
		String discoveredPath = SolonCodeCliDiscovery.getDiscoveredPath();
		System.out.println("SolonCode CLI discovered path: " + discoveredPath);

		if (discoveredPath != null) {
			// getDiscoveredPath() should always return a full, absolute path
			Path cliPath = Paths.get(discoveredPath);
			assertThat(Files.exists(cliPath)).isTrue();
			assertThat(Files.isExecutable(cliPath)).isTrue();
			// Ensure it's an absolute path
			assertThat(cliPath.isAbsolute()).isTrue();
		}
		else {
			System.out.println("SolonCode CLI not found - this may be expected in some environments");
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
	void testIsSolonCodeCliAvailable() {
		// Test if SolonCode CLI is available
		boolean isAvailable = SolonCodeCliDiscovery.isSolonCodeCliAvailable();
		System.out.println("SolonCode CLI is available: " + isAvailable);

		if (isAvailable) {
			// If available, getDiscoveredPath should not return null
			String path = SolonCodeCliDiscovery.getDiscoveredPath();
			assertThat(path).isNotNull();
			System.out.println("SolonCode CLI path: " + path);
		}
	}

	@Test
	void testGetDiscoveredPathWhenNotAvailable() {
		// Clear any system properties that might help discovery
		String originalPath = System.getProperty("soloncode.cli.path");
		try {
			System.clearProperty("soloncode.cli.path");
			// Force rediscovery to clear any cached results
			SolonCodeCliDiscovery.forceRediscovery();

			// If SolonCode is truly not available, this should return null
			// If it's available, that's fine too - we're just testing the method works
			String path = SolonCodeCliDiscovery.getDiscoveredPath();
			// Should not throw - just return null if not found
		}
		finally {
			if (originalPath != null) {
				System.setProperty("soloncode.cli.path", originalPath);
			}
			// Force rediscovery to restore normal state
			SolonCodeCliDiscovery.forceRediscovery();
		}
	}

	@Test
	void testDiscoverSolonCodePathWhenNotAvailable() {
		// Test the exception-throwing discovery method
		String originalPath = System.getProperty("soloncode.cli.path");
		try {
			System.setProperty("soloncode.cli.path", "/nonexistent/soloncode");
			SolonCodeCliDiscovery.forceRediscovery();

			assertThatThrownBy(() -> SolonCodeCliDiscovery.discoverSolonCodePath())
				.isInstanceOf(SolonCodeCliDiscovery.SolonCodeCliNotFoundException.class);
		}
		finally {
			if (originalPath != null) {
				System.setProperty("soloncode.cli.path", originalPath);
			}
			else {
				System.clearProperty("soloncode.cli.path");
			}
			// Force rediscovery to restore normal state
			SolonCodeCliDiscovery.forceRediscovery();
		}
	}

}