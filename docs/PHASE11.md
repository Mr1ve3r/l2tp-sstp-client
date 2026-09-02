# Phase 11 — tests, documentation, release

What phase 11 closed, and the four places where it could not do what SPEC 11
asks in the form it asks for. This file exists so none of that has to be
re-argued from the SPEC text alone.

---

## 11.1 Tests

### What was added

- **RFC vectors for the ported cryptography.** `Md4Test` runs the RFC 1320 §A.5
  suite; `MsChapV2ClientTest` runs the RFC 2759 §9.2 vectors — the NT response,
  the authenticator response, and the two ways verification must fail. Upstream
  Open SSTP Client ships no tests at all, so this arithmetic had no evidence
  behind it before now, and a transcription error in it is indistinguishable
  from a wrong password on a real server.
- **A seam in `MsChapV2Client`.** The peer challenge is a constructor parameter
  defaulting to `SecureRandom`. It exists only because RFC 2759 fixes the peer
  challenge in its vectors; a response computed from a random one can be
  checked against nothing.
- **The migration chain.** `TrustDatabaseMigrationTest` already covered 1 → 2
  and 2 → 3 one step at a time. `version1UpgradesToVersion3InOneRun` adds the
  upgrade a real install actually performs — both migrations, one open — which
  is where the ordering between the `profile_certificate_ref` rebuild and the
  failover tables can break.
- **`StoreMappingTest`.** The parts of the store that are field copying and
  arithmetic rather than SQLite: the entity ↔ summary mapping, the failover
  timeout clamp, the profile secret references, and the enum converters
  including their fallback for a value written by a newer build.

### What was already there

`core-trust` had trust policies, the validator, the parser, the hostname check
and the pre-flight covered before this phase; `core-tunnel` had `TunnelBuilder`;
`engine-sstp` had the PPP and SSTP data-unit codecs, the error mapping and the
TLS transport. Phase 11 did not rewrite any of it.

### AES-CMAC: nothing to test

SPEC 11.1 asks for RFC 4493 §4 vectors for AES-CMAC "in `cipher/hash.kt`".
**There is no AES-CMAC in this fork.** The port kept `hashMd4` and nothing else
from upstream's cipher file; the SSTP crypto binding computes its compound MAC
with `HmacSHA1` or `HmacSHA256` from the JCE, which is the platform's
implementation and not something this project can get wrong. The RFC 4493
criterion is void — not skipped, not deferred. If a CMAC is ever written here,
the vectors come with it.

### Coverage: what the 80% is measured over

SPEC 11.4 asks for `core-trust` coverage of at least 80%.
`:core-trust:jacocoCoverageVerification` enforces it in CI and fails the job
below the line. Two decisions are baked into that number:

- **Only the JVM unit tests feed it.** CI has no device, so the instrumented
  tests do not run there. A gate that passes because half its input never ran
  is worse than no gate, so the classes that exist only to talk to Room, the
  Keystore or the filesystem are *excluded* from the measurement rather than
  counted as covered. Every exclusion in `core-trust/build.gradle.kts` says
  which of those it is.
- **Room entities are excluded; their companions are not.** The bytecode of a
  33-field data class is `equals`, `hashCode`, `toString`, `copy` and
  `componentN` — the compiler's code, not this project's. Counting it as
  untested logic buries the classes that decide something. The hand-written
  functions on the companions are measured and covered.

With those exclusions the module measures 91% of instructions and 95% of lines,
which is where the floor of 80% leaves room for a change to be honest about
what it did not test.

### The manual matrix

[`docs/TEST_MATRIX.md`](TEST_MATRIX.md) records the matrix SPEC 11.1 specifies:
protocol × server × certificate × policy × network, the Android versions, the
security checklist from appendix А, and the artifact checks from 11.4.

**It is a checklist, not a result.** Nobody has run it: it needs a MikroTik and
a SoftEther server, a proxy, five Android versions and a cellular connection.
The rows are written so a filled-in copy is what a release links to, and a row
nobody ran is recorded as *not run* rather than quietly as passed.

---

## 11.2 Documentation

`README.md`, `README.ru.md`, `docs/ARCHITECTURE.md`, `docs/LICENSING.md` and
`docs/DEPENDENCIES.md` existed and were brought up to date — the phase
checklist in both READMEs still said phase 7 was unfinished, and the security
section still described secrets as living in `flutter_secure_storage`, which
stopped being true in phase 8.

Added: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `CHANGELOG.md`.

Two notes on those:

- **`CODE_OF_CONDUCT.md` references the Contributor Covenant rather than
  copying it.** A copied text goes stale silently and nobody notices; a link
  does not.
- **`SECURITY.md` names GitHub private reporting, not an email address.** This
  repository has no published security contact, and inventing one produces a
  channel that swallows reports. If the maintainer adds a real address, it goes
  there.

### `PROVENANCE.md`

SPEC 11.4 asks that it be filled in. It is:
`third_party/open-sstp-client/PROVENANCE.md` names the upstream commit, the
release tag, and the per-file origin of every ported file. It was written in
phase 6 and phase 11 did not change it — but the upstream commit hash in it is
the thing that goes stale, so re-verify it at release time rather than assuming.

---

## 11.3 Release

Already in place before this phase, and unchanged by it: `.github/workflows/release.yml`
builds signed per-ABI release APKs from a `v*.*.*` tag in a reproducible path
layout, publishes them to GitHub Releases with `SHA256SUMS` and a per-file
`.sha256`, and refuses to run if the tag does not match the `pubspec.yaml`
version. The signing key comes from GitHub Secrets and is not in the
repository. F-Droid metadata is in `docs/fdroid/`.

Google Play is out of scope per SPEC 11.3 and does not block a release.

---

## Two open items phase 11 inherited and did not close

- **Proxy-only mode** (SPEC appendix В.8) still fails its L2TP handshake, and
  the attribution experiment described there has not been run. It is inherited
  from TunnelForge, it is not part of the SSTP work, and it is listed in
  `CHANGELOG.md` under *Not implemented* so that no release note claims it
  works. It is **not** in the release matrix.
- **`EngineProfile.customDns`** — appendix В.14 proposed removing it from the
  contract in phase 11 "if no engine ever starts reading it". One does:
  `SstpEngine` reads it when it builds `TunnelParams`, and prefers it over the
  address IPCP proposed. **The field stays.** What is true is that the host
  fills it with an empty list on purpose, because DNS for both protocols is
  configured host-side by `DnsSupport`, which is the only layer that speaks
  DNS-over-TLS and DNS-over-HTTPS. Filling it would apply the same servers by a
  second path. The field is the engine-level override; nothing currently sets
  it, and that is a decision rather than an omission.
