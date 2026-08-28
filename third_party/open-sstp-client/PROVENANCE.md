# Provenance: Open SSTP Client

This document records the origin of every source file in this repository that is
derived from Open SSTP Client. It exists to satisfy the attribution requirement
of the MIT License and to make the relationship to upstream auditable.

## Upstream

| Field | Value |
|---|---|
| Project | Open SSTP Client |
| URL | https://github.com/kittoku/Open-SSTP-Client |
| Author | KOBAYASHI Ittoku |
| License | MIT (`Copyright (c) 2019 KOBAYASHI Ittoku`) |
| Imported from commit | `5f97511c3afff7dcf76b763882e0e29207ad1a36` |
| Commit date | 2026-08-15 |
| Release tag | `1.10.3` |
| License text | `third_party/open-sstp-client/LICENSE` (verbatim copy) |

**When updating from upstream, update the commit hash above and re-verify every
row in the mapping tables.** A stale hash makes this document worse than useless.

## Licensing of derived files

Upstream ships no per-file copyright headers — only a root `LICENSE`. MIT
requires the copyright notice and permission notice to accompany all copies and
substantial portions of the software, so every derived file in this repository
**must** carry the following header. It is added by us; it is not present
upstream.

```kotlin
/*
 * Derived from Open SSTP Client
 * https://github.com/kittoku/Open-SSTP-Client
 * Copyright (c) 2019 KOBAYASHI Ittoku
 * Licensed under the MIT License.
 * See third_party/open-sstp-client/LICENSE for the full text.
 *
 * Modifications Copyright (C) <year> <owner>
 * Licensed under GPL-3.0-or-later as part of this project.
 */
```

Renaming the package from `kittoku.osc.*` to
`io.github.<owner>.combined.engine.sstp.*` does not remove this obligation.

### Adaptation levels

| Level | Meaning |
|---|---|
| `VERBATIM` | Byte-identical apart from the `package` line and the added header |
| `MINOR` | Import paths and visibility modifiers adjusted; logic unchanged |
| `ADAPTED` | Logic changed — typically to remove `SharedPreferences` or `VpnService` coupling |
| `REWRITTEN` | New implementation informed by upstream; retained here for honest attribution |

`REWRITTEN` files still carry the header. Being informed by MIT code is enough
reason to credit it, and the cost of over-attributing is zero.

---

## 1. Files imported into `engine-sstp`

Upstream spells its acronyms in full capitals — `LCPConfigureRequest`,
`PAPFrame`, `MSCHAPV2Client` — and carries a few typos in identifiers
(`ChapValueNameFiled`, `idFiled`). Both are normalised here: `LcpConfigureRequest`,
`PapFrame`, `MsChapV2Client`, `ChapValueNameField`. The renames are mechanical
and apply to every row below, so they are recorded once here rather than in
thirty notes.

### 1.1. SSTP protocol units

| Upstream path | Destination | Level | Notes |
|---|---|---|---|
| `unit/sstp/ControlPacket.kt` | `engine-sstp/.../unit/sstp/ControlPacket.kt` | MINOR | SSTP control messages |
| `unit/sstp/Attribute.kt` | `engine-sstp/.../unit/sstp/Attribute.kt` | MINOR | SSTP attributes |
| `unit/DataUnit.kt` | `engine-sstp/.../unit/DataUnit.kt` | MINOR | Base unit abstraction |

### 1.2. PPP frames and options

| Upstream path | Destination | Level | Notes |
|---|---|---|---|
| `unit/ppp/Frame.kt` | `engine-sstp/.../unit/ppp/Frame.kt` | MINOR | |
| `unit/ppp/LCPFrame.kt` | `engine-sstp/.../unit/ppp/LcpFrame.kt` | MINOR | Renamed to Kotlin casing |
| `unit/ppp/IpcpFrame.kt` | `engine-sstp/.../unit/ppp/IpcpFrame.kt` | MINOR | |
| `unit/ppp/Ipv6cpFrame.kt` | `engine-sstp/.../unit/ppp/Ipv6cpFrame.kt` | MINOR | |
| `unit/ppp/option/Option.kt` | `engine-sstp/.../unit/ppp/option/Option.kt` | MINOR | |
| `unit/ppp/option/lcp.kt` | `engine-sstp/.../unit/ppp/option/LcpOptions.kt` | MINOR | Renamed: ktlint requires PascalCase file names |
| `unit/ppp/option/ipcp.kt` | `engine-sstp/.../unit/ppp/option/IpcpOptions.kt` | MINOR | Renamed for the same reason |
| `unit/ppp/option/ipv6cp.kt` | `engine-sstp/.../unit/ppp/option/Ipv6cpOptions.kt` | MINOR | Renamed for the same reason |
| `unit/ppp/auth/PAPFrame.kt` | `engine-sstp/.../unit/ppp/auth/PapFrame.kt` | ADAPTED | **Bug fixed:** the acknowledgement read its message length from its own (empty) contents rather than from the length the peer claimed, so the message was never read and its bytes were left in the buffer for the next packet boundary to trip over. Upstream typos `idFiled`/`passwordFiled` corrected |
| `unit/ppp/auth/ChapFrame.kt` | `engine-sstp/.../unit/ppp/auth/ChapFrame.kt` | MINOR | |
| `unit/ppp/auth/ChapField.kt` | `engine-sstp/.../unit/ppp/auth/ChapField.kt` | MINOR | |
| `unit/ppp/auth/EAPFrame.kt` | `engine-sstp/.../unit/ppp/auth/EapFrame.kt` | MINOR | See §4 on EAP scope |

### 1.3. Protocol clients (state machines)

| Upstream path | Destination | Level | Notes |
|---|---|---|---|
| `client/SstpClietnt.kt` | `engine-sstp/.../client/SstpClient.kt` | ADAPTED | **Upstream filename contains a typo (`Clietnt`); corrected here. Recorded so the mapping stays traceable.** Reads config from `SstpEngineConfig` instead of `SharedBridge` prefs |
| `client/ppp/PPPClient.kt` | `engine-sstp/.../client/ppp/PppClient.kt` | ADAPTED | |
| `client/ppp/ConfigClient.kt` | `engine-sstp/.../client/ppp/ConfigClient.kt` | MINOR | |
| `client/ppp/LCPClient.kt` | `engine-sstp/.../client/ppp/LcpClient.kt` | ADAPTED | MRU/MTU from profile |
| `client/ppp/IpcpClient.kt` | `engine-sstp/.../client/ppp/IpcpClient.kt` | ADAPTED | Result feeds `TunnelParams` instead of `VpnService.Builder` |
| `client/ppp/Ipv6cpClient.kt` | `engine-sstp/.../client/ppp/Ipv6cpClient.kt` | ADAPTED | Same |
| `client/ppp/auth/PAPClient.kt` | `engine-sstp/.../client/ppp/auth/PapClient.kt` | ADAPTED | Credentials from profile |
| `client/ppp/auth/ChapClient.kt` | `engine-sstp/.../client/ppp/auth/ChapClient.kt` | ADAPTED | Same |
| `client/ppp/auth/MSCHAPV2Client.kt` | `engine-sstp/.../client/ppp/auth/MsChapV2Client.kt` | ADAPTED | Same |
| `client/ppp/auth/ChapMSCHAPV2Client.kt` | `engine-sstp/.../client/ppp/auth/ChapMsChapV2Client.kt` | ADAPTED | Same |
| `client/ppp/auth/EAPClient.kt` | `engine-sstp/.../client/ppp/auth/EapClient.kt` | ADAPTED | See §4 |
| `client/ppp/auth/EAPMSAuthClient.kt` | `engine-sstp/.../client/ppp/auth/EapMsAuthClient.kt` | ADAPTED | See §4 |

### 1.4. I/O pipeline

| Upstream path | Destination | Level | Notes |
|---|---|---|---|
| `io/incoming/IncomingManager.kt` | `engine-sstp/.../io/IncomingManager.kt` | MINOR | |
| `io/incoming/process.kt` | `engine-sstp/.../io/process.kt` | MINOR | |
| `io/incoming/EchoTimer.kt` | `engine-sstp/.../io/EchoTimer.kt` | MINOR | Keepalive |
| `io/OutgoingManager.kt` | `engine-sstp/.../io/OutgoingManager.kt` | MINOR | |

### 1.5. Terminals

| Upstream path | Destination | Level | Notes |
|---|---|---|---|
| `terminal/SSLTerminal.kt` | `engine-sstp/.../terminal/SslTerminal.kt` | **ADAPTED (heavy)** | Largest upstream file (483 lines) and the main integration point. See §3 |
| `terminal/IPTerminal.kt` | `engine-sstp/.../terminal/IpTerminal.kt` | **ADAPTED (heavy)** | Upstream writes directly to `VpnService.Builder`; here it only produces `TunnelParams` and consumes a TUN fd handed in via `attachTun()`. See §3 |

### 1.6. Utilities

| Upstream path | Destination | Level | Notes |
|---|---|---|---|
| `cipher/hash.kt` | `engine-sstp/.../cipher/Md4.kt` | MINOR | MD4 for MSCHAPv2, which no JCA provider offers. Body byte-identical; renamed for ktlint and given a `:Suppress` so the RFC 1320 register names A/B/C/D survive the naming rule |
| `extension/Byte.kt` | `engine-sstp/.../extension/Byte.kt` | VERBATIM | |
| `extension/ByteArray.kt` | `engine-sstp/.../extension/ByteArray.kt` | MINOR | The `parse` argument of `toHexString` only served `debug/Capture.kt`, which is not imported |
| `extension/ByteBuffer.kt` | `engine-sstp/.../extension/ByteBuffer.kt` | VERBATIM | |
| `extension/Short.kt` | `engine-sstp/.../extension/Short.kt` | VERBATIM | |
| `extension/String.kt` | `engine-sstp/.../extension/String.kt` | MINOR | `toUri()` dropped: UI-only |
| `debug/exception.kt` | `engine-sstp/.../debug/Exceptions.kt` | MINOR | Renamed for ktlint |

### 1.7. Coordination layer

| Upstream path | Destination | Level | Notes |
|---|---|---|---|
| `SharedBridge.kt` | `engine-sstp/.../SstpBridge.kt` | **REWRITTEN** | See §3.1 — this is the file that must change most |
| `control/Controller.kt` | `engine-sstp/.../SstpEngine.kt` | **REWRITTEN** | Becomes the `VpnEngine` implementation |
| `control/LogWriter.kt` | — | **REWRITTEN** | Replaced by `EngineLogEvent` flow in `engine-api`; upstream design informed the event taxonomy |
| `control/NetworkObserver.kt` | — | **NOT IMPORTED** | Superseded by `core-tunnel/NetworkMonitor`, which serves both engines |
| `debug/Capture.kt` | — | **NOT IMPORTED** | Decided in phase 6; see §4 |

---

## 2. Files explicitly NOT imported

Recording exclusions matters as much as recording inclusions: it proves the
import was deliberate rather than a bulk copy.

| Upstream path | Reason |
|---|---|
| `activity/MainActivity.kt` | UI — replaced by Flutter |
| `activity/BlankActivity.kt` | UI |
| `fragment/*` (5 files) | UI |
| `preference/custom/*` (12 files) | Android `PreferenceScreen` UI |
| `preference/accessor/*` (5 files) | `SharedPreferences` access — replaced by Room + `EngineProfile` |
| `preference/app.kt`, `check.kt`, `constant.kt`, `profile.kt` | Preference keys and profile serialisation — replaced by our Room schema |
| `extension/SharedPreferences.kt` | Same |
| `extension/View.kt` | UI |
| `service/SstpVpnService.kt` | Only one `VpnService` may exist; replaced by `CombinedVpnService` |
| `service/SstpTileService.kt` | Replaced by our shared QS tile |
| `configuration.kt` | Global config constants — folded into `EngineProfile.Sstp` defaults |
| `fragment/SaveCertFragment.kt` | Certificate handling — replaced by `core-trust` (see §3.2) |
| `res/*`, `AndroidManifest.xml`, Gradle files | Application shell |

---

## 3. Substantive modifications

The three areas below are where the import stops being mechanical. Each is a
likely source of subtle bugs; review them with more care than the rest.

### 3.1. Removing the `SharedBridge` god-object

Upstream `SharedBridge` holds, in one class: a `SharedPreferences` handle, a
live `VpnService.Builder`, both terminals, all PPP negotiation state, the
control-message channel, and the selected-app list. Every client reaches into it.

It is replaced by three narrower types:

- `SstpEngineConfig` — immutable, built once from `EngineProfile.Sstp`. Read-only
  for all clients. No `SharedPreferences` anywhere.
- `SstpSessionState` — mutable negotiation state (`currentMRU`, `currentAuth`,
  `currentIPv4`, `currentIPv6`, `currentProposedDNS`, `hlak`, `nonce`, `guid`,
  `frameID` allocator). Ownership stays with the engine.
- `ControlMailbox` — the `Channel<ControlMessage>` and the `Where` / `Result`
  enums, carried over as-is.

`Where` and `Result` are imported verbatim. Their granularity is genuinely good
and they map cleanly onto `EngineError`; the mapping table lives in
`docs/ARCHITECTURE.md`.

**`builder` must not appear in any of the three.** If a client can still reach a
`VpnService.Builder`, the abstraction has leaked and Phase 7 will not work.

### 3.2. Replacing certificate handling

Upstream `SSLTerminal.createTrustManagers()` resolves a user-picked directory via
`DocumentFile.fromTreeUri(...)`, iterates its files, and loads every one into a
`KeyStore`. Consequences:

- an external `Uri` may be unresolvable when the service starts under always-on
  VPN before the device is unlocked;
- there is no fingerprint pinning, no expiry check, no per-profile selection;
- a single unparseable file in the directory aborts the whole connection;
- the trust mode is all-or-nothing — no "system, then fall back to custom".

The entire method is deleted and replaced by
`core-trust/TrustManagerFactoryProvider.create(policy, certs, pins)`. Certificate
storage, import, validation and per-profile selection move to `core-trust`.

`SSL_DO_SPECIFY_CERT` and `SSL_CERT_DIR` have no equivalent and are dropped;
`TrustPolicy` supersedes both. If existing OSC users migrate, there is no
automatic path for these settings — document that in the release notes.

### 3.3. Decoupling from `VpnService`

Two call sites bind upstream to the service. Both must move behind
`engine-api`.

**`SSLTerminal.kt:318` — `bridge.service.protect(socket)`.** Replaced by
`protector.protect(socket)` using the injected `SocketProtector`. The call must
happen **before** `connect()` on every socket, including sockets created during
reconnect and the proxy socket when an HTTP proxy is configured. Losing one of
these produces the classic failure where the first connection works and the
reconnect routes into its own tunnel and hangs.

**`IPTerminal` writing to `bridge.builder`.** Upstream calls `addAddress`,
`addDnsServer`, `addRoute` and `allowedApplication` directly on the builder.
Here it returns `TunnelParams` from `connect()`; `CombinedVpnService` builds the
TUN via `core-tunnel/TunnelBuilder` and hands the fd back through `attachTun()`.
Routing policy (default route, private-address routes, custom routes, per-app
rules) leaves the SSTP engine entirely and lives in `core-tunnel`, shared with
L2TP.

---

## 4. Scope decisions requiring a ruling

Upstream carries two capabilities absent from the original plan. Record the
decision here before Phase 6 starts; do not leave it implicit in the code.

**EAP / EAP-MSCHAPv2** (`EAPClient.kt`, `EAPMSAuthClient.kt`, `EAPFrame.kt`).
Not needed for MikroTik or SoftEther, which negotiate MSCHAPv2 directly.
Recommendation: import, keep behind the `pppAuthMethods` set, leave disabled by
default. The code is written and tested upstream; dropping it and re-adding it
later costs more than carrying it.

**HTTP proxy support** (`SSLTerminal.establishProxy()`, `PROXY_*` preferences).
Upstream can tunnel SSTP through an HTTP CONNECT proxy with basic auth.
Recommendation: import. It composes well with SSTP-on-443 in restrictive
networks, which is precisely the scenario SSTP exists for in this project. If
imported, add `proxyHost`, `proxyPort`, `proxyUsername`, `proxyPassword` to
`EngineProfile.Sstp` and to the profile UI in Phase 9 — otherwise the code ships
unreachable, which is worse than not shipping it.

Decision, date, and rationale go here once made:

| Feature | Decision | Date | Rationale |
|---|---|---|---|
| EAP / EAP-MSCHAPv2 | Imported, disabled by default | 2026-08-28 | Carried behind `PppAuthMethod.EAP_MSCHAPV2`, which `EngineProfile.Sstp.DEFAULT_AUTH_METHODS` leaves out. The code is written and tested upstream and some Windows RRAS deployments need it; dropping it and re-adding it later would cost more than carrying it. |
| HTTP proxy | Imported | 2026-08-28 | `EngineProfile.Sstp.proxy` and `ProxyConfig` already exist in the phase 2 contract, so the code ships reachable rather than dead. SSTP on 443 through a CONNECT proxy is the restrictive-network case this fork adds SSTP for. TLS is still terminated at the target server (SPEC 6.4.1). |
| `debug/Capture.kt` | Not imported | 2026-08-28 | It hex-dumps whole frames to `Log.d`: for a PAP `AuthenticateRequest` that is the password in the clear, for MSCHAPv2 the whole challenge/response. SPEC appendix А forbids secrets in logs, and `EngineLogEvent` already carries the redacted trace a bug report needs. |

---

## 5. Verification checklist

Run before every release; a failure here is a licence violation, not a nit.

- [ ] `third_party/open-sstp-client/LICENSE` is a byte-exact copy of upstream's
      `LICENSE` at the recorded commit.
- [ ] Every file listed in §1 carries the header from the "Licensing" section.
- [ ] No file outside §1 carries that header (over-attribution confuses provenance).
- [ ] The commit hash in this document matches the upstream state actually imported.
- [ ] Every §1 destination path exists; every §1 upstream path existed at that commit.
- [ ] `NOTICE` lists Open SSTP Client with its MIT licence and a link.
- [ ] `README.md` credits both upstream projects above the fold.
- [ ] The §4 decision table has no `pending` rows.

A one-shot audit for the first three items:

```bash
# Files that should carry the OSC header
grep -rL "Derived from Open SSTP Client" engine-sstp/src/main/java --include=*.kt

# Files that carry it but shouldn't
grep -rl "Derived from Open SSTP Client" \
  --include=*.kt . | grep -v "^./engine-sstp/"
```

Both commands should return nothing.