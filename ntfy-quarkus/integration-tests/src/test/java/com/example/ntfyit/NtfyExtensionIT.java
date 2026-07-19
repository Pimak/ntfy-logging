package com.example.ntfyit;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs the {@link NtfyExtensionJvmTest} assertions against the PACKAGED artifact rather than the
 * in-JVM app: a fast-jar under plain {@code ./mvnw verify}, and the native binary under {@code
 * ./mvnw verify -Dnative}. The loopback ntfy stand-in (wired by the inherited {@code
 * @QuarkusTestResource}) runs in the test JVM and is reached by the launched process over loopback,
 * so the same publish-received assertion holds end-to-end.
 *
 * <p>This is the native smoke: with {@code -Dnative} the extension is proven to install its log
 * handler and reach an https-capable HTTP client from a GraalVM native image.
 */
@QuarkusIntegrationTest
class NtfyExtensionIT extends NtfyExtensionJvmTest {}
