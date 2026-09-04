# Manual device test — phase 7

Phase 7 made one service run either engine, taught the host to rebuild a tunnel
after a network change, added `onRevoke()`, a Quick Settings tile, a session
timer in the notification, and a protocol on every log line.

Most of that is checkable on a device today over L2TP, and this list is written
for that. What is **not** checkable is anything that needs an SSTP profile: the
UI that creates one is phase 9 and the profile model that holds its fields is
phase 8, so `VpnClient.connect` still always sends `protocol=l2tp`. The service
is `android:exported="false"`, so `adb shell am start-service` cannot reach it
either — shell is a different UID. The eight SSTP items deferred from phase 6
therefore stay deferred, together with the two acceptance criteria of SPEC 7.2
that need two protocols at once; they are listed at the bottom so phase 9
inherits them written down.

**Build under test:** debug build of `spec/phase-1` at or after the phase 7
commits.

---

## 1. Dispatch — the L2TP path still is the L2TP path

Connect an L2TP/IPsec profile. Expected: it connects exactly as it did before
phase 7, and the log shows `ACTION_START accepted protocol=l2tp`.

This is the regression check that matters most: every L2TP session now goes
through `createEngine()`, `TunnelStartRequest` and the protocol-gated native
ownership, and none of that may change what the L2TP path does.

## 2. Notification: protocol, subject, session timer

While connected, pull down the notification. Expected:

- the text reads `Connected: L2TP/IPsec · <profile name>` (or the server, when
  the profile has no name);
- a timer runs beside it and keeps counting while the phone sleeps and wakes.

The timer is the platform's chronometer, so check it after a few minutes of
screen-off — a timer that only advances while the app is awake is the bug this
avoids.

## 3. Reconnect on a network change — SPEC В.4 and В.7

Connect over Wi-Fi, confirm traffic flows, then turn Wi-Fi off so the device
falls to mobile data.

Expected:

- the UI shows the reconnecting state and the log has
  `Reconnecting attempt=1 reason=network changed to CELLULAR`;
- within a few seconds the tunnel is up again and traffic flows;
- **no repeated `esp_encrypt_send: sendto failed errno=101`.** That line is
  finding В.7. It appeared for about half a minute per switch because the old
  socket outlived the network; the engine that owned it is now thrown away.

Run this five times, both directions (Wi-Fi → LTE and LTE → Wi-Fi). В.7
reproduced in two runs out of five, so a single clean run proves nothing.

Then switch networks six times in a row quickly. Expected: after the fifth
reconnect the session gives up with *"The network kept changing; gave up after
5 reconnect attempts."* rather than looping.

## 4. `onRevoke()`

While connected, go to Android settings → Network → VPN and revoke the app's
permission (or connect a different VPN app, which revokes it the same way).

Expected: the notification disappears, the app shows the tunnel stopped with
*"VPN permission was revoked."*, and the log has
`VPN permission revoked; stopping the tunnel`. Before phase 7 the app went on
showing a connected tunnel that no longer existed.

## 5. Quick Settings tile

Add the TunnelForge tile to Quick Settings.

- Nothing connected: the tile reads *Tap to connect* and is off; tapping it
  opens the app.
- Connected: the tile is on and reads *Connected*; tapping it stops the tunnel,
  the notification goes away and the app shows it stopped.
- Pull the shade down again after each: the tile state must match reality, not
  the state it had when it was last drawn.

Connecting straight from the tile is phase 8 (SPEC В.13).

## 6. The single log, and its two filters

With a session's worth of log in the buffer:

- the level filter still works as before;
- the new protocol filter offers ALL / L2TP / SSTP. On an L2TP session, L2TP
  shows the engine and native lines, SSTP shows nothing, and ALL additionally
  shows lines that belong to no session (app startup, settings, connectivity);
- each line's prefix reads `native/L2TP/<tag>` or `kotlin/L2TP/<tag>`.

Export the log (share) and confirm the exported text carries the same protocol
prefixes.

**Then grep the exported file.** It must not contain the VPN password, the PSK,
or a proxy password, in any form. This is an acceptance criterion of 7.2, and
the redaction it depends on is `sanitizeLogMessage`, not the eye.

## 7. Always-on VPN and lockdown

Set TunnelForge as the always-on VPN with *Block connections without VPN*.

Expected today:

- with the app closed and no tunnel up, no traffic leaves the device — the
  platform blocks it, which is the criterion;
- opening the app and connecting works normally, and the connection survives
  the screen turning off.

Not expected today: the system bringing the tunnel up on its own after a
reboot. That needs the profile and its secrets to be readable from Kotlin,
which is phase 8 (SPEC В.13). Note whether the platform shows its "always-on
VPN disconnected" warning — that is the expected behaviour of this build, not a
regression.

---

## Deferred to phase 9 (needs an SSTP profile)

Everything in `docs/MANUAL_TEST_PHASE6.md` under *Deferred from SPEC 6.6* — the
three-server lab, the trust policies, the substituted certificate, the name
mismatch, the proxy, the connect/disconnect loop under `lsof` — plus:

1. **Switching between an L2TP profile and an SSTP profile without restarting
   the app** (SPEC 7.2). The dispatch is unit-tested; the switch is not
   observable until two kinds of profile exist.
2. **Starting a second profile while the first is live** stops the first
   cleanly (SPEC 7.2), including L2TP → SSTP and SSTP → L2TP, where the native
   poll loop is owned in one direction and not the other.
3. **Always-on with lockdown on an SSTP profile** — the criterion names both
   protocols.
4. **A reconnect on an SSTP session**, which unlike L2TP has no native loop and
   rebuilds entirely in Kotlin.
5. **The protocol filter with both engines' lines in one buffer** — checked
   above with one engine only.

## Results

_Fill in when run. Record the build hash, the device, and for item 3 the number
of runs and how many showed the ESP error._
