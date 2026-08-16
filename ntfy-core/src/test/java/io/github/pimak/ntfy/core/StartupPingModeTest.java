package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parsing contract for {@link StartupPingMode} and the {@code NtfyConfig.Builder} overload built on
 * it: lenient like every other config parser in the module (a typo must never take down the host
 * application), yet still able to distinguish "unset" from "misspelled" so the typo can be warned
 * about rather than silently read as {@code off}.
 */
class StartupPingModeTest {

  @ParameterizedTest
  @ValueSource(strings = {"off", "OFF", "Off", " off "})
  void parsesOffCaseAndWhitespaceInsensitively(String value) {
    assertThat(StartupPingMode.fromValue(value)).isEqualTo(StartupPingMode.OFF);
  }

  @ParameterizedTest
  @ValueSource(strings = {"probe", "PROBE", "Probe"})
  void parsesProbe(String value) {
    assertThat(StartupPingMode.fromValue(value)).isEqualTo(StartupPingMode.PROBE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"publish", "PUBLISH", "Publish"})
  void parsesPublish(String value) {
    assertThat(StartupPingMode.fromValue(value)).isEqualTo(StartupPingMode.PUBLISH);
  }

  @ParameterizedTest
  @ValueSource(strings = {"prob", "yes", "true", "on", "ping", "publsh"})
  void returnsNullForAnUnrecognizedValue(String value) {
    assertThat(StartupPingMode.fromValue(value)).isNull();
  }

  @Test
  void returnsNullForNullOrBlank() {
    assertThat(StartupPingMode.fromValue(null)).isNull();
    assertThat(StartupPingMode.fromValue("")).isNull();
    assertThat(StartupPingMode.fromValue("   ")).isNull();
  }

  @Test
  void defaultIsOffAndNotFlaggedAsRejected() {
    NtfyConfig config = NtfyConfig.builder().build();

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.OFF);
    assertThat(config.isStartupPingFailFast()).isFalse();
    assertThat(config.isStartupPingValueRejected()).isFalse();
  }

  @Test
  void aValidStringValueBinds() {
    assertThat(NtfyConfig.builder().startupPing("publish").build().getStartupPing())
        .isEqualTo(StartupPingMode.PUBLISH);
  }

  @Test
  void anUnparseableValueKeepsTheDefaultButIsFlaggedForDiagnosis() {
    NtfyConfig config = NtfyConfig.builder().startupPing("prob").build();

    // Lenient like applyInt/applyDuration: one bad character in a config file must not throw.
    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.OFF);
    // ...but the engine still has to be able to tell the operator about it.
    assertThat(config.isStartupPingValueRejected()).isTrue();
  }

  @Test
  void anUnparseableValueDoesNotClobberAnAlreadyValidOne() {
    NtfyConfig config =
        NtfyConfig.builder().startupPing(StartupPingMode.PROBE).startupPing("nonsense").build();

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.PROBE);
    assertThat(config.isStartupPingValueRejected()).isTrue();
  }

  @Test
  void aBlankOrNullStringIsTreatedAsUnset_notAsAnError() {
    assertThat(NtfyConfig.builder().startupPing("  ").build().isStartupPingValueRejected())
        .isFalse();
    assertThat(NtfyConfig.builder().startupPing((String) null).build().isStartupPingValueRejected())
        .isFalse();
  }

  @Test
  void aLaterValidValueClearsTheRejectedFlag() {
    NtfyConfig config = NtfyConfig.builder().startupPing("bogus").startupPing("probe").build();

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.PROBE);
    assertThat(config.isStartupPingValueRejected()).isFalse();
  }

  @Test
  void nullEnumResetsToOff() {
    NtfyConfig config =
        NtfyConfig.builder()
            .startupPing(StartupPingMode.PUBLISH)
            .startupPing((StartupPingMode) null)
            .build();

    assertThat(config.getStartupPing()).isEqualTo(StartupPingMode.OFF);
  }
}
