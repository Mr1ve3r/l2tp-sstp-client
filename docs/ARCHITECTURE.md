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
      ├── core-tunnel ── TunnelBuilder, KillSwitchController,  (planned, phase 3)
      │                  DnsConfigurator, NetworkMonitor,
      │                  SocketProtectorImpl
      │
      ├── engine-l2tp ── L2tpEngine : VpnEngine                (planned, phase 4)
      │        └── JNI ── android/app/src/main/cpp (unchanged native engine)
      │
      ├── engine-sstp ── SstpEngine : VpnEngine                (planned, phase 6)
      │        └── core-trust ── certificate store, TrustPolicy (planned, phase 5)
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

## Error mapping

`EngineError` is the single vocabulary both engines report in. The per-protocol
mapping tables below are filled in as the engines land.

### Native L2TP layer → `EngineError` (planned, phase 4)

| Native status | `EngineError` |
|---|---|
| _to be filled in phase 4_ | |

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
