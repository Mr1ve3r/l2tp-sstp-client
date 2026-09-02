# Contributing

Thanks for looking. This is a VPN client, so the bar for changes is a little
higher than the usual "does it compile" — a mistake here does not crash, it
silently sends someone's traffic somewhere it should not go.

Before anything else: **security problems do not go in issues or pull
requests.** See [`SECURITY.md`](SECURITY.md).

## What this project is

A fork of [TunnelForge](https://github.com/evokelektrique/tunnel-forge) that
adds SSTP, using the engine from
[Open SSTP Client](https://github.com/kittoku/Open-SSTP-Client). The scope and
the phase-by-phase plan it was built to are in [`SPEC`](SPEC) (Russian), and
the decisions taken along the way are in its appendix В. If a change looks
like it contradicts the SPEC, read that appendix first — it usually records why.

The architecture is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). The one
thing to internalise before touching anything: **engines never see
`VpnService`.** An engine gets a TUN file descriptor and a `SocketProtector`;
the host owns the interface. Android permits one active `VpnService`, so an
engine that configures its own cannot coexist with the other engine.

## Getting a build

```bash
flutter pub get
flutter build apk --debug
```

You need the Flutter SDK version pinned in `.github/workflows/ci.yml`, a JDK 17,
the Android SDK with the NDK, and Go (the `android/gvisor` module builds during
`preBuild`). `android/local.properties` is not in the repository; create it with
`flutter.sdk` and `sdk.dir` pointing at your SDKs.

## What CI will check

Run these before opening a pull request. They are the same gates the
`ci.yml` workflow runs, and they fail in this order:

```bash
dart format --output=none --set-exit-if-changed lib test
```

```bash
flutter analyze
```

```bash
flutter test --coverage
```

```bash
cd android && ./gradlew ktlintCheck
```

```bash
cd android && ./gradlew :app:testDebugUnitTest :engine-api:testDebugUnitTest :engine-l2tp:testDebugUnitTest :engine-sstp:testDebugUnitTest :core-tunnel:testDebugUnitTest :core-trust:testDebugUnitTest
```

```bash
cd android && ./gradlew :core-trust:jacocoCoverageVerification
```

`ktlintCheck` covers the modules this fork adds (`engine-*`, `core-*`) and not
`:app`, whose sources come from upstream — reformatting those would bury real
changes under whitespace.

`:core-trust:jacocoCoverageVerification` enforces the coverage floor SPEC 11.4
sets for the trust module. If your change drops it, the answer is a test, not a
new exclusion — the exclusion list in `core-trust/build.gradle.kts` is for code
that cannot run off a device, and every entry says why.

The instrumented tests (`src/androidTest`) need a device or emulator and do not
run in CI. Run them yourself if you touch storage, migrations or the certificate
store:

```bash
cd android && ./gradlew :core-trust:connectedDebugAndroidTest
```

## Tests

Every change to behaviour needs a test that fails without it. Some specifics
for this codebase:

- **Ported cryptography is tested against the RFC, not against itself.** MD4
  has the RFC 1320 suite, MSCHAPv2 has the RFC 2759 §9.2 vectors. A wrong
  response is indistinguishable from a wrong password on a real server, so a
  self-consistent test would prove nothing.
- **Room migrations are tested with `MigrationTestHelper` against the exported
  schema.** Changing an entity means a new schema file under
  `core-trust/schemas/`, a migration, and a test — including one that runs the
  whole chain from the oldest version, because that is the upgrade a real
  install performs.
- **Anything touching sockets is tested for `protect()`**, including on
  reconnect. An unprotected socket on the second connection routes into the
  tunnel it is meant to carry, and it looks exactly like a hang.
- Changes that can only be verified on a device belong in the manual matrix,
  [`docs/TEST_MATRIX.md`](docs/TEST_MATRIX.md).

## Style

Match the surrounding code. Two conventions worth naming because they are not
the defaults:

- **Comments say why, not what.** The codebase is full of comments explaining
  which constraint forced a shape. Keep that up, and delete comments that only
  restate the line below.
- **ktlint's continuation indent** is what catches most hand-written Kotlin
  here: a named argument whose value starts on the next line wants +4 from the
  argument, not +8. Binding the expression to a local first avoids the question.

Ported files from Open SSTP Client carry an attribution header. Keep it, on the
file and on anything split out of it — renaming a package does not end the MIT
obligation. See [`docs/LICENSING.md`](docs/LICENSING.md) and
`third_party/open-sstp-client/PROVENANCE.md`.

## Pull requests

- One change per pull request.
- Say what breaks without it, and how you verified it. "Tested on a Pixel 7,
  Android 15, MikroTik SSTP with a pinned self-signed certificate" is worth more
  than a paragraph of description.
- If a change alters what the application will accept as a server, say so in the
  first line. That is the review everyone should be looking at.
- New or changed dependencies need a line in
  [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md) with the licence, and a licence
  compatible with GPL-3.0-or-later.
- User-visible changes need a `CHANGELOG.md` entry under **Unreleased**.

By contributing you agree your work is distributed under GPL-3.0-or-later.
