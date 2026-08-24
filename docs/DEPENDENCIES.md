# Dependencies

Every dependency added to this project must be listed here with its licence and
a note on GPL-3.0 compatibility (SPEC rule 0.3.4). The project as a whole is
distributed under GPL-3.0-or-later, so each dependency must be distributable
under those terms.

Versions are declared in [`android/gradle/libs.versions.toml`](../android/gradle/libs.versions.toml)
and [`pubspec.yaml`](../pubspec.yaml); this file records *why* a dependency is
present and under what licence, not which version is current.

## Android / Kotlin

| Dependency | Licence | GPL-3.0 compatible | Used by | Purpose |
|---|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | Apache-2.0 | Yes | all Kotlin modules | Structured concurrency; `StateFlow`/`SharedFlow` in the engine contract |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache-2.0 | Yes | `core-tunnel`, `engine-l2tp`, `engine-sstp` | Android main-thread dispatcher |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | Apache-2.0 | Yes | tests | Deterministic coroutine tests |
| `androidx.annotation:annotation` | Apache-2.0 | Yes | `:app` and library modules | Nullability and threading annotations |
| `androidx.core:core-ktx` | Apache-2.0 | Yes | `:app`, `core-tunnel` | Android platform helpers |
| `io.netty:netty-codec-http` | Apache-2.0 | Yes | `:app` | Local HTTP CONNECT proxy frontend (inherited from TunnelForge) |
| `io.netty:netty-codec-socks` | Apache-2.0 | Yes | `:app` | Local SOCKS5 proxy frontend (inherited from TunnelForge) |
| `junit:junit` | EPL-1.0 | Yes (test scope only, not distributed) | tests | Unit tests |
| `androidx.test.ext:junit`, `androidx.test:runner` | Apache-2.0 | Yes (test scope only) | instrumented tests | Instrumented tests |

Apache-2.0 is one-way compatible with GPL-3.0: Apache-2.0 code may be combined
into a GPL-3.0 work, not the other way round. That direction is the one we need.

EPL-1.0 is *not* GPL-compatible in general. JUnit is acceptable here only because
it is a test-scope dependency that is never shipped in the APK. Do not move a
test-scope EPL dependency to `implementation`.

## Build tooling

Build-time only; not linked into the shipped artifact.

| Tool | Licence | Purpose |
|---|---|---|
| Android Gradle Plugin | Apache-2.0 | Android builds |
| Kotlin Gradle Plugin | Apache-2.0 | Kotlin compilation |
| `org.jlleitschuh.gradle.ktlint` | Apache-2.0 | Kotlin style checks on the modules added by this fork |
| CMake / Android NDK | BSD-3-Clause / NDK licence | Native L2TP engine |
| Go toolchain | BSD-3-Clause | `android/gvisor` userspace networking module |

## Inherited source, not dependencies

Two bodies of source are vendored rather than depended on. They are covered in
[`LICENSING.md`](LICENSING.md) and
[`../third_party/open-sstp-client/PROVENANCE.md`](../third_party/open-sstp-client/PROVENANCE.md).

| Source | Licence |
|---|---|
| TunnelForge (base application, native L2TP engine) | GPL-3.0 |
| Open SSTP Client (SSTP and PPP implementation) | MIT |

## Adding a dependency

1. Check the licence and confirm it is GPL-3.0 compatible in the *inbound*
   direction. Permissive (MIT, BSD, Apache-2.0, ISC) is fine. Anything
   copyleft-incompatible (EPL, CDDL, MPL in some combinations) is not, outside
   test scope.
2. Add the version to `libs.versions.toml`, never to a module build file.
3. Add a row above, in the same commit.
4. No analytics, crash reporting or telemetry SDKs. The application makes no
   network connections other than to the user's own VPN server (SPEC rule 0.3.5).
