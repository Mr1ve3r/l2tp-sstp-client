# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Numbering starts at `0.1.0` for this fork rather than continuing
[TunnelForge](https://github.com/evokelektrique/tunnel-forge)'s: the fork has
its own application id and its own signing key, so it is a different
application and inheriting a version history it did not have would mislead. A
release tag must be `v<the pubspec version>` — the release workflow refuses to
publish otherwise.

## [Unreleased]

## [0.2.0]

The fork's own artwork, in every place Android draws it.

### Added

- **An adaptive launcher icon.** `minSdk` is 31, so every device masks its own
  icons; with only the legacy bitmap the launcher shrank the square onto a
  white plate and the logo became a tile inside someone else's circle. The
  background is the logo's own plate colour and the foreground inset is 20%,
  measured from the glyph column's half-diagonal against the guaranteed-visible
  circle rather than taken from the default.
- **A monochrome layer** for Android 13+ themed icons, derived from the artwork
  by reading each pixel's distance from the plate colour towards the glyph
  colour as alpha, so antialiased edges survive.

### Changed

- **The startup screen shows this application's logo.** It still showed
  upstream's monogram. On Android 12+ the splash icon is masked to a circle of
  two thirds of the image, so the glyphs are scaled to 52% of the side against
  the 62% that circle allows.
- **The Quick Settings tile and both service notifications** use the logo
  silhouette instead of the platform padlock and share glyphs. Android tints
  these by alpha, so the colour bitmap the tile had briefly been pointed at
  would have rendered as a solid block.
- The F-Droid listing icon and the proxy notification title, both of which the
  rename had missed.

## [0.1.0]

The first release of this fork: SSTP alongside the inherited L2TP/IPsec, built
to the phases in [`SPEC`](SPEC).

### Identity

- **Own application id**, `io.github.mr1ve3r.l2tpsstp`, and own name,
  **L2/SS/TP**. It installs alongside upstream TunnelForge rather than over it;
  neither can update the other.
- **Update checks point at this repository.** An APK from upstream is signed
  with a different key and cannot install over this one, so offering it as an
  update would produce a failure the user could not act on.
- The Persian F-Droid listing was replaced by a Russian one, matching the
  locales the application actually ships.

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

[Unreleased]: https://github.com/Mr1ve3r/l2tp-sstp-client/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Mr1ve3r/l2tp-sstp-client/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Mr1ve3r/l2tp-sstp-client/releases/tag/v0.1.0
