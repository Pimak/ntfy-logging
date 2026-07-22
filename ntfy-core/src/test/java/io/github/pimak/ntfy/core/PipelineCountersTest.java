package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PipelineCounters}: increments, reads, and the {@link
 * PipelineCounters.Snapshot} triple. Increment methods are package-private, so this in-package test
 * exercises them directly (the engine is the only production caller).
 */
class PipelineCountersTest {

  @Test
  void startsAtZero() {
    PipelineCounters counters = new PipelineCounters();
    assertThat(counters.published()).isZero();
    assertThat(counters.suppressed()).isZero();
    assertThat(counters.failed()).isZero();
  }

  @Test
  void incrementsAreIndependentAndMonotonic() {
    PipelineCounters counters = new PipelineCounters();

    counters.incrementPublished();
    counters.incrementPublished();
    counters.incrementSuppressed();
    counters.incrementFailed();
    counters.incrementFailed();
    counters.incrementFailed();

    assertThat(counters.published()).isEqualTo(2);
    assertThat(counters.suppressed()).isEqualTo(1);
    assertThat(counters.failed()).isEqualTo(3);
  }

  @Test
  void snapshotReflectsCurrentValues() {
    PipelineCounters counters = new PipelineCounters();
    counters.incrementPublished();
    counters.incrementSuppressed();
    counters.incrementSuppressed();

    PipelineCounters.Snapshot snap = counters.snapshot();

    assertThat(snap.published()).isEqualTo(1);
    assertThat(snap.suppressed()).isEqualTo(2);
    assertThat(snap.failed()).isZero();

    // A later increment does not mutate the already-taken snapshot.
    counters.incrementFailed();
    assertThat(snap.failed()).isZero();
    assertThat(counters.failed()).isEqualTo(1);
  }
}
