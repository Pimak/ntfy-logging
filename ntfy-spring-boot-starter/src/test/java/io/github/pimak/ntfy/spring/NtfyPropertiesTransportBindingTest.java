package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Verifies Spring Boot's relaxed binding maps the transport keys — {@code ntfy.proxy} and the
 * {@code ntfy.truststore-*} trio — onto {@link NtfyProperties}, and that all four stay {@code null}
 * when absent.
 *
 * <p>That null default is the part worth pinning. "Unset" has to mean "do not touch the HTTP
 * client's proxy selector or SSL context at all", because that is what leaves {@code
 * -Dhttps.proxyHost} and {@code -Djavax.net.ssl.trustStore} working exactly as they did before these
 * keys existed. A non-null default here would silently take that over for every existing user.
 */
class NtfyPropertiesTransportBindingTest {

  private static NtfyProperties bind(Map<String, String> props) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(props);
    return new Binder(source)
        .bind("ntfy", NtfyProperties.class)
        .orElseGet(NtfyProperties::new);
  }

  @Test
  void transportKeysBindWithRelaxedKebabCase() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.proxy", "proxy.corp.example.com:3128");
    props.put("ntfy.truststore-path", "/etc/ssl/corp/ca.p12");
    props.put("ntfy.truststore-password", "s3cret");
    props.put("ntfy.truststore-type", "PKCS12");

    NtfyProperties bound = bind(props);

    assertThat(bound.getProxy()).isEqualTo("proxy.corp.example.com:3128");
    assertThat(bound.getTruststorePath()).isEqualTo("/etc/ssl/corp/ca.p12");
    assertThat(bound.getTruststorePassword()).isEqualTo("s3cret");
    assertThat(bound.getTruststoreType()).isEqualTo("PKCS12");
  }

  @Test
  void transportKeysAlsoBindInCamelCase() {
    Map<String, String> props = new HashMap<>();
    props.put("ntfy.truststorePath", "/etc/ssl/corp/ca.crt");
    props.put("ntfy.truststoreType", "PEM");

    NtfyProperties bound = bind(props);

    assertThat(bound.getTruststorePath()).isEqualTo("/etc/ssl/corp/ca.crt");
    assertThat(bound.getTruststoreType()).isEqualTo("PEM");
  }

  @Test
  void transportKeysDefaultToUnsetWhenAbsent() {
    NtfyProperties bound = bind(new HashMap<>());

    assertThat(bound.getProxy()).isNull();
    assertThat(bound.getTruststorePath()).isNull();
    assertThat(bound.getTruststorePassword()).isNull();
    assertThat(bound.getTruststoreType()).isNull();
  }
}
