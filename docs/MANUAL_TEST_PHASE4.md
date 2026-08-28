# Manual device test — phase 4

Everything here needs a real device and a real L2TP/IPsec server, so none of it
can be checked in CI or on a development machine. It closes the acceptance
criteria of SPEC phase 4.2, which are all regression criteria.

**What is being tested:** phase 4 moved the native L2TP path out of
`TunnelVpnService` and behind `L2tpEngine : VpnEngine`. Negotiation, the poll
loop, socket protection and the exit-code-to-error mapping all changed hands;
the C code under `android/app/src/main/cpp/` did not change at all. Nothing
about the connection is supposed to behave differently. **Anything that differs
from the build you were running before is a regression, however small.**

One deliberate change rides along: `setBlocking(true)` is now set on the tunnel
interface, which upstream never did (SPEC В.1). Item 11 covers it.

**Build under test:** branch `spec/phase-1`, the phase 4 commit or later.

**Getting an APK.** Local `assembleDebug` is blocked by an unrelated Windows
filesystem problem, so take the artifact from CI: the `build-debug-apk` job on
the branch publishes `l2tp-sstp-client-debug-apk`.

**Baseline.** The phase 3 build, whose results are in
[`MANUAL_TEST_PHASE3.md`](MANUAL_TEST_PHASE3.md). Where a check says "same as
before", that is what it means.

**Status: complete.** Run against a MikroTik L2TP/IPsec server, 2026-08-28.
Results are recorded per item below. Every SPEC 4.2 gating criterion passed;
two observations came out of it, both written up under "What this found".

---

## Gating checks

These are SPEC 4.2 verbatim. All of them must pass.

**1. Connection to a real server.**
*Result: `pass`.*

Connect to the MikroTik (IKEv1, PSK). It connects, the status screen shows the
IPCP-assigned address, and traffic flows.

**2. Throughput and stability are no worse than baseline.**
*Result: `pass`. Same speed as the phase 3 build.*

Run a speed test and compare with the phase 3 figure. Then leave the tunnel up
for at least ten minutes with traffic on it. `fail` on a drop in throughput, on
a stall, or on a disconnection the baseline did not have.

**3. Reconnect across Wi-Fi → LTE.**
*Result: `pass`, with an observation. The tunnel switches networks and recovers
every time. In two runs out of five the log then carried
`esp_encrypt_send: sendto failed errno=101 plain_len=78 pkt_len=132 udp_encap=1`
for about half a minute, while the tunnel stayed up and traffic flowed both
ways. See finding F1.*

With the tunnel up, turn Wi-Fi off so the device falls back to mobile data. The
tunnel recovers, exactly as it did before.

**4. Disconnect and reconnect within one service session.**
*Result: `pass`. Reconnected without restarting the app.*

Connect, disconnect, connect again **without** force-stopping the app. The
second connection succeeds and is as fast as the first.

This is the check that catches a leaked descriptor or an unclosed socket. The
engine now owns the TUN descriptor and closes it in `disconnect()`, so a leak
here means ownership is being handed over twice or not at all. Repeat the cycle
five times; a leak usually shows on the third or fourth as a connection that
hangs rather than one that fails.

**5. The log shows the full cycle.**
*Result: `pass`. The whole cycle is in the log.*

In the app's log view: `Connecting` → `Connected` → `Disconnected`, once each
per session, with no terminal state reported twice.

---

## Checks specific to what phase 4 changed

**6. Socket protection still goes through.**
*Result: `not run`. The owner cannot put a VPN server on the local network from
this device: site security policy forbids it. Left unverified rather than
claimed. Item 1 exercises the same path indirectly - the tunnel could not have
come up at all if the native layer's sockets had stopped being protected, since
unprotected transport sockets route back into the tunnel and the connection
hangs. What stays untested is the specific case where `protect()` returns
`false`.*

Connect while on Wi-Fi with the VPN server on the **local network**, if you can
arrange it. That is the case where `protect()` failing is visible: the tunnel
hangs at "Connecting" instead of failing outright. It exercises the new path,
where the native layer's protect call is routed through the `SocketProtector`
the host handed the engine rather than straight to the service.

Watch the log for `protect() failed` — it should not appear.

**7. Failure messages are unchanged.**
*Result: `pass`. All three messages are word-for-word what the previous build
showed.*

Force each failure and check the message shown is word-for-word what the
previous build showed. The mapping table in
[`ARCHITECTURE.md`](ARCHITECTURE.md#native-l2tp-layer--engineerror) says which
message belongs to which failure.

- Wrong PSK → "IPsec negotiation failed. Check the PSK and server settings."
- Wrong password → "PPP negotiation failed."
- Unreachable server address → the IPsec message, after the timeout.

**8. Cancelling mid-negotiation is silent.**
*Result: `pass`. No error is shown.*

Start a connection to an unreachable address and press disconnect while it is
still negotiating. The UI returns to disconnected **without** showing an error:
a stop the user asked for is not a failure. This path changed from an exit-code
check to coroutine cancellation, so it is worth confirming by hand.

**9. Manual DNS still works.**
*Result: `pass`.*

Set two manual DNS servers, at least one over DoT or DoH. Resolution works and
the servers shown are the ones you set. The interface advertises the virtual
resolver `198.18.0.1`; the bridge answering on it did not change in phase 4, but
the DNS servers now arrive from the engine's `TunnelParams`, so the wiring did.

**10. Automatic DNS still works.**
*Result: `pass`.*

Set DNS to automatic. Resolution works and the servers shown are the ones the
server proposed, in the same order. A server that proposes the same resolver as
both primary and secondary should now show it once.

**11. `setBlocking(true)` changed nothing.**
*Result: `pass`. Nothing changed, as predicted.*

This build is the first to call `VpnService.Builder.setBlocking(true)`; upstream
never called it at all (SPEC В.1). On this path it is expected to be inert,
because `tunnel_loop.c` puts the descriptor back into non-blocking mode when the
poll loop starts — but "expected to be inert" is exactly the kind of claim that
needs a device to confirm.

There is no separate thing to do: if checks 1–5 pass, this one passes with them.
What to watch for is a tunnel that connects and then moves no traffic, or one
whose throughput collapses — that would mean the descriptor stayed blocking and
the poll loop is stalling on a read.

**12. Proxy-only mode is unaffected.**
*Result: `fail`. Proxy-only mode reports "L2TP handshake failed." See finding
F2 - this is a real defect, and it is very probably older than phase 4.*

Phase 4 left proxy-only mode on the direct native path, but it shares the JNI
upcalls with the tunnel. Start proxy-only mode, confirm it connects and that the
HTTP and SOCKS listeners work. Then switch from proxy-only to VPN mode and back
without force-stopping the app: each switch works and neither mode is left
holding the native session.

---

## Outcome

**Phase 4 is accepted.** All five SPEC 4.2 criteria passed, and the C code was
not touched: `git diff` over `android/app/src/main/cpp/` is empty. The rule that
would have stopped the phase - "if you had to change the C code, revisit the
contract" - never came into play, which is the evidence that the abstraction
boundary was drawn in the right place.

Two things came out of the run. Neither blocks the phase: F1 is a pre-existing
gap that the phase 4 checklist happened to expose, and F2 is in proxy-only mode,
which is outside SPEC 4.2 and was added to this checklist by hand.

## What this found

### F1. ESP sends fail for ~30 s after a network change

*Item 3. Reproduced in two runs out of five.*

**Symptom.** After Wi-Fi to LTE the tunnel recovers and carries traffic in both
directions, but for roughly half a minute the log repeats:

```
esp_encrypt_send: sendto failed errno=101 plain_len=78 pkt_len=132 udp_encap=1
```

**What the line means.** `esp_encrypt_send` (`esp_udp.c:716`) is the outbound
NAT-T path: L2TP inside ESP inside UDP on port 4500. `errno 101` is
`ENETUNREACH` - the kernel has no route for the datagram. The socket was set up
while Wi-Fi was the default network, and after Wi-Fi goes away sends against it
fail until the platform has finished tearing the old network down.
`plain_len=78` is far too small for user data; it is the size of a keepalive.

**Why it is not phase 4's doing.** Phase 4 changed no C code and no socket
lifecycle. More to the point, nothing in this project rebuilds the transport on
a network change, in this build or in any before it: `EngineState.Reconnecting`
has no producer at all, and the tunnel surviving the switch is the platform's
doing rather than the application's. Phase 3 item 11 passed the same switch;
what is new is that this checklist asked for the log to be read during it.

**What happens about it.** Recorded as SPEC В.7 and folded into the phase 7
reconnect work (SPEC В.4). A reconnect that notices the network change and
rebuilds the ESP socket on the new one turns half a minute of failed sends into
a deliberate, logged re-establishment. Until then the tunnel does recover on its
own, so this is noise in the log rather than a broken connection.

### F2. Proxy-only mode fails with "L2TP handshake failed."

*Item 12. Reproducible.*

**Symptom.** Starting proxy-only mode ends with "L2TP handshake failed." That
string is `ProxyTunnelService.tunnelExitDetail(2)`, so `nativeNegotiate` returned
`TUNNEL_EXIT_L2TP_FAILED`. IKE therefore succeeded - the PSK, the server address
and the network are all fine, and the IPsec SA came up - and the L2TP control
channel failed on top of a working SA.

**Why phase 4 is very probably not the cause.** Phase 4 made exactly three
changes the proxy path can see, and none of them can produce this:

1. `TunnelVpnService.DEFAULT_NATIVE_EXIT_STOPPED` became `L2tpExitCode.STOPPED`.
   Both are `12`; it is a rename.
2. `VpnTunnelEvents.emitEngineLogFromNative` gained a call forwarding the line to
   the running engine. With no engine installed it is a null check.
3. `TunnelVpnService.protectSocketFd` now consults the engine first. **The proxy
   path never reaches it:** proxy-only mode calls
   `nativeSetSocketProtectionEnabled(false)`, and `util_protect_fd`
   (`util.c:590`) returns `0` on that flag *before* it looks up any Java method.
   The JNI upcall is not made at all in proxy-only mode.

This is reasoning, not proof. Proxy-only mode is in neither the phase 1/3 nor the
phase 4 baseline, so there is no passing run to compare against.

**Next step, before anything is changed.** Establish whether the failure needs a
preceding VPN session:

1. Force-stop the app. Start proxy-only mode first, with no VPN session in the
   process at all. If it fails here, the defect is in proxy-only mode itself and
   has nothing to do with mode switching.
2. If it only fails after a VPN session, a mode switch is leaving the native
   session behind, and `NativeTunnelSessionCoordinator` is where to look.
3. Either way, capture the log from `Starting proxy negotiation` to the failure.
   The L2TP exchange is SCCRQ then SCCRP; which side goes quiet says whether the
   request ever left the device.

A useful control: install the last pre-phase-4 build and run step 1 on it. That
settles the attribution in one attempt, which is worth more than any amount of
reading the diff.

Recorded as SPEC В.8.
