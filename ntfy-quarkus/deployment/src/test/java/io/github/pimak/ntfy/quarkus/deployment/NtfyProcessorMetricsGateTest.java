package io.github.pimak.ntfy.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The negative half of the Micrometer wiring: without the {@code io.quarkus.metrics} capability the
 * build step must produce nothing, so an application with no Micrometer extension never gets a bean
 * whose class implements a {@code MeterBinder} interface it cannot load.
 *
 * <p>Driven directly rather than through {@link io.quarkus.test.QuarkusUnitTest}: every test in this
 * module shares one classpath, and {@link NtfyMetricsBindingTest} has to put Micrometer on it. That
 * makes "Micrometer absent" unreachable from a booted application here — but the build step is plain
 * Java, so calling it is both possible and exact.
 *
 * <p>Only the negative cases live here. The positive one — capability present, bean registered —
 * needs the step's second check, {@code QuarkusClassLoader.isClassPresentAtRuntime}, which by
 * design refuses to answer outside a Quarkus build; {@link NtfyMetricsBindingTest} covers it where
 * it belongs, inside a real one. Both cases below short-circuit on the capability check before
 * reaching it, which is exactly the property under test: no Micrometer capability, no work done.
 */
class NtfyProcessorMetricsGateTest {

  private final List<AdditionalBeanBuildItem> produced = new ArrayList<>();
  private final BuildProducer<AdditionalBeanBuildItem> producer = produced::add;

  @Test
  void withoutTheMetricsCapability_noBeanIsRegistered() {
    new NtfyProcessor().ntfyMetricsBinder(new Capabilities(Set.of()), producer);

    assertThat(produced).isEmpty();
  }

  /**
   * A neighbouring metrics capability must not open the gate — only {@code io.quarkus.metrics} does.
   *
   * <p>Named by string rather than through {@code Capability.OPENTELEMETRY_METRICS}: that constant
   * does not exist in older Quarkus (3.15 has {@code SMALLRYE_METRICS} where newer releases have the
   * OpenTelemetry one), and referencing it would make this test — and therefore the whole deployment
   * module — fail to compile against a Quarkus the extension itself supports perfectly well. A
   * capability IS a string; the constant is only a convenience.
   */
  @Test
  void otherCapabilitiesDoNotEnableTheBinding() {
    new NtfyProcessor()
        .ntfyMetricsBinder(new Capabilities(Set.of("io.quarkus.opentelemetry.metrics")), producer);

    assertThat(produced).isEmpty();
  }
}
