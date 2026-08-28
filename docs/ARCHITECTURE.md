# Architecture

This document describes the layering of the combined L2TP/IPsec + SSTP client.
It is written against the plan in [`SPEC`](../SPEC) and is updated as each phase
lands. Sections marked **(planned)** describe work that has not been implemented
yet; they exist so the target shape is agreed before code is written.

## Layers

```
Flutter UI (lib/)
      │  MethodChannel / EventChannel
      ▼
android/app  ──  CombinedVpnService, platform channels        (planned, phase 7)
      │
      ├── core-tunnel ── TunnelBuilder, NetworkMonitor,
      │                  SocketProtectorImpl; DnsConfigurator
      │                  still in android/app (SPEC В.3)
      │
      ├── engine-l2tp ── L2tpEngine : VpnEngine
      │        └── L2tpNative ── VpnBridge ── android/app/src/main/cpp
      │                          (unchanged native engine)
      │
      ├── engine-sstp ── SstpEngine : VpnEngine                (planned, phase 6)
      │        └── core-trust ── trust policies, hostname check,
      │                          pre-flight; store still to come
      │
      └── engine-api ─── VpnEngine, EngineProfile, EngineState,
                         EngineError, TunnelParams, SocketProtector
```

Dependency rules, in force from phase 1:

- `engine-api` depends on nothing but Kotlin coroutines and the Android SDK. No
  Flutter, no concrete engine, no `VpnService`.
- `core-tunnel` depends on `engine-api` only. It must not learn which protocol is
  running.
- `engine-l2tp` and `engine-sstp` depend on `engine-api`; `engine-sstp` also
  depends on `core-trust`. Neither depends on the other, and neither depends on
  `android/app`.
- Only `android/app` may touch `VpnService` and `VpnService.Builder`.

The last rule is the one that decides whether phase 7 works. An engine that can
reach a `VpnService.Builder` will quietly configure the tunnel itself, and the
single-service dispatcher then has two owners for one resource.

## The `VpnEngine` contract

Both engines expose the same lifecycle:

```
engine.connect(profile, protector)  ->  TunnelParams
        (transport up, PPP negotiated, TUN not yet created)
tunnelBuilder.build(params, perAppConfig)  ->  ParcelFileDescriptor
engine.attachTun(fd)
        (packet pumping starts)
engine.disconnect()
```

`connect()` deliberately stops short of creating the TUN. The negotiated address,
DNS servers and MTU come back as `TunnelParams`, and the host builds the
interface. That keeps routing policy — default route, per-app rules, kill switch
— in one place for both protocols instead of duplicated inside each engine.

Every socket an engine opens must be passed to `SocketProtector.protect()`
*before* `connect()` on that socket, including sockets created during a
reconnect and the socket to an HTTP proxy. See appendix Б of the `SPEC`.

## Deviations from the SPEC in `engine-api`

Two things in the SPEC's phase 2 listing could not be implemented as written.
Both are recorded here rather than silently changed.

**`SocketProtector` is a plain interface, not a `fun interface`.** The SPEC
declares it `fun interface` with three `protect` overloads. Kotlin allows
exactly one abstract method in a functional interface, so that does not
compile. The three overloads are the useful part — a TCP socket, a UDP socket
and a raw descriptor are all genuinely needed — so the `fun` modifier is what
gave way. Callers lose SAM-conversion syntax and pass an object instead.

**`EngineProfile.Sstp` carries certificate selections.** The SPEC's field list
stops at `trustPolicy`, but `TrustManagerFactoryProvider.create(policy, certs,
pins)` in phase 5.6 needs the certificates and the pinned fingerprints too, and
the profile handed to an engine has no identifier it could use to look them up.
Two fields are added: `trustedCertificateIds`, holding SHA-256 fingerprints
that identify entries in the `core-trust` store, and `pinnedFingerprints` for
`PIN_LEAF`. Without them `CUSTOM_ONLY` and `PIN_LEAF` cannot be expressed at
all.

`EngineException` is also new: `connect()` is declared to return `TunnelParams`,
so it needs a way to fail that carries an `EngineError`.

**`TunnelParams` carries `excludedRoutes`.** Added in phase 3. Upstream
TunnelForge calls `VpnService.Builder.excludeRoute()` for the VPN server address
on API 33+, so that the tunnel's own transport does not get routed into the
tunnel. `TunnelParams.routes` cannot express this: it means "send these through
the tunnel", and the exclusion is the opposite.

The host cannot derive the value either. For L2TP the excluded address is the
server from the profile, but for SSTP through an HTTP proxy it is the proxy —
that is the host the socket actually connects to, and only the engine knows it.
So the engine reports it.

This duplicates protection already provided by `SocketProtector`, deliberately.
Socket protection is the mechanism that must work; the route exclusion is a
second line that also keeps the traffic off the tunnel interface, and it is
unavailable below API 33.

## Contract members with no implementation yet

Three things in `engine-api` are declared and unused. They are listed here so
that "nothing writes this" is a recorded state rather than something the next
person rediscovers. The decisions on all three are in appendix В of the `SPEC`.

| Member | Status | Lands in |
|---|---|---|
| `EngineState.Reconnecting` | nothing emits it; reconnect-on-network-change is not built | phase 7, in the host — the logic is the same for both protocols |
| `EngineProfile.customDns` | nothing reads it; DNS reaches the service through the `Intent` instead | phase 8, with the profile model |
| `TunnelParams.searchDomains` | `TunnelBuilder` applies it, no engine fills it | phase 6 from SSTP; the L2TP native layer does not return domains from IPCP |

`EngineProfile.L2tp` additionally carries four fields the native engine cannot
honour — `ipsecEnabled = false`, `localIdentifier`, `phase1Proposals` and
`phase2Proposals`. `L2tpEngine` logs a warning for each rather than ignoring it
silently, because a setting that does nothing is worse than an absent one. What
happens to them — implemented in C, dropped from the contract, or shown for SSTP
only — is decided before phase 9 (SPEC В.5).

## Trust, in `core-trust`

Verification splits into three questions that fail differently, so they are
three types rather than one.

**Is the certificate trusted?** `TrustManagerFactoryProvider` builds the
`X509TrustManager` a profile's `TrustPolicy` calls for. `SYSTEM` and
`CUSTOM_ONLY` are ordinary PKIX managers over different anchors;
`SYSTEM_PLUS_CUSTOM` tries the system store first and falls back, throwing the
system failure with the custom one attached because the system message is the
one that explains what is wrong; `PIN_LEAF` compares the leaf's SHA-256 against
the profile's pins in constant time and ignores the chain entirely, which is
what makes a self-signed router certificate usable without disabling
verification everywhere.

`INSECURE` cannot be built unless the caller passes `allowInsecure`, and the
default is `false` so that forgetting produces a refusal rather than an
unverified tunnel. A profile carrying it in a release build is downgraded to
`SYSTEM_PLUS_CUSTOM` with a log entry rather than refused, so a profile written
on a debug build still connects.

**Is it the right host?** `HostnameVerification` answers separately, because a
trusted certificate with the wrong name needs a different fix from an untrusted
one — filling in `expectedHostname` rather than importing anything. A mismatch
carries every name the certificate presents, which is what
`EngineError.HostnameMismatch` shows the user.

**Will this work at all?** `TrustPreflight` runs before a socket is opened and
turns what would be an `SSLHandshakeException` ten seconds in into a sentence
beforehand. It blocks on things that make the attempt pointless — a deleted
certificate, a chain policy with no anchors, pinning with no pins — and merely
asks about an expired certificate, since a router serving one is common and the
user may know why. `PIN_LEAF` never checks expiry during the handshake, so
without this the fact would never surface at all.

## The certificate store

The store answers one question the trust managers cannot: which certificates
does this device trust, and where are they.

Metadata lives in Room (`server_certificates`, and `profile_certificate_ref`
for the many-to-many with profiles); the certificates themselves live as PEM
files under `filesDir/trust`, owner-only. Splitting them is deliberate. A trust
decision is made from a certificate's bytes and the user can export them
unchanged, which a blob column makes awkward for no gain. The file name is the
SHA-256 fingerprint and so is the primary key, so a row and its file cannot
drift apart, and re-importing the same certificate updates the alias instead of
creating a second entry.

Nothing outside the application is referenced. A document the user picks is
copied in, never remembered as a `Uri`: an always-on VPN starts before the
device is unlocked, and at that moment no document provider is there to answer.
A certificate the tunnel cannot read is a tunnel that cannot start.

`TrustStore` is the only thing that writes to both halves, and it writes the
file first — a failure there means no row, and the import simply did not
happen. Deleting cascades the profile references away; a profile that trusted
the deleted certificate is not quietly repaired, because `TrustPreflight` is
where the user finds out, at the moment they can act on it.

### Importing

Three paths (SPEC 5.3), converging on one type. `CertificateParser` reads PEM,
DER and bundles; `ServerChainFetcher` covers the third path by opening a TLS
connection that accepts anything, capturing the chain during the handshake, and
closing without sending a byte. All three end at a list of `ImportCandidate`,
each carrying its fields, the PEM that would be stored, and the warnings from
`CertificateValidator`. Nothing reaches the store until the user picks from
that list.

The warnings are attached to the candidate rather than computed afterwards
because the point of SPEC 5.4 is that the user decides with them on screen. All
of them are warnings: an expired certificate or a short key may be exactly what
the user's router presents, and blocking the import would leave them unable to
connect to their own server. Only a file that does not parse is refused.

The download-from-server path is the weakest and the UI says so. It accepts any
certificate, so an attacker in the middle can hand over their own and it will
be displayed as the server's. Comparing the SHA-256 fingerprint against the one
the router shows is what provides the security; the download only saves typing.

### Reaching it from Flutter

The store stays on the Kotlin side. `TrustChannel` exposes the operations, and
`TrustPayloads` maps store types onto the channel's maps — separately, and
tested, because the Dart side reads those keys by hand. Certificates cross as
fields and identifiers; PEM travels in exactly two directions, out when a
candidate is offered for import and back when the user exports one.

Which trust policies exist is answered by the host rather than decided in Dart:
`INSECURE` is absent from a release build entirely, and a list hardcoded in the
UI would be one `kDebugMode` check away from offering it anyway.

## Error mapping

`EngineError` is the single vocabulary both engines report in. The per-protocol
mapping tables below are filled in as the engines land.

### Native L2TP layer → `EngineError`

The native engine reports one `TUNNEL_EXIT_*` code, defined in
`android/app/src/main/cpp/engine.h`. `L2tpExitCode.toEngineError` is the
translation, and `L2tpExitCodeTest` asserts every row below.

| Native status | Code | `EngineError` |
|---|---|---|
| `TUNNEL_EXIT_OK` | 0 | none — the tunnel shut down cleanly |
| `TUNNEL_EXIT_IKE_FAILED` | 1 | `IpsecFailed` |
| `TUNNEL_EXIT_L2TP_FAILED` | 2 | `PppNegotiationFailed(phase = "L2TP")` |
| `TUNNEL_EXIT_PPP_FAILED` | 3 | `PppNegotiationFailed(phase = "PPP")` |
| `TUNNEL_EXIT_POLL_ERROR` | 4 | `NetworkUnreachable` |
| `TUNNEL_EXIT_BAD_ARGS` | 10 | `Internal` |
| `TUNNEL_EXIT_PROXY_NOT_IMPLEMENTED` | 11 | `Internal` |
| `TUNNEL_EXIT_STOPPED` | 12 | none — a deliberate stop |
| anything else | | `Internal`, with the code in `detail` |

Three rows deserve their reasoning written down.

**`TUNNEL_EXIT_L2TP_FAILED` is not its own variant.** The L2TP control channel
is neither IPsec nor PPP, and `EngineError` has no variant for it. Adding one
would push a detail of a single protocol into the vocabulary both engines share,
for a failure the user can do nothing different about, so the failure is
reported as the negotiation it belongs to and named by its `phase`. If SSTP ever
needs the same distinction, that is the point to revisit it.

**Nothing maps to `AuthenticationFailed`.** *Being fixed: SPEC В.2 assigns the
native change, due before phase 10.* The C layer does not distinguish a
CHAP/MSCHAPv2 rejection from any other PPP failure: `ppp.c` returns the same
`-1` for a Failure packet, an I/O error and a timeout, and the caller turns all
three into `TUNNEL_EXIT_PPP_FAILED`. Mapping code 3 to `AuthenticationFailed`
would therefore claim wrong credentials whenever LCP or IPCP failed — and a
failover group **stops** on `AuthenticationFailed` rather than trying the next
member, so the wrong guess would silently break failover in phase 10. The peer's
own failure text does reach the log stream, which is where a user looks today.
Fixing this properly means a new native status code, which phase 4 may not add.

**`TUNNEL_EXIT_POLL_ERROR` maps to `NetworkUnreachable`, not `Internal`.** It is
only reachable once the tunnel is already carrying packets, so the transport was
working and has stopped working. That is a lost network from the user's point of
view, and it is the case reconnection logic should act on.

#### What the user reads

The `detail` strings carry the exact wording the interface showed before phase 4;
`messageKey` is the localisation key the UI moves to later. Phase 4 changed the
shape of the L2TP path and deliberately not one character of its user-visible
text.

#### Where the engine sits

`L2tpEngine` wraps the native layer through `L2tpNative`, an interface with one
implementation: `VpnBridgeL2tpNative` in the application module, which is pure
delegation to `VpnBridge`. The engine cannot reference `VpnBridge` directly —
the JNI methods are registered against a class name the C layer hard-codes, so
that class has to stay in the application, and a library module may not depend
on the application containing it.

The C layer also calls *up*, by name, into three static methods it resolves at
load time. Those stay where they are and forward to the running engine through
`L2tpNativeCallbacks`:

| Native upcall | Forwards to | Effect |
|---|---|---|
| `TunnelVpnService.protectSocketFd` | `SocketProtector.protect` | the socket bypasses the tunnel |
| `TunnelVpnService.onNativeTunnelReady` | `EngineState.Connected` | the UI shows the tunnel as up |
| `VpnTunnelEvents.emitEngineLogFromNative` | `EngineLogEvent(protocol = L2TP)` | the line joins the engine's event stream |

When no engine is installed — proxy-only mode, or a teardown already in flight —
these are no-ops, and `protect` returns `null` so the caller falls back to
protecting the socket itself rather than leaving it inside the tunnel.

### SSTP `Where` / `Result` → `EngineError` (planned, phase 6)

Upstream Open SSTP Client reports failures as a `Where` (which component failed)
plus a `Result` (why). The granularity is good and is carried over verbatim; the
table below maps it onto `EngineError`.

| `Where` | `Result` | `EngineError` |
|---|---|---|
| _to be filled in phase 6_ | | |

## Native code

The C engine under `android/app/src/main/cpp/` implements L2TP, IKEv1 and the
IPv4 data plane. It is working and tested, and phase 4 is explicitly a
behaviour-preserving refactor around it: `git diff` over that directory is
expected to stay empty. Any change there needs a justification in the commit
message.

### Changes made to the native engine

One entry per change, with the reason. The list should stay short; if it grows,
the boundary between the engine and the layers around it is in the wrong place.

#### Quick Mode no longer aborts on an interleaved Informational exchange

*`ikev1.c`, 2026-08-25. Found while running the phase 3 manual test plan.*

**Symptom.** Connecting to a MikroTik L2TP/IPsec server failed every time from
a debug build, and succeeded every time from a release build of the same code.
The log said `Quick Mode: server sent NOTIFY type=24578 (0x6002) - proposal
rejected`, while the server logged `ISAKMP-SA established` and no error at all.

**Cause.** After sending Quick Mode msg1, the engine took the first datagram
that came back and treated any Informational exchange among them as a rejected
ESP proposal. A peer may send an Informational at any time, and MikroTik sends
INITIAL-CONTACT shortly after phase 1 authentication. Whether it arrives before
or after QM2 is a race, and the two builds sat on opposite sides of it: native
code is compiled `-O0` for debug variants and optimised for release, so the
debug build consistently lost.

Notification type 24578 is INITIAL-CONTACT. RFC 2408 §3.14.1 splits NOTIFY
types into errors (1..16383) and status messages (16384 and up), and RFC 2407
§4.6.3 assigns INITIAL-CONTACT, RESPONDER-LIFETIME and REPLAY-STATUS in the
status range. The engine was reporting a status message as a rejection.

**Change.** An Informational carrying only status notifications is logged and
skipped, and the engine keeps waiting for QM2 rather than giving up; an
Informational carrying an error notification still fails the negotiation, now
with an accurate message. Reading again uses a new `ike_recv_only()` rather
than `ike_send_recv()`, because QM1 was delivered and must not be re-sent.
Bounded by `IKE_QM_MAX_INFO_BEFORE_QM2` and `IKE_QM_INFO_WAIT_MS`.

**Why this was not left alone.** It is not cosmetic and it is not specific to
debug builds. The race exists in release too — a slower device or a busier
network puts a release build on the losing side of it just as readily. The
behaviour on a genuinely rejected proposal is unchanged.
