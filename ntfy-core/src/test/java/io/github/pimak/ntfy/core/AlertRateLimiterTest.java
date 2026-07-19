package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AlertRateLimiter} enforces a burst allowance per window and never silently
 * loses a suppressed count across window rollovers.
 */
class AlertRateLimiterTest {

  @Test
  void burstUnderAllowance_allAcquiresSucceed() {
    AlertRateLimiter limiter = new AlertRateLimiter(3, 180_000);

    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
  }

  @Test
  void burstAboveAllowance_fourthAndBeyondReturnFalse() {
    AlertRateLimiter limiter = new AlertRateLimiter(3, 180_000);

    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isFalse();
    assertThat(limiter.tryAcquire()).isFalse();
  }

  @Test
  void recordSuppressed_incrementsGlobalAndPerLoggerTally() {
    AlertRateLimiter limiter = new AlertRateLimiter(3, 180_000);

    limiter.recordSuppressed("com.foo.Bar");
    limiter.recordSuppressed("com.foo.Bar");
    limiter.recordSuppressed("com.foo.Baz");

    AlertRateLimiter.DigestSnapshot snapshot = limiter.drainAndReset();

    assertThat(snapshot.count()).isEqualTo(3);
    assertThat(snapshot.perLoggerTally()).containsEntry("com.foo.Bar", 2);
    assertThat(snapshot.perLoggerTally()).containsEntry("com.foo.Baz", 1);
  }

  // Binding test: N=10 events, allowance=3 -> exactly 7 suppressed, never lost.
  @Test
  void burstOfTen_withAllowanceThree_suppressesExactlySeven() {
    AlertRateLimiter limiter = new AlertRateLimiter(3, 180_000);

    for (int i = 0; i < 10; i++) {
      if (!limiter.tryAcquire()) {
        limiter.recordSuppressed("com.foo.Bar" + (i % 2));
      }
    }

    AlertRateLimiter.DigestSnapshot snapshot = limiter.drainAndReset();

    assertThat(snapshot.count()).isEqualTo(7);
    int tallySum = snapshot.perLoggerTally().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(tallySum).isEqualTo(7);
  }

  @Test
  void afterDrainAndReset_secondIdenticalBurst_allowsThreeAndSuppressesRemainder() {
    AlertRateLimiter limiter = new AlertRateLimiter(3, 180_000);

    for (int i = 0; i < 10; i++) {
      if (!limiter.tryAcquire()) {
        limiter.recordSuppressed("com.foo.Bar");
      }
    }
    limiter.drainAndReset();

    int acquired = 0;
    int suppressed = 0;
    for (int i = 0; i < 10; i++) {
      if (limiter.tryAcquire()) {
        acquired++;
      } else {
        suppressed++;
        limiter.recordSuppressed("com.foo.Bar");
      }
    }

    assertThat(acquired).isEqualTo(3);
    assertThat(suppressed).isEqualTo(7);
    assertThat(limiter.drainAndReset().count()).isEqualTo(7);
  }

  @Test
  void maxAlertsPerWindowZero_disablesSuppression_alwaysAcquires() {
    AlertRateLimiter limiter = new AlertRateLimiter(0, 180_000);

    for (int i = 0; i < 20; i++) {
      assertThat(limiter.tryAcquire()).isTrue();
    }
    assertThat(limiter.drainAndReset().count()).isZero();
  }

  @Test
  void drainAndResetOnUntouchedLimiter_returnsZeroCountAndEmptyTally() {
    AlertRateLimiter limiter = new AlertRateLimiter(3, 180_000);

    AlertRateLimiter.DigestSnapshot snapshot = limiter.drainAndReset();

    assertThat(snapshot.count()).isZero();
    assertThat(snapshot.perLoggerTally()).isEmpty();
  }

  // Heap-exhaustion guard: dynamically-named loggers (per-tenant/per-connection) during a long
  // ntfy outage must not grow the per-logger tally without bound — beyond 100 distinct names,
  // new loggers fold into a single overflow bucket. The global count stays exact.
  @Test
  void perLoggerTally_capsDistinctLoggers_andFoldsOverflowIntoOtherBucket() {
    AlertRateLimiter limiter = new AlertRateLimiter(3, 180_000);

    for (int i = 0; i < 150; i++) {
      limiter.recordSuppressed("com.dyn.Session" + i);
    }

    AlertRateLimiter.DigestSnapshot snapshot = limiter.drainAndReset();

    assertThat(snapshot.count()).isEqualTo(150);
    // 100 tracked names + the single overflow bucket.
    assertThat(snapshot.perLoggerTally()).hasSize(101);
    assertThat(snapshot.perLoggerTally()).containsEntry("(other loggers)", 50);
  }

  // The failed-digest restore path must respect the same cap: restore() is exactly how the map
  // would otherwise re-grow forever while the ntfy server is unreachable.
  @Test
  void restore_ofOversizedSnapshot_respectsTheSameCap() {
    AlertRateLimiter limiter = new AlertRateLimiter(3, 180_000);
    java.util.Map<String, Integer> bigTally = new java.util.HashMap<>();
    for (int i = 0; i < 150; i++) {
      bigTally.put("com.dyn.Session" + i, 1);
    }

    limiter.restore(new AlertRateLimiter.DigestSnapshot(150, bigTally));
    AlertRateLimiter.DigestSnapshot snapshot = limiter.drainAndReset();

    assertThat(snapshot.count()).isEqualTo(150);
    assertThat(snapshot.perLoggerTally()).hasSize(101);
  }

  // Guard: only drainAndReset() zeroes suppression counters; a window-elapsed rollover inside
  // tryAcquire() must reset ONLY the burst-allowance counter.
  @Test
  void suppressionCount_survivesBurstAllowanceWindowRollover() throws InterruptedException {
    long windowMillis = 50;
    AlertRateLimiter limiter = new AlertRateLimiter(3, windowMillis);

    // Fill the burst allowance, then suppress one event.
    limiter.tryAcquire();
    limiter.tryAcquire();
    limiter.tryAcquire();
    limiter.tryAcquire(); // false, allowance exhausted
    limiter.recordSuppressed("com.foo.Bar");

    // Force the window-elapsed rollover.
    Thread.sleep(windowMillis + 20);
    boolean afterRollover = limiter.tryAcquire();
    assertThat(afterRollover).isTrue(); // burst allowance refilled

    AlertRateLimiter.DigestSnapshot snapshot = limiter.drainAndReset();
    assertThat(snapshot.count()).isEqualTo(1);
  }
}
