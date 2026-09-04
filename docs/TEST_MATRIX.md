# Release test matrix

The manual pass that stands between a tag and a release (SPEC 11.1, 11.4).

Everything in the automated suite runs on every push; what is here is what no
CI runner can do — a real device, a real server, a real network that drops UDP.
The matrix is not a suggestion of things to look at. SPEC 11.4 makes "the whole
matrix passed" an acceptance criterion for the project, so a release either has
a filled-in copy of this file behind it or it does not go out.

**How to use it:** copy this file into the release issue, fill the result
columns, and link the log export for anything that failed. A row nobody ran is
recorded as *not run*, never as passed — the point of writing the result down is
that "we think it works" and "someone watched it work" stop looking alike.

The step-by-step for most of these already exists in the per-phase manual test
documents; this file says *what* combination must be exercised and points at
the document that says *how*.

| Phase document | What it walks through |
|---|---|
| [`MANUAL_TEST_PHASE4.md`](MANUAL_TEST_PHASE4.md) | L2TP/IPsec connect, reconnect, proxy-only mode |
| [`MANUAL_TEST_PHASE5.md`](MANUAL_TEST_PHASE5.md) | Certificate import, trust policies, pre-flight |
| [`MANUAL_TEST_PHASE6.md`](MANUAL_TEST_PHASE6.md) | SSTP connect against MikroTik and SoftEther |
| [`MANUAL_TEST_PHASE7.md`](MANUAL_TEST_PHASE7.md) | One service, protocol dispatch, network change |
| [`MANUAL_TEST_PHASE8.md`](MANUAL_TEST_PHASE8.md) | Profile storage, migration, always-on, tile |
| [`MANUAL_TEST_PHASE9.md`](MANUAL_TEST_PHASE9.md) | The Flutter UI end to end |
| [`MANUAL_TEST_PHASE10.md`](MANUAL_TEST_PHASE10.md) | Failover groups |

**Instrumented tests need a device too.** They are in no `make` target, because
nothing in CI can run them, and they cover the one thing a JVM run cannot: the
platform provider is Conscrypt, not the JDK's, and the two differ over chain
cleanup and over a chain whose first element is itself a trust anchor. Run them
against an attached device before a release:

```
cd android && ./gradlew :core-trust:connectedDebugAndroidTest
```

A failure there that does not reproduce on the JVM is a real finding about the
device, not a flaky test.

---

## 1. Protocol × server × certificate × policy × network

The matrix SPEC 11.1 specifies, one row per combination. Run each row on both
Wi-Fi and cellular where the row says so — the two are not interchangeable:
cellular is where CGNAT, a smaller path MTU and blocked UDP/500 live, and every
protocol-selection decision this application makes exists because of them.

| # | Protocol | Server | Certificate | Policy | Network | Result | Notes |
|---|---|---|---|---|---|---|---|
| 1 | L2TP/IPsec | MikroTik | — | — | Wi-Fi | | |
| 2 | L2TP/IPsec | MikroTik | — | — | LTE | | |
| 3 | SSTP | MikroTik | self-signed | `PIN_LEAF` | Wi-Fi | | |
| 4 | SSTP | MikroTik | self-signed | `PIN_LEAF` | LTE | | |
| 5 | SSTP | MikroTik | own CA | `CUSTOM_ONLY` | Wi-Fi | | |
| 6 | SSTP | MikroTik | own CA | `CUSTOM_ONLY` | LTE | | |
| 7 | SSTP | SoftEther | public CA | `SYSTEM` | Wi-Fi | | |
| 8 | SSTP | SoftEther | public CA | `SYSTEM` | LTE | | |
| 9 | SSTP via proxy | MikroTik | own CA | `CUSTOM_ONLY` | Wi-Fi | | |
| 10 | SSTP via proxy, proxy auth | MikroTik | own CA | `CUSTOM_ONLY` | Wi-Fi | | |
| 11 | Both | — | — | — | network switched mid-session | | |
| 12 | SSTP | MikroTik | own CA, nothing selected | `STORE_AUTO` | Wi-Fi | | |
| 13 | SSTP | MikroTik | own CA plus an unrelated CA | `STORE_AUTO` | Wi-Fi | | |
| 14 | SSTP | MikroTik | store emptied | `STORE_AUTO` | Wi-Fi | | |

What "passed" means for a row, beyond the tunnel coming up:

- **Traffic actually leaves through the tunnel.** An address-check page shows
  the server's address, not the carrier's. A tunnel that connects and routes
  nothing is the failure this catches.
- **DNS resolves, and through the configured resolver.** Both engines leave DNS
  to the host (`DnsSupport`), so this is checked once per protocol, not per row.
- **Disconnect leaves nothing behind.** The notification clears, the interface
  goes, and connecting again works without a reboot.
- **Rows 9 and 10:** TLS is verified against the *SSTP server*, not the proxy
  (SPEC appendix А and appendix Б item 9). Point the proxy at a host whose
  certificate would fail the profile's policy; the connection must still be
  judged by the target server's certificate.
- **Rows 12 and 13:** the tunnel comes up with no certificate ticked on the
  profile, and the log names the anchor that vouched -- on row 13 it must name
  the server's own CA and not the unrelated one. Row 13 is the audit trail the
  mode depends on; without it the widening it buys would be invisible.
- **Row 14:** delete every certificate and connect. The pre-flight must refuse
  before the socket opens, naming an empty store, rather than timing out in the
  handshake.
- **Row 11:** switch Wi-Fi off mid-session and back on. The session either
  survives or reconnects; it must not sit "connected" while carrying nothing.
  This is what appendix В.7 is about.

### Known open item

Proxy-only mode (SPEC appendix В.8) fails with *L2TP handshake failed* and the
attribution work described there has not been done. It is **not** part of rows
1–11 and it is not a release blocker for the SSTP work, but a release note that
claims proxy-only mode works would be wrong.

---

## 2. Android versions

`minSdk` is 31, so the floor is **Android 12**. SPEC 11.1 asks for the floor
plus the four most recent releases.

| Android | API | Device / emulator | Connect (L2TP) | Connect (SSTP) | Always-on | Result |
|---|---|---|---|---|---|---|
| 12 (min supported) | 31 | | | | | |
| 13 | 33 | | | | | |
| 14 | 34 | | | | | |
| 15 | 35 | | | | | |
| 16 | 36 | | | | | |

On the newest API in the list, check `VpnService` and always-on specifically —
that is where the platform keeps changing:

- Always-on with **block connections without VPN** enabled: the tunnel comes up
  after a reboot without the application being opened, and no traffic escapes
  before it does.
- Always-on with a **certificate-pinned SSTP profile**: the certificate is read
  from internal storage before the device is unlocked. An external `Uri` breaks
  here, which is why appendix Б item 3 exists.
- The **notification** survives the connection: it is a foreground service, and
  a platform that kills it silently is the interesting result.
- **Per-app routing** still routes what it claims to after the OS upgrade.

---

## 3. Security checklist

SPEC appendix А, to be re-checked before each release rather than assumed from
the last one. The items that need a device or a build artifact rather than a
code read:

- [ ] No VPN password, PSK or proxy password appears in a debug-level log
      export. Connect with all three set, export the log, grep it.
- [ ] Log export goes through the secret filter.
- [ ] The `INSECURE` trust policy is unreachable in a release build.
- [ ] Every socket is protected, including the proxy socket and every socket
      created on reconnect (appendix Б item 1).
- [ ] Certificates live in internal storage with `0700`.
- [ ] Secrets are in the Keystore-backed store, not in the database.
- [ ] `android:allowBackup="false"`, or backup rules that exclude the secrets.
- [ ] No exported components beyond the ones that must be.
- [ ] Fingerprint comparison is constant-time.
- [ ] The default `minTlsVersion` is at least TLS 1.2.

---

## 4. Release artifact checks

SPEC 11.4's remaining two criteria, which are about the built APK rather than
about behaviour:

- [ ] **No network traffic except the user's VPN server.** Check the manifest
      for what the application may reach, then trace a session: with the tunnel
      down and the application open, nothing should leave the device.
- [ ] **Licensing and attribution are in place.** `LICENSE`, `NOTICE`,
      `COPYRIGHT`, [`LICENSING.md`](LICENSING.md), and
      `third_party/open-sstp-client/PROVENANCE.md` with the upstream commit it
      names still matching what was imported.
- [ ] `SHA256SUMS` published beside the APKs, and one checksum verified by hand
      against a downloaded file.
