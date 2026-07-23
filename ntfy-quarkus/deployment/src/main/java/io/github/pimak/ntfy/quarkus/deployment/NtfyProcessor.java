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
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;

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
 * META-INF/quarkus-config-roots.list} by the extension annotation processor. Quarkus injects it
 * through the {@link NtfyRecorder} constructor — a {@code RUN_TIME} config root can no longer be
 * consumed directly as a {@code @BuildStep} parameter (Quarkus 3.19+).
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

  /**
   * Registers the {@code AlertMessages} {@link java.util.ResourceBundle} so a native image ships the
   * translated {@code .properties} bundles. Without this, native builds silently keep only the
   * English base (the bundle-loading reflection is invisible to the static analysis). This step
   * registers only the bundle's base name and picks up every shipped locale automatically — adding a
   * language never requires touching it; only {@code ntfy-core}'s {@code resource-config.json}
   * {@code locales} list needs the new entry.
   */
  @BuildStep
  NativeImageResourceBundleBuildItem alertMessagesBundle() {
    return new NativeImageResourceBundleBuildItem("io.github.pimak.ntfy.core.AlertMessages");
  }

  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  LogHandlerBuildItem addNtfyHandler(NtfyRecorder recorder) {
    return new LogHandlerBuildItem(recorder.create());
  }
}
