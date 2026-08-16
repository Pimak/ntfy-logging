package io.github.pimak.ntfy.core;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared thread-liveness helpers for the engine tests.
 *
 * <p>Two deliberately different predicates, because "did this engine leak a thread?" and "is the JVM
 * quiet enough to hand to the next test class?" are not the same question:
 *
 * <ul>
 *   <li>{@link #anyEngineThreadAlive()} matches only threads the engine itself names
 *       ({@code ntfy-alert-*}). This is what a leak assertion should use — it is unambiguous about
 *       ownership, so it cannot be tripped by an unrelated component's HTTP client.
 *   <li>{@link #awaitQuiesced()} additionally waits on the JDK's
 *       {@code HttpClient-N-SelectorManager} threads. Those carry no owner-specific name, so they
 *       can only ever be waited on, never asserted about: Surefire runs every test class in one JVM,
 *       and a selector thread visible here may belong to any of them.
 * </ul>
 */
final class NtfyThreads {

  private NtfyThreads() {}

  /** True while any thread the engine itself created and named is still alive. */
  static boolean anyEngineThreadAlive() {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(
            t ->
                t.isAlive()
                    && (t.getName().startsWith("ntfy-alert-http")
                        || t.getName().startsWith("ntfy-alert-digest")
                        || t.getName().startsWith("ntfy-alert-delivery")
                        || t.getName().startsWith("ntfy-alert-selftest")));
  }

  /** Waits, bounded, until no engine-owned thread is alive. */
  static void awaitNoEngineThreads() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (anyEngineThreadAlive() && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }
  }

  /**
   * Best-effort wait for engine threads AND every JDK client selector thread to wind down, so a
   * class that tore an engine down mid-request does not hand a still-unwinding exchange to the next
   * class. Deliberately never asserts — see the class javadoc.
   */
  static void awaitQuiesced() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < deadline) {
      if (!anyEngineThreadAlive() && liveSelectorThreads().isEmpty()) {
        return;
      }
      Thread.sleep(25);
    }
  }

  /**
   * The JDK client selector threads alive right now, identified by name (each is uniquely numbered:
   * {@code HttpClient-7-SelectorManager}).
   *
   * <p>Snapshot this BEFORE starting an engine and pass it to {@link
   * #awaitNoNewSelectorThreads(Set)} after stopping it. Comparing against a baseline is what makes
   * the check about the client THIS test created: these threads carry no owner-specific name, so a
   * bare "no selector thread exists anywhere" assertion silently depends on which other test classes
   * happen to have run first in the shared Surefire JVM.
   */
  static Set<String> liveSelectorThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(
            t ->
                t.isAlive()
                    && t.getName().contains("HttpClient-")
                    && t.getName().contains("SelectorManager"))
        .map(Thread::getName)
        .collect(Collectors.toSet());
  }

  /**
   * Waits, bounded, until no selector thread beyond {@code baseline} remains — i.e. until every JDK
   * client created since the snapshot has released its selector thread.
   */
  static void awaitNoNewSelectorThreads(Set<String> baseline) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline && !newSelectorThreads(baseline).isEmpty()) {
      Thread.sleep(25);
    }
  }

  /** Selector threads alive now that were not alive at {@code baseline}. */
  static Set<String> newSelectorThreads(Set<String> baseline) {
    Set<String> now = new HashSet<>(liveSelectorThreads());
    now.removeAll(baseline);
    return now;
  }
}
