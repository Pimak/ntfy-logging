package io.github.pimak.ntfy.quarkus.deployment;

import io.github.pimak.ntfy.quarkus.runtime.NtfyClientProducer;
import io.github.pimak.ntfy.quarkus.runtime.NtfyRecorder;
import io.github.pimak.ntfy.quarkus.runtime.NtfyRuntimeConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LogHandlerBuildItem;

/**
 * Build-time wiring for the ntfy Quarkus extension.
 *
 * <p>Three build steps: advertise the {@code ntfy} feature, install the JUL alert handler via a
 * {@link LogHandlerBuildItem} (recorded at {@link ExecutionTime#RUNTIME_INIT} so the engine's HTTP
 * client and threads are created at boot, keeping the extension native-image-safe), and register
 * the {@link NtfyClientProducer} as a CDI bean so {@code @Inject NtfyClient} resolves.
 *
 * <p>The {@link NtfyRuntimeConfig} run-time config is discovered automatically: it is a {@code
 * @ConfigMapping} + {@code @ConfigRoot(RUN_TIME)} interface, listed in {@code
 * META-INF/quarkus-config-roots.list} by the extension annotation processor, so it can be injected
 * directly as a build-step parameter and handed to the recorder.
 */
class NtfyProcessor {

  private static final String FEATURE = "ntfy";

  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem(FEATURE);
  }

  @BuildStep
  AdditionalBeanBuildItem ntfyClientProducer() {
    return AdditionalBeanBuildItem.unremovableOf(NtfyClientProducer.class);
  }

  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  LogHandlerBuildItem addNtfyHandler(NtfyRecorder recorder, NtfyRuntimeConfig config) {
    return new LogHandlerBuildItem(recorder.create(config));
  }
}
