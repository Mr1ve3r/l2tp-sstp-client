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

**Status: not yet run.** Record a result under each item as you go.

---

## Gating checks

These are SPEC 4.2 verbatim. All of them must pass.

**1. Connection to a real server.**
*Result:*

Connect to the MikroTik (IKEv1, PSK). It connects, the status screen shows the
IPCP-assigned address, and traffic flows.

**2. Throughput and stability are no worse than baseline.**
*Result:*

Run a speed test and compare with the phase 3 figure. Then leave the tunnel up
for at least ten minutes with traffic on it. `fail` on a drop in throughput, on
a stall, or on a disconnection the baseline did not have.

**3. Reconnect across Wi-Fi → LTE.**
*Result:*

With the tunnel up, turn Wi-Fi off so the device falls back to mobile data. The
tunnel recovers, exactly as it did before.

**4. Disconnect and reconnect within one service session.**
*Result:*

Connect, disconnect, connect again **without** force-stopping the app. The
second connection succeeds and is as fast as the first.

This is the check that catches a leaked descriptor or an unclosed socket. The
engine now owns the TUN descriptor and closes it in `disconnect()`, so a leak
here means ownership is being handed over twice or not at all. Repeat the cycle
five times; a leak usually shows on the third or fourth as a connection that
hangs rather than one that fails.

**5. The log shows the full cycle.**
*Result:*

In the app's log view: `Connecting` → `Connected` → `Disconnected`, once each
per session, with no terminal state reported twice.

---

## Checks specific to what phase 4 changed

**6. Socket protection still goes through.**
*Result:*

Connect while on Wi-Fi with the VPN server on the **local network**, if you can
arrange it. That is the case where `protect()` failing is visible: the tunnel
hangs at "Connecting" instead of failing outright. It exercises the new path,
where the native layer's protect call is routed through the `SocketProtector`
the host handed the engine rather than straight to the service.

Watch the log for `protect() failed` — it should not appear.

**7. Failure messages are unchanged.**
*Result:*

Force each failure and check the message shown is word-for-word what the
previous build showed. The mapping table in
[`ARCHITECTURE.md`](ARCHITECTURE.md#native-l2tp-layer--engineerror) says which
message belongs to which failure.

- Wrong PSK → "IPsec negotiation failed. Check the PSK and server settings."
- Wrong password → "PPP negotiation failed."
- Unreachable server address → the IPsec message, after the timeout.

**8. Cancelling mid-negotiation is silent.**
*Result:*

Start a connection to an unreachable address and press disconnect while it is
still negotiating. The UI returns to disconnected **without** showing an error:
a stop the user asked for is not a failure. This path changed from an exit-code
check to coroutine cancellation, so it is worth confirming by hand.

**9. Manual DNS still works.**
*Result:*

Set two manual DNS servers, at least one over DoT or DoH. Resolution works and
the servers shown are the ones you set. The interface advertises the virtual
resolver `198.18.0.1`; the bridge answering on it did not change in phase 4, but
the DNS servers now arrive from the engine's `TunnelParams`, so the wiring did.

**10. Automatic DNS still works.**
*Result:*

Set DNS to automatic. Resolution works and the servers shown are the ones the
server proposed, in the same order. A server that proposes the same resolver as
both primary and secondary should now show it once.

**11. `setBlocking(true)` changed nothing.**
*Result:*

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
*Result:*

Phase 4 left proxy-only mode on the direct native path, but it shares the JNI
upcalls with the tunnel. Start proxy-only mode, confirm it connects and that the
HTTP and SOCKS listeners work. Then switch from proxy-only to VPN mode and back
without force-stopping the app: each switch works and neither mode is left
holding the native session.

---

## What this found

*Fill in after the run. If a check failed, describe the symptom, what caused it
and what changed as a result. If SPEC 4.2's rule bit — "if you had to change the
C code, stop and revisit the contract" — say so explicitly.*
