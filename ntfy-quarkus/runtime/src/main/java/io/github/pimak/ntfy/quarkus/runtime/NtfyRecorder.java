package io.github.pimak.ntfy.quarkus.runtime;

import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.jul.NtfyJulHandler;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.Optional;
import java.util.logging.Handler;

/**
 * Records the creation of the ntfy log {@link Handler} at {@code RUNTIME_INIT}. The deployment
 * processor invokes {@link #create} from a {@code @Record(RUNTIME_INIT)} build step and feeds the
 * result to a {@code LogHandlerBuildItem}. The handler itself is the framework-neutral {@link
 * NtfyJulHandler} from ntfy-jul; this recorder only binds it to Quarkus config and lifecycle.
 *
 * <p>Native-image safety hinges on this being RUNTIME_INIT: the engine builds its {@code
 * HttpClient}, worker threads, and digest scheduler inside {@code AlertEngine.start()}, which
 * {@link NtfyJulHandler#forConfig} invokes here at application boot — never at build time or
 * static-init.
 */
@Recorder
public class NtfyRecorder {

  private final RuntimeValue<NtfyRuntimeConfig> config;

  /**
   * The run-time config is injected through the recorder constructor. Since Quarkus 3.19+ a {@code
   * RUN_TIME} {@code @ConfigRoot} can no longer be consumed directly as a {@code @BuildStep}
   * parameter; it must be received here wrapped in a {@link RuntimeValue}.
   */
  public NtfyRecorder(RuntimeValue<NtfyRuntimeConfig> config) {
    this.config = config;
  }

  /**
   * Builds the ntfy JUL handler from the run-time config. Returns {@link Optional#empty()} when the
   * config is inactive (disabled, or missing the url/topic endpoint), so Quarkus installs no
   * handler at all.
   *
   * <p>Either way the handler's pipeline counters are published to {@link
   * NtfyPipelineCountersHolder} — the live instance when a handler was installed, {@code null}
   * otherwise. That is the only handle the Micrometer binding gets on the handler Quarkus now owns,
   * and clearing it on the inactive path keeps a dev-mode reload from leaving the meters pointed at
   * the previous application's counters.
   */
  public RuntimeValue<Optional<Handler>> create() {
    NtfyConfig cfg = NtfyConfigFactory.from(config.getValue());
    if (!cfg.isActive()) {
      NtfyPipelineCountersHolder.set(null);
      return new RuntimeValue<>(Optional.empty());
    }
    NtfyJulHandler handler = NtfyJulHandler.forConfig(cfg);
    NtfyPipelineCountersHolder.set(handler.counters());
    return new RuntimeValue<>(Optional.of(handler));
  }
}
