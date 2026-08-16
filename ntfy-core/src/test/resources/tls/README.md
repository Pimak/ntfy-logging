# TLS test fixtures

Checked-in key material for the trust-store tests (`NtfyHttpClientsTest`,
`AlertEngineTruststoreIT`). **Test-only, never shipped** — these live under `src/test/resources`, so
they are not part of the `ntfy-core` artifact.

| File | What it is | Password |
|---|---|---|
| `server.p12` | PKCS12 keystore: the test HTTPS server's key + its certificate chain (`CN=localhost`, SAN `dns:localhost,ip:127.0.0.1`), signed by the test CA below. | `changeit` |
| `ca.p12` | PKCS12 **trust** store holding only the test CA certificate. The `truststore-type=PKCS12` fixture. | `changeit` |
| `ca.jks` | The same single CA certificate in a JKS store. The `truststore-type=JKS` fixture. | `changeit` |
| `ca.crt` | The same CA certificate, PEM-encoded. The `truststore-type=PEM` fixture — the shape a corporate CA actually arrives in on Kubernetes (a `ca.crt` key mounted from a ConfigMap). No password. | — |

The password is the literal string `changeit` everywhere. It guards a throwaway self-signed CA that
signs nothing outside this test suite, so it is deliberately not a secret.

## Why they are committed rather than generated

Generating a self-signed X.509 certificate from Java needs `sun.security.x509`, an internal JDK
package that is not open to the module path. The usual alternative is BouncyCastle, and
`ntfy-core`'s `enforce-dependency-allowlist` enforcer execution bans **every** dependency that is not
already on its allow-list, test scope included. Committing the fixtures is what keeps the module's
"pure JDK, zero dependencies" promise intact.

Validity is 100 years (`-validity 36500`) so the suite does not acquire a time bomb.

## Regenerating

Only needed if the fixtures are ever lost or the CA has to be rotated. Run from this directory:

```bash
# 1. The test CA (a real CA cert: basicConstraints ca:true + keyCertSign)
keytool -genkeypair -alias ntfy-test-ca -keyalg RSA -keysize 2048 -validity 36500 \
  -dname "CN=ntfy-logging test CA, OU=tests, O=ntfy-logging" \
  -ext bc:critical=ca:true -ext ku:critical=keyCertSign,cRLSign \
  -keystore ca-key.p12 -storetype PKCS12 -storepass changeit -keypass changeit
keytool -exportcert -alias ntfy-test-ca -keystore ca-key.p12 -storepass changeit -rfc -file ca.crt

# 2. The server key, signed by that CA. The SAN is what lets the client verify
#    the hostname for both https://localhost:PORT and https://127.0.0.1:PORT.
keytool -genkeypair -alias ntfy-test-server -keyalg RSA -keysize 2048 -validity 36500 \
  -dname "CN=localhost, OU=tests, O=ntfy-logging" -ext "san=dns:localhost,ip:127.0.0.1" \
  -keystore server.p12 -storetype PKCS12 -storepass changeit -keypass changeit
keytool -certreq -alias ntfy-test-server -keystore server.p12 -storepass changeit -file server.csr
keytool -gencert -alias ntfy-test-ca -keystore ca-key.p12 -storepass changeit \
  -infile server.csr -outfile server.crt -validity 36500 \
  -ext "san=dns:localhost,ip:127.0.0.1" -rfc

# 3. Install the reply. The CA cert must be in the keystore first, otherwise
#    keytool rejects the reply as an unestablished chain.
keytool -importcert -noprompt -alias ntfy-test-ca -keystore server.p12 -storepass changeit -file ca.crt
cat server.crt ca.crt > chain.crt
keytool -importcert -noprompt -alias ntfy-test-server -keystore server.p12 -storepass changeit -file chain.crt

# 4. The trust stores, in both keystore formats
keytool -importcert -noprompt -alias ntfy-test-ca -keystore ca.p12 -storetype PKCS12 -storepass changeit -file ca.crt
keytool -importcert -noprompt -alias ntfy-test-ca -keystore ca.jks -storetype JKS    -storepass changeit -file ca.crt

# 5. Drop the intermediates — the CA private key must NOT be committed
rm -f server.csr chain.crt server.crt ca-key.p12
```

The suite proves the fixtures are wired correctly in both directions: a client trusting `ca.p12`
completes the handshake against the `server.p12` server, and a client on the JDK default trust store
fails it with `SSLHandshakeException`.
