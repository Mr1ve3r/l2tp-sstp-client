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

**Reporting.** Answer each numbered item `pass`, `fail`, or `skip`, with a note
on anything surprising. Items 1–6 are the ones that gate the phase; 7–11 are
regressions I consider plausible given what changed; 12–13 are decisions I need
a ruling on rather than pass/fail.

---

## Gating checks

**1. Basic L2TP/IPsec connection.**
Connect to your MikroTik (IKEv1, PSK). The tunnel comes up, the status screen
shows an IP, and traffic flows. `pass` if it connects exactly as it did before.

**2. Assigned address is correct.**
On the status screen, the IP is the one the server assigned via IPCP, not
`10.0.0.2`. `10.0.0.2` is the fallback used when IPCP gives nothing usable, so
seeing it means the negotiated address was lost on its way to the builder.

**3. DNS in automatic mode.**
With DNS set to automatic, name resolution works and the servers shown are the
ones the server proposed, in the same order.

**4. DNS in manual mode.**
Set two custom DNS servers, ideally with different protocols (UDP and TLS).
Resolution works, and both are used.

**5. MTU is applied.**
Set a distinctive MTU in the profile — 1300, say — reconnect, and confirm the
interface reports that value rather than the default.

**6. Full-device routing.**
With per-app routing off, every application's traffic goes through the tunnel.
Check your public IP from a browser and from some other app.

---

## Regression checks

**7. Per-app routing, inclusive.**
Select two applications. Their traffic goes through the tunnel; everything else
does not. Confirm both directions — a browser inside the list showing the VPN
address, and one outside it showing your normal address.

**8. Per-app routing, exclusive.**
Select one application to exclude. That application shows your normal address,
everything else shows the VPN address.

**9. An uninstalled application in a routing rule.**
Add an application to a routing list, uninstall it, then connect. Expected: the
connection fails with the message `Package not installed: <package>` — the same
wording as before. This checks the exception rewrapping; a different message, or
a crash, is a fail.

**10. Traffic to the VPN server itself bypasses the tunnel.**
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

## Decisions I need from you

Two SPEC requirements contradict what upstream TunnelForge deliberately does. I
implemented neither, defaulting to upstream behaviour, because phase 3 is
supposed to be behaviour-preserving. Both are one field in `TunnelConfig`.

**12. Should this application's own traffic bypass the tunnel?**
SPEC 3.1 asks for the app's own package to be excluded. Upstream works at
keeping it *inside*: `effectiveInclusivePackages` adds the app to the inclusive
list, and `requestedExclusivePackages` filters it out of the exclusive one. That
is too deliberate to be an oversight, so I left it alone.

What I need: does anything in the app need to reach the network from outside the
tunnel? If not, upstream's behaviour is fine and the SPEC line should be dropped.

**13. Should `setBlocking(true)` be set on the TUN?**
SPEC 3.1 asks for it. Upstream never calls it, so the descriptor is
non-blocking, and that descriptor goes straight to the native L2TP poll loop.
Switching it changes read semantics for C code that phase 4 must not disturb.

What I need: is the native loop written to expect a blocking descriptor? If it
polls, leaving this alone is correct. This is the sort of thing that would work
in testing and fail under packet loss, so I would rather not guess.

---

## Known and excluded

Not regressions, do not spend time on them:

- `ProxyServerRuntimeTest.httpConnectSuppressesClientAbortWhenErrorResponseCannotBeWritten`
  fails on Windows. It fails identically without any phase 3 change and passes
  on the Linux CI runner. Tracked separately.
- `assembleDebug` does not complete on this Windows machine, from a filesystem
  problem also affecting pub and clang. CI builds it fine.
