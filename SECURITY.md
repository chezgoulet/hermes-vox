# What Hermes Vox trusts, and how to report a flaw

Hermes Vox is the Android voice client for Hermes, an agent that runs on a gateway
you operate. The phone holds the microphone, the speech models, the key and the
approval cards (the prompts that ask you to allow one action). The gateway holds the
agent, the memory and the tools. This page states what the app protects, how each
control works, what a compromised gateway can still do, and how to report a
vulnerability.

You need an Android phone, a Hermes gateway you can reach, and that gateway's API
key. Some deployments also use a tailnet (a private network such as Tailscale) and a
client certificate. Both are optional; the app works without them, and the limits of
working without them are below.

## The trust model

There are three sides, and the app treats them differently.

- **The phone.** The app, its private storage, its logs, its exported components and
  the phone tool server (the small server the app runs on the tailnet so the agent can
  act on the phone).
- **The wire.** The path from the phone to the gateway.
- **The gateway.** The agent's host, which the app trusts completely. The client is a
  front end to that agent, not a second decision-maker.

The gateway is the mind. It receives every turn, answers it, and decides what tools
to run. The app can gate what the agent may do **on the phone**, and it can ask you
before a phone action runs. It cannot audit the agent's reasoning, and it cannot stop
a gateway that is already compromised from misusing what it already has: the
conversation and the key.

| Asset | Why it matters | Where it lives |
|---|---|---|
| The gateway API key | a bearer credential to an agent that may have file, shell, web and memory tools | entered by you; encrypted at rest on the phone; never compiled into the app |
| The conversation | the content of your calls with the agent | on the phone during a turn; sent as text to the gateway; kept by the gateway |
| The phone-MCP token | a credential the gateway presents to the phone's tool server | generated on the phone; encrypted at rest; never logged |
| Device-local state | gateway address, model choices, preferences, logs, models, the phone tool audit | app-private storage |
| Release integrity | the APK (the installable Android package) is the build it claims to be | signed with the project's release key, which is not in the repository |

## The key: where it lives

- **You enter it.** Onboarding and Settings → Entity write it. There is no compiled-in
  default, no `BuildConfig` field and no fallback. `GatewayKey.resolve` returns `""` for
  blank storage or a value it cannot decrypt, and the app asks you to enter the key
  again.
- **A release build refuses to bake one in.** The Gradle guard fails
  `assembleRelease` and `bundleRelease` if `HERMES_VOX_API_KEY` is set in the build
  shell, or if a `buildConfigField` whose name mentions a key is added to the module's
  Gradle sources. The guard runs on release builds only, so debug builds and unit
  tests are unaffected.
- **It is encrypted at rest.** `SecureStore` generates an AES/GCM key inside the
  Android Keystore (the phone's key store; alias `hv_api_key`). The stored value is
  `enc1:<iv>:<ciphertext>` in the app's private `hv` preferences. The Keystore key
  never leaves the keystore; the ciphertext is decrypted only in memory. The
  phone-MCP token has its own alias, `hv_phone_mcp`.
- **A Keystore failure is visible, not silent.** If encryption fails, the app stores
  the value as plaintext, writes
  `event=secure-store encrypt=failed … using=plaintext` to the local log, and retries
  the encryption on the next start (`SecureStore.storageUpgrade` in `VoxApplication`).
  A corrupt marked value reads as missing, which prompts you to enter the key again;
  it never reads as a wrong key.
- **The key goes to one destination.** The app attaches it as
  `Authorization: Bearer` to requests to the gateway origin you configured. The phone
  tool server compares a presented credential against the key on the phone; it never
  sends the key anywhere. A remote speech server, if you configure one, has its own
  optional key, stored the same way.
- **Android backup is off.** `allowBackup="false"` keeps the app's private storage out
  of Android's cloud backup. The manifest declares no device-transfer extraction rules,
  so a transfer run by the phone's own migration tool is outside the app's control.

## Cleartext and the tailnet

The network security config (`android/app/src/main/res/xml/network_security_config.xml`)
is the first rule:

- **The default is no cleartext and system CAs only.** Cleartext is plain HTTP with
  no TLS. Anything not listed below must speak HTTPS and present a certificate the
  system store trusts, from a certificate authority (CA) the phone ships trusting.
- **`ts.net` is the one exception.** MagicDNS names under `ts.net` may be reached in
  cleartext, and the user certificate store is trusted for them, so a tailnet CA
  issued by `tailscale serve` verifies. Cleartext is allowed because the tailnet
  encrypts the path itself.
- **There is no debug exception.** The file has no `<debug-overrides>` block, so a
  user-installed CA is never trusted for every host. Trusting one would let a work
  proxy, a test root or a hostile profile mint a certificate for the gateway and read
  the key.

The app adds a second rule (`core/CleartextPolicy.kt`), because the XML cannot ask
whether the tunnel is up:

- `https://` is never touched: TLS is the guarantee.
- Loopback (`localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0`) is never touched: it cannot
  be answered off the device.
- **Every other `http://` URL is refused while the tailnet is down.** The detector is
  the phone's own tailnet address: `100.64.0.0/10` for IPv4, Tailscale's
  `fd7a:115c:a1e0::/48` for IPv6. With no such address the policy fails closed, and
  the refusal names the cause: "Tailscale is not connected, so this plain-HTTP
  endpoint cannot be trusted."

Why it matters: `*.ts.net` is tailnet-private by construction. With the tunnel down,
the name is resolved by whatever network the phone is on, and a hostile answer would
collect the bearer key on the first request. Because the app checks the live address
on every gateway request (`Entity.open` is the backstop for every connector path), a
plain-HTTP request to a `ts.net` name is refused while the tunnel is down even though
the XML would permit it. The optional remote speech server builds its own connection
and is not covered by this check.

Two consequences:

- A gateway on a bare LAN address over plain HTTP does not work. Android blocks the
  request, and the app's error names the way out: serve it over HTTPS, or use
  `tailscale serve`.
- A self-signed certificate on a LAN address is not trusted, because the user CA
  store is scoped to `ts.net`. Use a certificate the system store trusts, or the
  `https://<machine>.<tailnet>.ts.net` address with the tailnet CA.

## Per-origin client certificates

A gateway can require a client certificate as well as the key. The app can present
one, chosen by you from Android's credential store, scoped to one **origin**.

- An origin is a host and a port. The choice is stored as
  `mtls_alias_<host>:<port>` in the `hv` preferences. The host is normalised once:
  ASCII, lower case, no IPv6 brackets, no trailing root dot, IPv6 in compressed form.
  The same function normalises the peer a socket reports, so an IPv6 or IDN gateway
  cannot silently match itself incorrectly.
- The certificate is offered only when the socket's peer is that origin — same host
  and same port. A redirect to another host gets no certificate, and the refusal is
  recorded as `origin-mismatch`.
- Server verification is untouched. The `SSLContext` is initialised with a **null**
  TrustManager, which on Android is the platform trust manager: the system store and
  the `ts.net` rule apply exactly as they do without a client certificate. There is no
  custom `X509TrustManager` and no `HostnameVerifier` in the app.
- Settings shows the certificate's subject and expiry (`CN=gateway-client · expires
  2027-04-01`), not the opaque KeyChain alias. Changing the address does not carry the
  certificate to the new origin; the row says to choose it again.
- The app never exports, serialises, logs or copies the private key. It holds a
  `PrivateKey` handle from `KeyChain.getPrivateKey` for the duration of a handshake.
- When no certificate is sent, one line names why:
  `event=mtls state=unavailable reason=<token> host=<host:port>`. The tokens are
  `no-alias`, `origin-mismatch`, `cert-unavailable`, `key-unavailable`,
  `origin-unparseable`, `no-context`, `no-provider`, `origin-unverified` and
  `picker-cancelled`. A bare `401` from the gateway is therefore distinguishable from
  "the app never sent anything".

What it does not do: it does not make the gateway require a certificate, and it does
not pin the gateway's own certificate. The server is verified by the platform store,
like any other TLS server.

## Approvals: silence is never a yes

Two kinds of approval exist, and both treat the absence of an answer as the absence of
consent.

### An approval from the agent's run

When the gateway stops a run and asks, it sends an `approval.request` with the
choices it will accept. The app renders those choices and never invents one. `deny` is
present even when the gateway's list is malformed.

- **Every request has one answer.** The first tap, spoken answer or timeout removes
  the request and records its key. A second tap, a duplicate voice match or a late
  timeout is a local no-op, never a second request to the gateway.
- **The local timeout is 290 seconds**, slightly inside the gateway's 300-second
  default so the phone's answer arrives while the request is still open. A timeout
  answers `deny`; the log records the decision as `timeout`, so a machine denial is
  never read as a human "no".
- **Voice is a whole-utterance allowlist.** The recognised words must be one of a
  short list of refusals or approvals, at most three words. A question, a negation, a
  hedge or thinking aloud is no answer: the card stays up and the app says "say
  approve or no" once. A positive answer takes the least privilege the gateway
  offered (`once`, then `session`, then `always`).
- **Nothing else approves.** A dropped socket, a finished run, a hung-up call or a
  withdrawn request closes the card without answering it. There is no code path that
  turns silence into a yes.

### An approval for a phone action

Every phone action whose group is **Ask** uses one two-step handshake, whatever the
group or its tier.

1. A call raises a card naming the action and its arguments and returns
   `approval-required` with an id and an expiry. Nothing is performed.
2. The agent calls the same action with the same canonical arguments and the id. It
   runs only when the id is unexpired, unused, and the action and arguments hash to
   the value the card was raised for.

- The card is good for **two minutes**. Expiry is an expired id, not an approval.
- At most three cards are open at once — one on screen and two behind it. A fourth
  request is `approval_busy`.
- A yes answered while the phone is locked is refused (`approval_locked`); the id is
  burnt.
- Dismissal, expiry and every refusal burn the id. A repeat returns the recorded
  reason once, then `approval_unknown`; it never becomes a second permission.
- If no card and no notification can be shown, the action refuses `unavailable` rather
  than looking like a card that silently expired.
- The notification is `VISIBILITY_PRIVATE`, so the lock screen does not show a file's
  name or the app being removed.

**The rule in one line:** no timeout, expiry, dropped run, hung-up call, non-answer or
unavailable surface ever approves anything.

## What a compromised gateway can do

The honest answer is: anything the agent could do, within the phone grants you chose,
plus everything it already receives.

- **It already has the conversation and the key.** The app sends the turn and the key
  to it. A compromised gateway does not need to steal them.
- **It can speak, and it can ask.** Reply text is displayed and spoken. A gateway can
  therefore put arbitrary words in the avatar's mouth.
- **It can call the phone tools, within the grants** (the Off/Ask/Allow setting for
  each capability group). At **Allow**, the group's
  actions run without a card: timers and alarms, Do Not Disturb and volume, media,
  device state, notes, calendar, clipboard reads and writes, messages and dialer
  drafts, opening and removing apps (Android confirms a removal), contact lookups,
  notifications while a call is open, and reading or offering a file in the folder you
  granted. At **Ask**, each call raises a card. At **Off**, the action is not in the
  schema and a direct call is refused `not_granted`.
- **It cannot answer its own card.** An Ask request needs your tap or your voice on
  the phone. The gateway can ask; only you can approve.
- **It cannot exceed the granted folder or the declared surface.** Files are limited
  to the folder picked in Android's folder picker; `install` is not in the phone tool
  surface; a file offer above 100 MB is refused.
- **It cannot run code on the phone.** The app has no remote-code path: replies are
  text and speech, and tools are the enumerated phone actions.
- **It cannot make the app send the key to another configured destination.** The key
  is attached to requests to the gateway base URL you configured; it is not sent to
  the model host, a remote speech server, or the phone's own tool server.

If you do not trust the gateway, do not give it phone grants, and do not point the
app at it. The architecture is *the entity is the agent*: there is no second brain on
the phone to overrule it.

## Other controls

- **The exported surface is small.** `MainActivity` is exported because it is the
  launcher and a share/view target; `AssistActivity` is exported because the system
  starts it when Vox holds the assistant role; `MediaListenerService` is bound with
  the platform's notification-listener permission. Everything else is
  `exported="false"`. The app honours no intent extra that repoints the gateway or
  injects a command: a share or a viewed file becomes a draft on a confirmation card
  and is not sent until you press SEND.
- **Permissions are the ones the actions need.** Internet, microphone, audio settings,
  vibration, wake lock, network state, notifications, Do Not Disturb policy, calendar
  read/write, contacts read, usage stats and the foreground-service permissions. There
  is no storage permission (files go through the folder picker), no exact-alarm
  permission (timers use the exempt listener form) and no `QUERY_ALL_PACKAGES`.
- **The phone tool server binds one address.** It refuses `0.0.0.0` and `::`, listens
  only on the tailnet address, serves only `/mcp`, and requires a bearer credential on
  every request, compared in constant time. Two credentials are accepted: the phone's
  own token, and the gateway key of the connection in force, so the agent this phone
  already talks to does not need a second pairing secret. It caps a request body at
  256 KiB and keeps at most eight sessions, each idle-expiring after 30 minutes.
- **Model downloads are verified.** A model streams, is checked against a pinned
  SHA-256, and is unpacked with a zip-slip guard into app-private storage. A mismatched
  hash aborts the install.
- **The gate inspects the built APK.** It unpacks the debug APK and fails if any
  `.p12`, `.pfx`, `.jks`, `.bks`, `.keystore` or `.pem` file is inside. The mTLS test
  keys live under `src/test/resources` and must not reach the artifact.
- **Release builds are signed with the project's release key.** The keystore and
  `keystore/keystore.properties` are gitignored. A missing properties file fails the
  build loudly; there is no silent fallback to the Android debug key. Verify a build
  with `apksigner verify --print-certs`: the certificate is the release certificate,
  never `androiddebugkey`. A release-signed build does not install over a debug-signed one;
  that move is a fresh install.

## Reporting a vulnerability

**Do not open a public issue or pull request.** The tracker is public, and the details
of a live exploit — especially one involving the key or the agent's tool access —
must not sit in a public thread before a fix ships.

Report privately to:

> **chris@coveredbridgecookies.com**

Include:

- the affected build: `versionName` and `versionCode` from
  `android/app/build.gradle`, and the Android version and device;
- a reproduction: steps, or a minimal proof of concept without live keys;
- what you observed and what you expected;
- your assessment of the impact, using the trust model above.

**What happens next.** The maintainer triages privately, a fix is developed on a
private branch, and a fix release ships on the normal release path. Credit can be
given in the release notes if you want it. There is no bug-bounty programme, and this
is a small project with no response-time guarantee.

## Supported versions

Fixes ship on the current release line, as point releases. No older line is
maintained and there is no long-term-support promise. Updates arrive by sideload or
Obtainium; because a release-signed build and a debug-signed build cannot replace each
other, moving between them is a fresh install.

## What it cannot do

- It cannot protect you from a malicious or compromised gateway. That is the
  architecture: the gateway is the mind, and the app is its voice.
- It cannot pin the gateway's certificate. Verification is the platform's, so a
  trusted CA that issues a wrong certificate is believed.
- It cannot make a gateway require a client certificate, and mTLS (mutual TLS, where
  the gateway also asks the phone for a certificate) helps only when the gateway
  verifies the one it receives.
- It cannot protect a rooted or compromised phone. The Keystore raises the cost of
  extracting the key; it does not defeat an attacker who owns the operating system.
- It cannot turn cleartext into encryption. Plain HTTP to a `ts.net` name is safe only
  while the tailnet is up and only because the tailnet encrypts it.
- It cannot provide the phone tool surface or a file offer without a tailnet address:
  with no tailnet, there is no address to bind and the server does not start.
- It cannot pin a model host. Downloads come from the host you choose and are verified
  by SHA-256 after the fact; the host itself is not pinned.
- It cannot stop a third-party keyboard, launcher or Android build from observing what
  the app draws or what you type.
- It cannot extend the phone grants beyond the groups and states you chose, and it
  cannot give the agent an action a group does not list.
- It cannot guarantee that deleting data erases every physical copy on flash storage;
  it deletes the app's files and destroys the Keystore keys that protect the secrets.
