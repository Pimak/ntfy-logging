package io.github.pimak.ntfy.quarkus.runtime;

import io.github.pimak.ntfy.core.DurationParser;
import io.github.pimak.ntfy.core.NtfyConfig;

/**
 * Translates the Quarkus {@link NtfyRuntimeConfig} mapping into the framework-neutral {@link
 * NtfyConfig} the core engine and client consume. Kept as a single shared helper so the recorder
 * (alert handler path) and the CDI producer (generic client path) build identical configs from the
 * same source of truth.
 */
final class NtfyConfigFactory {

  private NtfyConfigFactory() {}

  /** Builds an immutable {@link NtfyConfig} from the run-time mapping. */
  static NtfyConfig from(NtfyRuntimeConfig config) {
    NtfyConfig.Builder b =
        NtfyConfig.builder()
            .maxStackFrames(config.maxStackFrames())
            .connectTimeout(DurationParser.parse(config.connectTimeout()))
            .requestTimeout(DurationParser.parse(config.requestTimeout()))
            .maxAlertsPerWindow(config.maxAlertsPerWindow())
            .suppressionWindow(DurationParser.parse(config.suppressionWindow()))
            .errorPriority(config.errorPriority())
            .digestPriority(config.digestPriority())
            .errorTags(config.errorTags())
            .digestTags(config.digestTags())
            .enabled(config.enabled())
            .asyncEnabled(config.async())
            .asyncQueueCapacity(config.asyncQueueCapacity())
            .requireHttpsForCredentials(config.requireHttpsForCredentials());

    config.url().ifPresent(b::url);
    config.topic().ifPresent(b::topic);
    config.token().ifPresent(b::token);
    config.username().ifPresent(b::username);
    config.password().ifPresent(b::password);
    config.title().ifPresent(b::title);
    config.appName().ifPresent(b::appName);
    config.clickUrl().ifPresent(b::clickUrl);
    config.actions().ifPresent(b::actionsHeader);
    config.excludedLoggers().ifPresent(b::excludedLoggers);

    return b.build();
  }
}
