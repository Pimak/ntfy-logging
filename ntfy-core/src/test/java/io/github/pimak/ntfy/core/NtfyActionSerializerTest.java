package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class NtfyActionSerializerTest {

  @Test
  void nullOrEmpty_serializesToNull() {
    assertThat(NtfyActionSerializer.serialize(null)).isNull();
    assertThat(NtfyActionSerializer.serialize(List.of())).isNull();
  }

  @Test
  void onlyNullElements_serializesToNull() {
    assertThat(NtfyActionSerializer.serialize(Arrays.asList((NtfyAction) null, null))).isNull();
  }

  @Test
  void view_rendersTypeLabelUrl_withInternalSpacesUnquoted() {
    String header =
        NtfyActionSerializer.serialize(
            List.of(NtfyAction.view("View logs", "https://grafana.example.com/d/abc")));
    assertThat(header).isEqualTo("view, View logs, https://grafana.example.com/d/abc");
  }

  @Test
  void view_withClear_appendsClearFlag() {
    String header =
        NtfyActionSerializer.serialize(List.of(NtfyAction.view("Open", "https://x/", true)));
    assertThat(header).isEqualTo("view, Open, https://x/, clear=true");
  }

  @Test
  void label_withComma_isDoubleQuoted() {
    String header =
        NtfyActionSerializer.serialize(List.of(NtfyAction.view("Open, now", "https://x/")));
    assertThat(header).isEqualTo("view, \"Open, now\", https://x/");
  }

  @Test
  void label_withSemicolonQuoteBackslash_isQuotedAndEscaped() {
    String header =
        NtfyActionSerializer.serialize(List.of(NtfyAction.view("a;b\"c\\d", "https://x/")));
    // ; triggers quoting; the inner " and \ are backslash-escaped inside the quotes.
    assertThat(header).isEqualTo("view, \"a;b\\\"c\\\\d\", https://x/");
  }

  @Test
  void label_withLeadingOrTrailingSpace_isQuotedToPreserveIt() {
    String header = NtfyActionSerializer.serialize(List.of(NtfyAction.view(" pad ", "https://x/")));
    assertThat(header).isEqualTo("view, \" pad \", https://x/");
  }

  @Test
  void http_rendersMethodAndBody() {
    String header =
        NtfyActionSerializer.serialize(
            List.of(NtfyAction.http("Ack", "https://api.example.com/ack", "POST", "ok=1")));
    assertThat(header).isEqualTo("http, Ack, https://api.example.com/ack, method=POST, body=ok=1");
  }

  @Test
  void http_withoutMethodOrBody_omitsThoseFields() {
    String header =
        NtfyActionSerializer.serialize(List.of(NtfyAction.http("Ping", "https://x/ping")));
    assertThat(header).isEqualTo("http, Ping, https://x/ping");
  }

  @Test
  void http_withHeaders_rendersHeaderFields() {
    String header =
        NtfyActionSerializer.serialize(
            List.of(
                NtfyAction.http(
                    "Ack",
                    "https://x/ack",
                    "POST",
                    null,
                    new NtfyAction.HttpHeader("Authorization", "Bearer tok"),
                    new NtfyAction.HttpHeader("X-Env", "prod"))));
    assertThat(header)
        .isEqualTo(
            "http, Ack, https://x/ack, method=POST,"
                + " headers.Authorization=Bearer tok, headers.X-Env=prod");
  }

  @Test
  void http_headerValueWithComma_isDoubleQuoted() {
    String header =
        NtfyActionSerializer.serialize(
            List.of(
                NtfyAction.http(
                    "Ack",
                    "https://x/ack",
                    null,
                    null,
                    new NtfyAction.HttpHeader("X-List", "a, b"))));
    assertThat(header).isEqualTo("http, Ack, https://x/ack, headers.X-List=\"a, b\"");
  }

  @Test
  void broadcast_minimal_rendersTypeAndLabel() {
    String header = NtfyActionSerializer.serialize(List.of(NtfyAction.broadcast("Take photo")));
    assertThat(header).isEqualTo("broadcast, Take photo");
  }

  @Test
  void broadcast_withExtras_rendersExtraFields() {
    String header =
        NtfyActionSerializer.serialize(
            List.of(
                NtfyAction.broadcast(
                    "Take photo",
                    new NtfyAction.BroadcastExtra("cmd", "pic"),
                    new NtfyAction.BroadcastExtra("camera", "front"))));
    assertThat(header)
        .isEqualTo("broadcast, Take photo, extras.cmd=pic, extras.camera=front");
  }

  @Test
  void broadcast_withIntentAndClear_appendsBothTrailing() {
    String header =
        NtfyActionSerializer.serialize(
            List.of(
                new NtfyAction.Broadcast(
                    "Run",
                    "com.example.ACTION",
                    List.of(new NtfyAction.BroadcastExtra("k", "v")),
                    true)));
    assertThat(header)
        .isEqualTo("broadcast, Run, extras.k=v, intent=com.example.ACTION, clear=true");
  }

  @Test
  void headerOrExtra_withBlankNameOrValue_throwsAtConstruction() {
    assertThatThrownBy(() -> new NtfyAction.HttpHeader("  ", "v"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NtfyAction.HttpHeader("X", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NtfyAction.BroadcastExtra(null, "v"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NtfyAction.BroadcastExtra("k", "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void multipleActions_joinedBySemicolon() {
    String header =
        NtfyActionSerializer.serialize(
            List.of(
                NtfyAction.view("Logs", "https://logs/"),
                NtfyAction.http("Ack", "https://ack/", "PUT", null)));
    assertThat(header).isEqualTo("view, Logs, https://logs/; http, Ack, https://ack/, method=PUT");
  }

  @Test
  void moreThanThreeActions_cappedAtThree_neverThrows() {
    String header =
        NtfyActionSerializer.serialize(
            List.of(
                NtfyAction.view("1", "https://1/"),
                NtfyAction.view("2", "https://2/"),
                NtfyAction.view("3", "https://3/"),
                NtfyAction.view("4", "https://4/")));
    assertThat(header)
        .isEqualTo("view, 1, https://1/; view, 2, https://2/; view, 3, https://3/");
  }

  @Test
  void action_withBlankLabelOrUrl_throwsAtConstruction() {
    assertThatThrownBy(() -> NtfyAction.view("  ", "https://x/"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NtfyAction.view("Label", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NtfyAction.http("Label", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
