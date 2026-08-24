package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * What {@code <cache>} and {@code <firebase>} put on the wire from a {@code logback.xml}.
 *
 * <p>The reason this suite exists at the adapter level rather than only in core: Joran converts an
 * XML value with {@code Boolean.valueOf}, which maps everything that is not {@code "true"} to
 * false. Declared as booleans, {@code <cache>yes</cache>} would therefore have sent {@code Cache:
 * no} — turning caching OFF for an operator asking to turn it on, and costing every offline
 * subscriber their alerts without a word. On this surface the value is typed by hand, so it is
 * exactly where that misreading would happen.
 */
@WireMockTest
class LogbackAlertAppenderDeliveryPolicyIT {

  private static LoggedRequest publishOnce(
      WireMockRuntimeInfo wm, Consumer<LogbackAlertAppender> tune) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wm.getHttpPort());
    appender.setTopic("alerts");
    appender.setAsync(false);
    tune.accept(appender);

    appender.start();
    appender.doAppend(errorEvent());
    appender.stop();

    List<LoggedRequest> published = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(published).hasSize(1);
    return published.get(0);
  }

  private static LoggingEvent errorEvent() {
    LoggerContext context = new LoggerContext();
    LoggingEvent event =
        new LoggingEvent(
            "com.example.Service", context.getLogger("com.example.Service"), Level.ERROR, "boom",
            null, null);
    event.setTimeStamp(System.currentTimeMillis());
    return event;
  }

  @Test
  void cacheYes_leavesCachingOn(WireMockRuntimeInfo wm) {
    // The regression this whole change is about. "yes" means on; it must not send Cache: no.
    LoggedRequest request = publishOnce(wm, a -> a.setCache("yes"));

    assertThat(request.containsHeader("Cache")).isFalse();
  }

  @Test
  void cacheNo_turnsCachingOff(WireMockRuntimeInfo wm) {
    assertThat(publishOnce(wm, a -> a.setCache("no")).getHeader("Cache")).isEqualTo("no");
  }

  @Test
  void cacheFalse_turnsCachingOff(WireMockRuntimeInfo wm) {
    assertThat(publishOnce(wm, a -> a.setCache("false")).getHeader("Cache")).isEqualTo("no");
  }

  @Test
  void firebaseOff_stopsForwardingWithoutTouchingCache(WireMockRuntimeInfo wm) {
    // The mixed combination a self-hosted server with no FCM wants, expressed the way an operator
    // would actually type it.
    LoggedRequest request = publishOnce(wm, a -> a.setFirebase("off"));

    assertThat(request.getHeader("Firebase")).isEqualTo("no");
    assertThat(request.containsHeader("Cache")).isFalse();
  }

  @Test
  void anUnreadableValueKeepsTheDefaultRatherThanGuessing(WireMockRuntimeInfo wm) {
    // Boolean.valueOf would have read this as false and disabled caching. Keeping the default is
    // the only safe reading, and the engine warns about it at start().
    LoggedRequest request = publishOnce(wm, a -> a.setCache("maybe"));

    assertThat(request.containsHeader("Cache")).isFalse();
  }

  @Test
  void unsetSendsNeitherHeader(WireMockRuntimeInfo wm) {
    LoggedRequest request = publishOnce(wm, a -> {});

    assertThat(request.containsHeader("Cache")).isFalse();
    assertThat(request.containsHeader("Firebase")).isFalse();
  }
}
