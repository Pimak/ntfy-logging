package com.example.ntfyspring;

import io.github.pimak.ntfy.core.NtfyClient;
import io.github.pimak.ntfy.core.PublishResult;
import org.springframework.stereotype.Service;

/**
 * Manual (non-log) notifications through the starter's injectable {@link NtfyClient} bean — the
 * same {@code ntfy.*} endpoint and credentials as the log alerting, no extra configuration.
 * {@code notify(..)} never throws; every outcome comes back as a {@link PublishResult}.
 */
@Service
public class DeploymentNotifier {

  private final NtfyClient ntfy;

  public DeploymentNotifier(NtfyClient ntfy) {
    this.ntfy = ntfy;
  }

  /** Announces a finished deployment, e.g. from a startup hook or an ops endpoint. */
  public PublishResult announce(String version) {
    return ntfy.notify("Deployed " + version, "The order service is now running " + version);
  }
}
