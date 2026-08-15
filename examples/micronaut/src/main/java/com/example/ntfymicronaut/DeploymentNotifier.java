package com.example.ntfymicronaut;

import io.github.pimak.ntfy.core.NtfyClient;
import io.github.pimak.ntfy.core.PublishResult;
import jakarta.inject.Singleton;

/**
 * Manual (non-log) notifications through the module's injectable {@link NtfyClient} bean — the same
 * {@code ntfy.*} endpoint and credentials as the log alerting, no extra configuration.
 * {@code notify(..)} never throws; every outcome comes back as a {@link PublishResult}.
 *
 * <p>The bean is contributed unconditionally by {@code NtfyClientFactory} and closed by the
 * container on shutdown, so nothing here has to manage its lifecycle.
 */
@Singleton
public class DeploymentNotifier {

  private final NtfyClient ntfy;

  public DeploymentNotifier(NtfyClient ntfy) {
    this.ntfy = ntfy;
  }

  /** Announces a finished deployment, e.g. from a startup listener or an ops endpoint. */
  public PublishResult announce(String version) {
    return ntfy.notify("Deployed " + version, "The order service is now running " + version);
  }
}
