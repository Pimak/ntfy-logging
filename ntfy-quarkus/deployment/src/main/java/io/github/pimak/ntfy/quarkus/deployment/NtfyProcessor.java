package io.github.pimak.ntfy.quarkus.deployment;

import io.github.pimak.ntfy.quarkus.runtime.NtfyClientProducer;
import io.github.pimak.ntfy.quarkus.runtime.NtfyRecorder;
import io.github.pimak.ntfy.quarkus.runtime.NtfyRuntimeConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LogHandlerBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

/**
 * Build-time wiring for the ntfy Quarkus extension.
 *
 * <p>The core build steps: advertise the {@code ntfy} feature, install the JUL alert handler via a
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

  /**
   * The Micrometer binding's bean class, referenced by name so this module never loads it — see
   * {@link #ntfyMetricsBinder}.
   */
  private static final String METER_BINDER_CLASS =
      "io.github.pimak.ntfy.quarkus.runtime.NtfyMeterBinder";

  /** The Micrometer type whose presence that binding requires. */
  private static final String METER_REGISTRY_CLASS = "io.micrometer.core.instrument.MeterRegistry";

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

  /**
   * Registers {@code org.jboss.logmanager.ExtLogRecord} for reflection so the {@code
   * include-mdc-keys} feature keeps working in a native image.
   *
   * <p>{@code ntfy-jul}'s {@code ExtLogRecordMdc} reads one allow-listed MDC key at a time through
   * {@code getMethod("getMdc", String.class)} + {@code Method.invoke}, deliberately avoiding any
   * compile-time reference to JBoss LogManager. GraalVM's static analysis therefore cannot see that
   * call, and without this registration the lookup fails at native run time — silently, because the
   * adapter fails closed: alerts would still be published, just with every MDC line missing.
   *
   * <p>{@code ntfy-jul} ships only a <em>conditional</em> ({@code typeReachable}) entry for this
   * class in its own {@code reflect-config.json}, since a plain-JUL native application has no JBoss
   * LogManager at all. In a Quarkus application the class is always present — every log record goes
   * through it — so the registration is made unconditionally here, where that is a fact.
   */
  @BuildStep
  ReflectiveClassBuildItem extLogRecordMdcAccessor() {
    return ReflectiveClassBuildItem.builder("org.jboss.logmanager.ExtLogRecord").methods().build();
  }

  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  LogHandlerBuildItem addNtfyHandler(NtfyRecorder recorder) {
    return new LogHandlerBuildItem(recorder.create());
  }

  /**
   * Registers the Micrometer binding — {@code NtfyMeterBinder}, a CDI {@code MeterBinder} exporting
   * the pipeline counters as {@code ntfy.pipeline.published} / {@code .suppressed} / {@code .failed}
   * — bringing the extension to parity with the Spring Boot starter, which has exported the same
   * three meters all along. The Quarkus Micrometer extension discovers every {@code MeterBinder}
   * bean and applies it to the registry it creates, so this registration is all the wiring needed.
   *
   * <p>The bean is added only when the application really has Micrometer, established by two
   * independent checks:
   *
   * <ul>
   *   <li>{@link Capability#METRICS} — provided by {@code quarkus-micrometer}, i.e. something is
   *       actually creating a {@code MeterRegistry} and collecting {@code MeterBinder}s. Without
   *       it, a bean implementing that interface would never be applied to anything.
   *   <li>{@code MeterRegistry} present at run time — the classpath fact the binder's own bytecode
   *       depends on. The capability alone is a statement about extensions, not about classes.
   * </ul>
   *
   * <p><strong>The binder is named by string, never by class literal.</strong> {@code
   * NtfyMeterBinder} implements {@code io.micrometer.core.instrument.binder.MeterBinder}, so a
   * {@code NtfyMeterBinder.class} constant here would have to resolve that interface at build time
   * and would blow up with {@code NoClassDefFoundError} on a Micrometer-free application — the same
   * hazard the Spring starter isolates behind a nested {@code @ConditionalOnClass} configuration.
   * Naming the class by string keeps this processor, and the deployment module as a whole, free of
   * any Micrometer dependency: nothing loads the binder unless the checks above passed.
   */
  @BuildStep
  void ntfyMetricsBinder(Capabilities capabilities,
      BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
    if (!capabilities.isPresent(Capability.METRICS)
        || !QuarkusClassLoader.isClassPresentAtRuntime(METER_REGISTRY_CLASS)) {
      return;
    }
    additionalBeans.produce(new AdditionalBeanBuildItem.Builder()
        .addBeanClass(METER_BINDER_CLASS)
        .setUnremovable()
        .build());
  }
}
