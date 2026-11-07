# Per-origin client certificates (mTLS)

Some gateways ask the client for a certificate as well as the API key. Hermes Vox can present
one, chosen by you from Android's credential store, **per gateway origin**. This page states
what that means, what it sends and to whom, and what it does not protect.

Code: `android/app/src/main/java/com/hermesvox/core/MtlsConfig.kt`,
`MtlsKeyManager.kt`, `MtlsKeyProvider.kt`, `AndroidKeyChainProvider.kt`.

## One certificate per origin

An **origin** is a host and a port: `gateway.tailnet.ts.net:443`,
`10.1.2.3:8642`, `[2001:db8::1]:8642`. The choice is stored under that origin
(`mtls_alias_<host>:<port>` in the `hv` preferences) and the client certificate is offered
only to a connection to that origin.

- The host is normalised once (`MtlsConfig.normaliseHost`): ASCII, lower case, no IPv6
  brackets, no trailing root dot, IPv6 in compressed form. The same function normalises the
  peer a socket reports, so an IPv6 or IDN gateway cannot silently fail to match itself.
- Changing the address in Settings → Entity & Connection → **Entity** does **not** carry the
  certificate to the new origin. Save clears it there and the row says *"Address changed —
  choose the certificate again"*. An alias is only ever written by an explicit choice made
  on that origin (`MtlsConfig.saveAlias`).
- It is one device setting. It is **not** per profile: the profile editor does not show the
  row (a certificate per profile is a separate feature; see Follow-ups).

## How to choose one

1. Install the certificate in Android: **Settings → Security → Encryption & credentials →
   Install a certificate**, or let your MDM do it. The private key then lives in the
   Android keystore, never in this app.
2. In Hermes Vox: **Settings → Entity & Connection → Entity → Client certificate → Choose**.
   Android's own picker lists the certificates with a private key; pick one.
3. The row shows what you are approving: the certificate's subject and expiry
   (`CN=gateway-client,O=… · expires 2027-04-01`), not the KeyChain alias — an alias is an
   opaque store key, and approving one is not consent to anything you can check.
4. **Clear** removes the choice for that origin.

The app never exports, serialises, logs or copies the private key. It holds a
`java.security.PrivateKey` handle obtained from `KeyChain.getPrivateKey` for the duration of a
handshake, and only for the alias you chose.

## What it sends, and to whom

On each HTTPS connection whose origin has a chosen certificate, `Entity.open` attaches an
`SSLSocketFactory` built from a `MtlsKeyManager` scoped to that origin. The manager answers
`chooseClientAlias` only when the socket's peer is that origin — same host (normalised) and
same port. The certificate chain and key are then fetched through the `MtlsKeyProvider`
(KeyChain on a device) on the TLS handshake thread.

- **Server verification is untouched.** `SSLContext.init(keyManagers, null, null)` passes a
  **null TrustManager**, which resolves to Android's platform trust manager — so the system
  store and the `network_security_config.xml` rules (including the `ts.net` user-CA entry)
  apply exactly as they do without a client certificate. There is no custom
  `X509TrustManager` and no `HostnameVerifier` anywhere in this app.
- **A redirect to another host gets nothing.** The socket factory travels with a 30x, so
  the guard re-checks every handshake: a different host (or port) is refused and recorded as
  `origin-mismatch`. `MtlsEntityTest` proves this through `Entity.open` against a real
  `SSLServerSocket` with `needClientAuth`.
- **Nothing is sent to an origin with no choice**, and nothing is inherited.

## When no certificate is sent — the named reasons

Each failure is one log line, written once per state change:

```
event=mtls state=unavailable reason=<token> host=<host:port>
```

| `reason=` | Means |
|---|---|
| `origin-unparseable` | the address is not an HTTPS origin |
| `no-context` | the app could not read its settings (the store was never installed) |
| `no-provider` | the certificate store is not available |
| `no-alias` | no certificate is chosen for this address (the ordinary case) |
| `cert-unavailable` | the chosen certificate is missing or revoked |
| `key-unavailable` | its private key is missing, locked or revoked |
| `origin-mismatch` | the connection went to a different host or port (a redirect) |
| `origin-unverified` | the connection's origin could not be verified at all |
| `picker-cancelled` | the certificate picker was dismissed |

The same token appears in Settings: the Entity row reads `· mTLS` when a certificate is
configured and `· no certificate (<token>)` when one is configured but cannot be used. A bare
`401` from the gateway is therefore distinguishable from "the app never sent anything".

## What it does not protect

- **The gateway still has to verify.** mTLS only helps if the server checks the certificate
  against its own CA. The app cannot make a gateway require one.
- **The server is not pinned.** The gateway is verified by the platform store, like any other
  HTTPS connection. A user-installed CA that the platform trusts for that host can still
  impersonate it (which is why user CAs are scoped to `ts.net` in the network security
  config, and why debug builds get no exception).
- **The API key is still sent.** The bearer key travels in the request header; mTLS adds a
  second, per-device credential, it does not replace the key.
- **The private key is as safe as the keystore.** The app cannot protect a key an attacker
  can use through the platform.
- **IP-literal origins are not refused.** A certificate can be chosen for `https://1.2.3.4:8642`;
  a TLS certificate for an IP avoids name resolution but loses the tailnet's identity
  guarantees. See Follow-ups.
- **No per-profile certificates.** The connection (address, key, scope) is the profile; the
  certificate is a device-wide per-origin choice.

## Where the logic lives

| Piece | File |
|---|---|
| origin parsing/normalising, prefs, factory cache, reasons | `core/MtlsConfig.kt` |
| the origin-scoped `X509ExtendedKeyManager` | `core/MtlsKeyManager.kt` |
| KeyChain access (blocking IPC, handshake threads only) | `core/AndroidKeyChainProvider.kt` |
| process-wide install (one, in `Application.onCreate`) | `VoxApplication.kt` |
| request path | `core/Entity.kt`, `VoiceController.kt` |
| the row and the picker | `SettingsActivity.kt`, `res/layout/mtls_certificate.xml` |

## Follow-ups (not in this change)

1. **A certificate per profile.** Today's key is a device-wide origin choice. If profiles are
   ever served by different CAs, the certificate belongs to the profile record beside url/key,
   not to the origin. Recommendation: do it only when a real deployment needs it — one more
   credential per profile multiplies the "which certificate am I sending" surface for no gain
   on a single-gateway install.
2. **Refusing IP-literal origins.** A host may be a literal IP today. Recommendation: warn,
   do not refuse — a lab gateway on `https://192.168.1.10:8642` with a CA-issued certificate is
   a legitimate setup, and refusing it would break the one case that has no DNS to get wrong.
