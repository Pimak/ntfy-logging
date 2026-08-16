package io.github.pimak.ntfy.quarkus.runtime;

import io.github.pimak.ntfy.core.PipelineCounters;

/**
 * The one link between the log-handler half of the extension and the metrics half: a holder for the
 * {@link PipelineCounters} of the currently-installed ntfy log handler.
 *
 * <p>A holder is needed because the two halves are installed by different Quarkus mechanisms at
 * different times and neither can inject the other. The handler is created by {@link NtfyRecorder}
 * at {@code RUNTIME_INIT} and handed to a {@code LogHandlerBuildItem} — Quarkus owns it from then
 * on and exposes no way to get it back — while the Micrometer binding is a CDI bean created later,
 * when the container starts. Publishing the counters through this holder lets {@link
 * NtfyMeterBinder} read them <em>lazily at scrape time</em> without either half depending on the
 * other's lifecycle.
 *
 * <p><strong>Static state, deliberately.</strong> Quarkus logging is configured before CDI exists,
 * so there is no bean the recorder could write to. The field is {@code volatile}: it is written
 * once on the boot thread and read from arbitrary meter-scrape threads. It is {@code null} until a
 * handler is installed (and stays {@code null} when the config is inactive), which the binder maps
 * to a meter value of {@code 0}.
 *
 * <p><strong>Native-image safety.</strong> The field is only ever written from the recorder, i.e.
 * at application boot, never during static initialization. The value captured in the image heap is
 * therefore always {@code null}, and the live counters are wired in at run time exactly as they are
 * on the JVM.
 *
 * <p>In dev mode and in tests the JVM outlives a single application, so {@link #set} is called
 * again on every boot — including with {@code null} when a reload lands on an inactive config, so
 * the meters fall back to {@code 0} rather than reporting the previous application's tallies.
 */
final class NtfyPipelineCountersHolder {

  private static volatile PipelineCounters current;

  private NtfyPipelineCountersHolder() {}

  /**
   * Publishes the counters of the handler just installed, or {@code null} when no handler was
   * installed for this boot.
   */
  static void set(PipelineCounters counters) {
    current = counters;
  }

  /** The current handler's counters, or {@code null} when no handler is installed. */
  static PipelineCounters get() {
    return current;
  }
}
