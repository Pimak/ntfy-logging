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

  /**
   * Builds the ntfy JUL handler from the run-time config. Returns {@link Optional#empty()} when the
   * config is inactive (disabled, or missing the url/topic endpoint), so Quarkus installs no
   * handler at all.
   */
  public RuntimeValue<Optional<Handler>> create(NtfyRuntimeConfig config) {
    NtfyConfig cfg = NtfyConfigFactory.from(config);
    if (!cfg.isActive()) {
      return new RuntimeValue<>(Optional.empty());
    }
    AlertEngine engine = new AlertEngine(cfg, new JulDiagnostics());
    engine.start();
    return new RuntimeValue<>(Optional.of(new NtfyJulHandler(engine)));
  }
}
