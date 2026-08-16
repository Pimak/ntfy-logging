package io.github.pimak.ntfy.core;

/**
 * Thrown out of {@code AlertEngine.start()} when the startup self-test fails and {@code
 * startup-ping-fail-fast} is enabled — the deliberate opt-in that turns a broken alerting
 * configuration into a failed deployment rather than a silent one.
 *
 * <p>Never thrown under the default configuration: {@code startup-ping} is {@code off}, and even
 * when it is on, a failure is only warned about unless fail-fast was explicitly requested.
 *
 * <p>The message is fixed text supplied by the localized catalog and carries no HTTP body, no URL
 * and no credential — the same no-leak rule the publisher's exception classification follows, since
 * this message reaches whatever the host application does with a startup failure (a console, a
 * crash reporter, an orchestrator's event log).
 *
 * <p>The engine releases every resource {@code start()} acquired before throwing, so a caller that
 * catches this and continues is not left with a half-initialized engine holding live threads.
 */
public class NtfyStartupSelfTestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NtfyStartupSelfTestException(String message) {
    super(message);
  }
}
