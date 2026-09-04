# Manual device test — phases 1 and 3

Everything here needs a real device and a real L2TP/IPsec server, so none of it
can be checked in CI or on a development machine. It closes the acceptance
criteria that phases 1 and 3 leave open.

**What is being tested:** phase 3 moved TUN construction out of
`TunnelVpnService` into `core-tunnel`'s `TunnelBuilder`. The interface built is
meant to be byte-for-byte the same as before. Anything that behaves differently
from the build you were running before is a regression, however small.

**Build under test:** branch `spec/phase-1`, commit `81a209e` or later.

**Getting an APK.** Local `assembleDebug` is currently blocked by an unrelated
Windows filesystem problem, so take the artifact from CI instead: the
`build-debug-apk` job on the branch publishes `l2tp-sstp-client-debug-apk`.

**Baseline.** Where a check says "same as before", compare against the upstream
build you were using previously. If you no longer have it, note what happens now
and say so — an absolute observation is still useful, it just cannot prove
"unchanged".

**Status: complete.** Run on a Nothing A142, Android 16 (API 36), against a
MikroTik L2TP/IPsec server, 2026-08-25. Results are recorded per item below.
The first round found one real defect — see "What this found" at the end.

---

## Gating checks

**1. Basic L2TP/IPsec connection.**
*Result: `pass`, after the native Quick Mode fix.*

Connect to your MikroTik (IKEv1, PSK). The tunnel comes up, the status screen
shows an IP, and traffic flows. `pass` if it connects exactly as it did before.

**2. Assigned address is correct.**
*Result: `pass`.*

On the status screen, the IP is the one the server assigned via IPCP, not
`10.0.0.2`. `10.0.0.2` is the fallback used when IPCP gives nothing usable, so
seeing it means the negotiated address was lost on its way to the builder.

**3. DNS in automatic mode.**
*Result: `pass`.*

With DNS set to automatic, name resolution works and the servers shown are the
ones the server proposed, in the same order.

**4. DNS in manual mode.**
*Result: `pass` over UDP; TLS and HTTPS skipped by the owner.*

Set two custom DNS servers, ideally with different protocols (UDP and TLS).
Resolution works, and both are used.

**5. MTU is applied.**
*Result: `pass` via R1: 1300, 1100 and 1150 all reported correctly.*

Set a distinctive MTU in the profile — 1300, say — reconnect, and confirm the
interface reports that value rather than the default.

**6. Full-device routing.**
*Result: `pass`.*

With per-app routing off, every application's traffic goes through the tunnel.
Check your public IP from a browser and from some other app.

---

## Regression checks

**7. Per-app routing, inclusive.**
*Result: `pass`.*

Select two applications. Their traffic goes through the tunnel; everything else
does not. Confirm both directions — a browser inside the list showing the VPN
address, and one outside it showing your normal address.

**8. Per-app routing, exclusive.**
*Result: `pass`.*

Select one application to exclude. That application shows your normal address,
everything else shows the VPN address.

**9. An uninstalled application in a routing rule.**
Add an application to a routing list, uninstall it, then connect. Expected: the
connection fails with the message `Package not installed: <package>` — the same
wording as before. This checks the exception rewrapping; a different message, or
a crash, is a fail.

**10. Traffic to the VPN server itself bypasses the tunnel.**
*Result: `pass` via R2: `excludeRoute <server>/32` present, `excludedRoutes=1`.*

Connect, then check the log for a line mentioning `excludeRoute` and the server
address. On Android 13 or newer it should say the exclusion was applied; on
Android 12 it should say it is relying on socket protection instead. Either is
correct for the respective version — the fail case is neither line appearing, or
the address in it being wrong.

**11. Reconnect across a network change.**
Connect over Wi-Fi, switch to mobile data, wait. The tunnel recovers, and
afterwards traffic still flows. Then disconnect and reconnect within the same
session and confirm it comes up again.

---

## Decisions taken

Both were resolved by the project owner on 2026-08-25.

**12. This application is excluded from the tunnel.** SPEC 3.1 asked for it and
the owner confirmed. This changed more than one field: upstream actively added
the app to the inclusive list, with two tests asserting it, so
`effectiveInclusivePackages` now filters the package out instead and those
tests were rewritten. Item R3 below re-checks the mode this affects.

**13. `setBlocking` stays unset.** Upstream never calls it, so the descriptor
handed to the native L2TP loop keeps its current read semantics. This matches
what `TunnelConfig` already defaulted to; no code changed.

## Round two

Against the build that carries the native Quick Mode fix, the self-exclusion,
and the enriched log line. Items 1-3 and 6-8 passed already and only need a
glance to confirm nothing regressed.

**R1. MTU is applied.** Set an unusual MTU, connect, and read the `TUN
established` line, now at INFO:

```
TUN established address=10.x.x.x/32 mtu=1300 dnsServers=2 excludedRoutes=1 perApp=AllApps
```

`mtu=` must be what the profile says. This closes item 5, which could not be
checked before because nothing reported it.

**R2. The server route exclusion is applied.** Switch the log filter in the app
to Debug — the default is Info, which is why item 10 looked empty — and look
for `excludeRoute <server>/32`. `excludedRoutes=1` on the R1 line is the
shorter version of the same check.

**R3. Inclusive routing still works with the app excluded.** This is the change
with the most risk in it, because it reverses something upstream did
deliberately. Put one browser in the inclusive list and connect. The browser
must show the VPN address, everything else must not. Then repeat with manual
DNS configured, since the app's DNS bridge is the part most likely to care
about the app's own routing.

**R4. Exclusive routing still works.** As item 8, but confirm the app itself is
now outside the tunnel too.

*Results: R1 `pass` — 1300, 1100, 1150 all reported, no errors on either side.
R2 `pass`. R3 `pass` with automatic and with manual DNS. R4 `pass` with
automatic and with manual DNS.*

## What this found

The checklist paid for itself on item 1, which failed outright.

The cause was not in phase 3. Quick Mode aborted on the first Informational
exchange that arrived in place of QM2, reporting INITIAL-CONTACT — a status
notification — as a rejected proposal. Whether that Informational arrived
before or after QM2 is a race, and native code is built `-O0` for debug
variants, so debug builds lost it every time while release builds won it every
time. That is why upstream's release APK worked and every build here did not.
Fixed in `ikev1.c`; see docs/ARCHITECTURE.md.

Establishing that it was not phase 3 took a control build: the same debug APK
at the commit before the `TunnelBuilder` wiring failed identically, which ruled
out the only commit that touches `:app` runtime code.

## Out of scope for phase 3

**Item 11, reconnect across a network change.** Reported as "nothing happens".
That is upstream behaviour, not a regression: there is no reconnect logic in
the app at all — no `NetworkCallback`, no network-change handling. `core-tunnel`
now ships `NetworkMonitor` as the building block, but nothing consumes it yet.
The SPEC places this in phase 4 for L2TP.

**Item 9, uninstalled package in a routing rule.** The connection succeeded
where the code says it should have failed with `Package not installed`. Still
unexplained; the package lists are sent unfiltered and nothing checks
installation. Being followed up separately along with the counter mismatch.

**Item 4, DNS over TLS and HTTPS.** UDP verified; the owner chose to skip the
rest.

## Known and excluded

Not regressions, do not spend time on them:

- `ProxyServerRuntimeTest.httpConnectSuppressesClientAbortWhenErrorResponseCannotBeWritten`
  fails on Windows. It fails identically without any phase 3 change and passes
  on the Linux CI runner. Tracked separately.
- `assembleDebug` does not complete on this Windows machine, from a filesystem
  problem also affecting pub and clang. CI builds it fine.
