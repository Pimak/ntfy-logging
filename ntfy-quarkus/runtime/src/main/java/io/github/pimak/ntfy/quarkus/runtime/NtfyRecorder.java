package io.github.pimak.ntfy.quarkus.runtime;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.Optional;
import java.util.logging.Handler;

/**
 * Records the creation of the ntfy log {@link Handler} at {@code RUNTIME_INIT}. The deployment
 * processor invokes {@link #create} from a {@code @Record(RUNTIME_INIT)} build step and feeds the
 * result to a {@code LogHandlerBuildItem}.
 *
 * <p>Native-image safety hinges on this being RUNTIME_INIT: the {@link AlertEngine} builds its
 * {@code HttpClient}, worker threads, and digest scheduler inside {@link AlertEngine#start()}, which
 * runs here at application boot — never at build time or static-init.
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
   */
  public RuntimeValue<Optional<Handler>> create() {
    NtfyConfig cfg = NtfyConfigFactory.from(config.getValue());
    if (!cfg.isActive()) {
      return new RuntimeValue<>(Optional.empty());
    }
    AlertEngine engine = new AlertEngine(cfg, new JulDiagnostics());
    engine.start();
    return new RuntimeValue<>(Optional.of(new NtfyJulHandler(engine)));
  }
}
