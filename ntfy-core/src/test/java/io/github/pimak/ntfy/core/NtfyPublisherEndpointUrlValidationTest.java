package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Direct unit tests for {@link NtfyPublisher#isValidEndpointUrl(String)}. A malformed / non-http(s)
 * endpoint URL can never produce a successful publish, so the engine must refuse activation rather
 * than manufacture a per-event failure for every alert. These pins the accept/reject contract —
 * including the getAuthority()-not-getHost() choice that keeps the documented {@code
 * user:pass@host} basic-auth form and underscore hostnames working — independently of the engine
 * wiring.
 */
class NtfyPublisherEndpointUrlValidationTest {

  @ParameterizedTest
  @CsvSource({
    // Accept: valid http(s) endpoints, including the forms URI.getHost() cannot see.
    "'https://ntfy.sh', true",
    "'http://ntfy.sh', true",
    "'https://host.example.com/ntfy', true", // reverse-proxy path
    "'http://ntfy.internal:8080', true", // non-standard port
    "'https://ntfy.sh/', true", // trailing slash (publisher normalizes)
    "'https://ntfy.sh///', true", // multiple trailing slashes
    "'https://user:p@ss@ntfy.sh/x', true", // userinfo, unencoded '@' -> getHost() null
    "'http://host_underscore/x', true", // underscore host -> getHost() null
    "'HTTPS://ntfy.sh', true", // scheme is case-insensitive
    // Reject: no scheme, wrong scheme, unparseable, no authority, whitespace-padded.
    "'ntfy.sh', false",
    "'ftp://host', false",
    "'not a url', false",
    "'https://', false",
    "' https://ntfy.sh', false", // leading whitespace -> unparseable, no trim by design
    "'https://ntfy.sh ', false", // trailing whitespace
    "'mailto:x@y.com', false",
    "'file:///etc/hosts', false",
  })
  void endpointUrlContract(String url, boolean expected) {
    assertThat(NtfyPublisher.isValidEndpointUrl(url)).isEqualTo(expected);
  }

  @Test
  void nullAndBlankAreRejected() {
    assertThat(NtfyPublisher.isValidEndpointUrl(null)).isFalse();
    assertThat(NtfyPublisher.isValidEndpointUrl("")).isFalse();
    assertThat(NtfyPublisher.isValidEndpointUrl("   ")).isFalse();
  }

  @Test
  void ipv6HostIsAccepted() {
    assertThat(NtfyPublisher.isValidEndpointUrl("https://[::1]:8443/x")).isTrue();
  }
}
