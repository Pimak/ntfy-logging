package io.github.pimak.ntfy.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies the conditional wiring of the Micrometer binding without booting a full application:
 *
 * <ul>
 *   <li>Micrometer absent from the classpath — the context still starts cleanly and no {@link
 *       NtfyMetricsBinder} bean is created (proves the outer auto-config never hard-references a
 *       Micrometer type).
 *   <li>Micrometer present but no {@code MeterRegistry} bean — the binder is still absent
 *       ({@code @ConditionalOnBean}).
 *   <li>Micrometer present with a {@code MeterRegistry} bean — the binder is created.
 * </ul>
 */
class NtfyMetricsBinderConditionalTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NtfyAutoConfiguration.class))
          // Keep the appender install cheap and offline: blank url/topic means no appender is
          // installed, which is irrelevant to the binder's conditional wiring under test here.
          .withPropertyValues("ntfy.enabled=true");

  @Test
  void micrometerAbsent_contextStartsAndNoBinderBean() {
    runner
        .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(NtfyMetricsBinder.class);
        });
  }

  @Test
  void micrometerPresentButNoRegistryBean_noBinderBean() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(NtfyMetricsBinder.class);
    });
  }

  @Test
  void micrometerPresentWithRegistryBean_binderCreated() {
    runner.withUserConfiguration(RegistryConfig.class).run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(NtfyMetricsBinder.class);
    });
  }

  @Configuration(proxyBeanMethods = false)
  static class RegistryConfig {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
