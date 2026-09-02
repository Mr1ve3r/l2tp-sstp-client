# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version in `pubspec.yaml` was inherited from
[TunnelForge](https://github.com/evokelektrique/tunnel-forge) and carried
forward, so the numbering continues upstream's rather than restarting. A
release tag must be `v<the pubspec version>` — the release workflow refuses to
publish otherwise.

## [Unreleased]

Everything below is the fork's own work: SSTP alongside the inherited
L2TP/IPsec, built to the phases in [`SPEC`](SPEC). It has not been tagged yet.

### Added

- **SSTP over TLS on port 443**, as a second protocol beside L2TP/IPsec. The
  engine is derived from
  [Open SSTP Client](https://github.com/kittoku/Open-SSTP-Client) (MIT) and
  reworked to fit this project's engine contract: PPP with PAP, CHAP, MSCHAPv2
  and EAP-MSCHAPv2, IPCP and IPv6CP, the SSTP control channel and its crypto
  binding.
- **SSTP through an HTTP proxy**, with and without proxy authentication. TLS is
  verified against the SSTP server, never against the proxy.
- **A server certificate store** with four trust policies — `SYSTEM`,
  `CUSTOM_ONLY`, `PIN_LEAF` and, in debug builds only, `INSECURE` — plus
  fingerprint pinning, an `expectedHostname` field for certificates issued to a
  name the server is not reached by, and a pre-flight check that explains what
  a profile's policy will and will not accept before the connection is
  attempted.
- **Certificate import** from a file or from the server's own offered chain,
  with the parsed certificate shown before anything is trusted. Certificates
  are copied into internal storage rather than referenced by `Uri`, so they can
  be read by an always-on tunnel before the device is unlocked.
- **A protocol-agnostic engine contract** (`engine-api`) with one error
  vocabulary for both protocols, and a shared tunnel layer (`core-tunnel`) that
  builds the interface, protects sockets and watches the network. Engines never
  see `VpnService`: Android allows one, and the host owns it.
- **Profiles in Kotlin, in Room**, with secrets held under an Android Keystore
  key rather than in the database. Profiles migrate from the Flutter-side
  storage on first read.
- **Profile export**, without secrets by default; the encrypted container
  exists on every layer below the UI.
- **Failover groups** (SPEC 10.1): an ordered list of profiles tried one after
  another with a per-member timeout. `NetworkUnreachable`, `TimedOut` and
  `IpsecFailed` advance to the next member; an authentication failure stops the
  group, because walking the list with a wrong password only spreads failed
  logins across servers.
- **A Quick Settings tile**, and per-protocol tagging and filtering in the log
  view.
- **Russian localisation**, with every string moved into ARB files.
- Documentation: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
  [`docs/LICENSING.md`](docs/LICENSING.md),
  [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md),
  [`docs/TEST_MATRIX.md`](docs/TEST_MATRIX.md), `CONTRIBUTING.md`,
  `CODE_OF_CONDUCT.md`, `SECURITY.md`, and
  `third_party/open-sstp-client/PROVENANCE.md` recording the origin of every
  ported file.
- CI running the Dart, native, Go and Android module test suites, ktlint over
  the modules this fork adds, and a coverage floor on `core-trust`. A release
  workflow builds signed per-ABI APKs from a tag and publishes them with
  `SHA256SUMS`.

### Changed

- The L2TP/IPsec engine is now behind the same `VpnEngine` contract as SSTP,
  and one service dispatches on the profile's protocol. The native engine
  itself was not rewritten.
- The native layer now distinguishes IKE, IPsec and L2TP failures instead of
  reporting one generic error, so a failover group can tell "wrong password"
  from "server unreachable".
- The tunnel is rebuilt by the host on a network change rather than by the
  engine, and this application stays outside its own tunnel.

### Fixed

- ESP transmission after a network change no longer fails silently.
- Stopping an SSTP session no longer crashes the service.
- The certificate import dialogs survive their own controllers being disposed.
- IKE quick mode no longer gives up when an Informational exchange is
  interleaved before QM2.

### Not implemented

- **Auto-selection by network** (SPEC 10.2) was dropped by decision of the
  project owner. Reading the SSID on Android 10 and later needs location
  permission, which the SPEC rules out. See
  [`docs/PHASE10.md`](docs/PHASE10.md).
- **Proxy-only mode**, inherited from TunnelForge, fails its L2TP handshake and
  has not been attributed to a cause. See SPEC appendix В.8.

[Unreleased]: https://github.com/Mr1ve3r/l2tp-sstp-client/commits/main
