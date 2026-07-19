package io.github.pimak.ntfy.quarkus.runtime;

import io.github.pimak.ntfy.core.NtfyClient;
import io.github.pimak.ntfy.core.NtfyConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Exposes a generic, application-scoped {@link NtfyClient} for CDI injection, so Quarkus apps can
 * {@code @Inject NtfyClient} to send arbitrary notifications independent of the log-alert path.
 *
 * <p>The client is produced even for an inactive config: {@link NtfyClient#notify} never throws and
 * simply returns a failure {@code PublishResult} when the endpoint is unset, which is friendlier
 * than failing bean resolution at startup for applications that only use the log-handler feature.
 */
@Singleton
public class NtfyClientProducer {

  /** Produces the singleton {@link NtfyClient} built from the run-time ntfy config. */
  @Produces
  @ApplicationScoped
  public NtfyClient ntfyClient(NtfyRuntimeConfig config) {
    NtfyConfig cfg = NtfyConfigFactory.from(config);
    return new NtfyClient(cfg);
  }

  /** Releases the client's underlying HTTP resources when the application shuts down. */
  public void closeNtfyClient(@Disposes NtfyClient client) {
    client.close();
  }
}
