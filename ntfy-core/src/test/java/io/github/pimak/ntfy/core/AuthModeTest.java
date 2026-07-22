package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AuthMode}: the {@code fromCredentials} token-wins-over-basic precedence (with
 * blank/whitespace masking), and the {@code Authorization} header each mode builds. Base64 for Basic
 * auth is asserted both as the exact encoded string and via a UTF-8 decode round-trip so intent is
 * documented, not just the literal encoding.
 */
class AuthModeTest {

  @Test
  void tokenOnly_selectsBearer() {
    AuthMode mode = AuthMode.fromCredentials("tok", null, null);

    assertThat(mode).isInstanceOf(AuthMode.BearerToken.class);
    assertThat(mode.buildHeader()).contains("Bearer tok");
  }

  @Test
  void usernameAndPasswordOnly_selectsBasic() {
    AuthMode mode = AuthMode.fromCredentials(null, "user", "pass");

    assertThat(mode).isInstanceOf(AuthMode.BasicAuth.class);
  }

  @Test
  void tokenAndBasicBothSet_tokenWins() {
    AuthMode mode = AuthMode.fromCredentials("tok", "user", "pass");

    assertThat(mode).isInstanceOf(AuthMode.BearerToken.class);
    assertThat(mode.buildHeader()).contains("Bearer tok");
  }

  @Test
  void blankToken_fallsThroughToBasic() {
    // A whitespace-only token must not select Bearer-with-garbage; isBlank trims first.
    AuthMode mode = AuthMode.fromCredentials("   ", "user", "pass");

    assertThat(mode).isInstanceOf(AuthMode.BasicAuth.class);
  }

  @Test
  void usernameWithoutPassword_selectsNone() {
    assertThat(AuthMode.fromCredentials(null, "user", null))
        .isInstanceOf(AuthMode.None.class);
  }

  @Test
  void passwordWithoutUsername_selectsNone() {
    assertThat(AuthMode.fromCredentials(null, null, "pass"))
        .isInstanceOf(AuthMode.None.class);
  }

  @Test
  void allNull_selectsNone() {
    assertThat(AuthMode.fromCredentials(null, null, null))
        .isInstanceOf(AuthMode.None.class);
  }

  @Test
  void allBlankWhitespace_selectsNone() {
    assertThat(AuthMode.fromCredentials("  ", "  ", "  "))
        .isInstanceOf(AuthMode.None.class);
  }

  @Test
  void basicHeader_isBase64OfColonJoinedCredentials() {
    AuthMode mode = AuthMode.basic("user", "pass");

    String expected =
        "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
    assertThat(mode.buildHeader()).contains(expected);

    // Decode round-trip documents that the payload is exactly "user:pass" in UTF-8.
    String encoded = mode.buildHeader().orElseThrow().substring("Basic ".length());
    String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    assertThat(decoded).isEqualTo("user:pass");
  }

  @Test
  void basicHeader_nonAsciiCredentials_roundTripAsUtf8() {
    AuthMode mode = AuthMode.basic("utilisateur", "mötdePässe😀");

    String encoded = mode.buildHeader().orElseThrow().substring("Basic ".length());
    String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    assertThat(decoded).isEqualTo("utilisateur:mötdePässe😀");
  }

  @Test
  void bearerHeader_carriesTokenVerbatim() {
    assertThat(AuthMode.bearer("abc123").buildHeader()).contains("Bearer abc123");
  }

  @Test
  void noneHeader_isEmpty() {
    assertThat(AuthMode.none().buildHeader()).isEmpty();
  }

  @Test
  void none_returnsSharedSingleton() {
    assertThat(AuthMode.none()).isSameAs(AuthMode.none());
  }
}
