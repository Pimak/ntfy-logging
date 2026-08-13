package com.example.ntfycore;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ntfytestkit.LoopbackNtfyServer;
import io.github.pimak.ntfy.core.AlertEngine;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Proves the synchronous-vs-asynchronous delivery difference on the example's {@link AlertEngine}
 * usage, deterministically: the loopback server holds its response open, so a synchronous {@code
 * submit(..)} is observably still blocked once the request has arrived, while an asynchronous one
 * has already returned.
 */
class CoreEngineSyncVsAsyncIT {

  @Test
  void syncSubmitBlocksTheCallingThreadUntilTheServerResponds() throws Exception {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      server.holdFirstResponses(1);
      AlertEngine engine =
          new AlertEngine(
              CoreExampleApp.syncConfig(server.url(), "alerts"),
              new CoreExampleApp.StderrDiagnostics());
      engine.start();
      try {
        CompletableFuture<Void> submission =
            CompletableFuture.runAsync(() -> CoreExampleApp.reportCheckoutFailure(engine));

        // The request has reached the server but its response is held: a synchronous submit is
        // necessarily still inside HttpClient.send() on the submitting thread.
        server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(submission).isNotDone();

        server.releaseHeldResponses();
        submission.get(5, TimeUnit.SECONDS);
        assertThat(engine.counters().published()).isEqualTo(1);
      } finally {
        engine.stop();
      }
    }
  }

  @Test
  void asyncSubmitReturnsImmediatelyAndAWorkerDeliversInTheBackground()
      throws InterruptedException, ExecutionException, TimeoutException {
    try (LoopbackNtfyServer server = new LoopbackNtfyServer()) {
      server.holdFirstResponses(1);
      AlertEngine engine =
          new AlertEngine(
              CoreExampleApp.asyncConfig(server.url(), "alerts"),
              new CoreExampleApp.StderrDiagnostics());
      engine.start();
      try {
        // Returns while the server has not answered anything yet — the blocking send happens on
        // the ntfy-alert-delivery daemon worker, never on this thread.
        CoreExampleApp.reportCheckoutFailure(engine);

        LoopbackNtfyServer.ReceivedRequest request =
            server.awaitRequest(0, Duration.ofSeconds(5));
        assertThat(request.path()).isEqualTo("/alerts");

        server.releaseHeldResponses();
        awaitPublished(engine, Duration.ofSeconds(5));
        assertThat(engine.counters().published()).isEqualTo(1);
      } finally {
        engine.stop();
      }
    }
  }

  private static void awaitPublished(AlertEngine engine, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (engine.counters().published() == 0 && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(25);
    }
  }
}
