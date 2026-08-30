# Manual device test — phase 9

Phase 9 is the user interface for everything the earlier phases built: the
protocol selector and the SSTP section of the profile editor, the certificate
selection inside a profile, the HTTP proxy sub-section, a protocol badge in the
profile list, a session card on the status screen, and engine failures phrased
in the user's language rather than the engine's.

Until this build the only way to put an SSTP profile on a device was to import a
profile file that carried one (see the phase 8 note). That is what this test
replaces: **an SSTP profile is now created entirely in the UI, without adb.**

**Build under test:** debug build of `spec/phase-1` at or after the phase 9
commits.

---

## 1. An SSTP profile is created in the UI — SPEC 9.2, criterion 1

1. Settings → **Server certificates** → add the certificate of your test SSTP
   server (from a file, from pasted PEM, or downloaded from the server, whose
   fingerprint you then compare against the server's own).
2. VPN tab → profile picker → **New profile**.
3. Set a name and the server address, the username and the password.
4. Switch the protocol selector to **SSTP**.

Expected: the L2TP section (the pre-shared key) disappears and the SSTP section
appears: port, certificate trust, expected hostname, minimum TLS version, PPP
authentication, HTTP proxy.

5. Set the port (443 unless your server differs). Set certificate trust to
   **System, then selected** or **Selected only** and tick the certificate
   imported in step 1.
6. Leave PPP authentication as it comes: MSCHAPv2, CHAP and PAP on,
   **EAP-MSCHAPv2 off** (SPEC 9.1.2).
7. Save, then connect.

Expected: the tunnel comes up. No adb was needed at any point.

Then check what the form does not draw:

8. Re-open the profile, change only its name, save, re-open it again.

Expected: port, trust policy, selected certificate, expected hostname, TLS
version and authentication methods are all still what you set. This is the
regression the phase 8 note warned about — the editor used to rebuild a profile
out of the fields it drew, which reset everything else — and
`profile_editor_sheet_test.dart` covers it now.

## 2. Trust policies each explain themselves — SPEC 9.1.2

Open the trust setting and read each option: every one has a single line under
it saying what it does. **No verification** appears in a debug build only; a
release build must not offer it (SPEC 5.5).

Set the policy to **Pin this certificate**, select the certificate, save, and
connect. Then set it to **Selected only** with nothing selected and press save.

Expected: the save is refused with "This trust setting needs at least one
selected certificate", and nothing is written.

## 3. The HTTP proxy sub-section — SPEC 9.1.3

1. In the SSTP profile, turn on **HTTP proxy**.

Expected: host, port, username and password appear; with the toggle off they are
gone entirely.

2. Point it at an HTTP CONNECT proxy that can reach the SSTP server, set a
   username and password, save, connect.

Expected: the tunnel comes up, and the status card shows **Through proxy** with
the proxy host.

3. Check the password went where the VPN password goes:

```bash
adb shell run-as io.github.evokelektrique.tunnelforge cat shared_prefs/profile_secrets.xml
```

Expected: opaque base64 entries; the proxy password appears nowhere in clear
text. The database holds only a reference:

```bash
adb shell run-as io.github.evokelektrique.tunnelforge strings databases/trust.db | grep -i <the-proxy-password>
```

Expected: no match.

## 4. The profile list carries the protocol — SPEC 9.1.6

Open the profile picker with one L2TP and one SSTP profile.

Expected: each row shows its protocol as a badge before the server address.

## 5. The status card — SPEC 9.1.7

Connect, then look at the VPN tab under the profile tile.

Expected: protocol, IP address, DNS, MTU, a session timer that advances once a
second, and received/sent counters that grow while traffic flows. Disconnect:
the card disappears.

Known ceiling, worth reading before filing a bug about the numbers: the counters
are this application's UID traffic since the tunnel came up — the transport's
own bytes. They are not the number of bytes your other applications pushed
through the tunnel, and they include the odd connectivity check. Per-interface
counters would need the TUN device name, which the platform does not hand back.

## 6. Engine failures read like sentences — SPEC 9.2, criterion 2

Each of these should produce a readable message, in **both** application
languages (switch in Settings → Language and repeat):

| What to do | Expected message |
|---|---|
| Wrong password on an SSTP profile | the server rejected the username or password |
| Server address that does not resolve | the server could not be reached |
| Trust set to **System store only** against a self-signed server | the certificate is not trusted by this profile |
| Certificate valid for another name, expected hostname left empty | issued to a different name; set the expected hostname |
| Wrong pre-shared key on an L2TP profile | IPsec negotiation failed; check the pre-shared key |

The technical detail is not lost: it is still in the log, one line above, with
the engine's own wording.

## 7. Logs and export — SPEC 9.1.8

Already in place since phase 7; re-check it still holds with an SSTP session
running: the level filter and the protocol filter both narrow the buffer, and
the share button exports what is on screen.

## 8. The interface speaks English and Russian — SPEC 9.1.10

Settings → **Language** offers English and Russian; Persian is gone. Switch to
Russian and walk the editor, the certificate screen, the status card and the
settings panel.

Expected: every label is Russian, and none of the strings above fall back to
English. The strings live in `lib/l10n/app_en.arb` and `lib/l10n/app_ru.arb`;
`localization_test.dart` fails if one file gains a key the other lacks.

Two things to look at rather than read:

- **Plurals.** Select 1, 2 and 5 applications for split tunneling and watch the
  subtitle: Russian needs three forms, and the ARB carries them.
- **The font.** The bundled Estedad face has no Cyrillic, so the Russian locale
  runs on the platform font. Check that Russian text is not a mix of two faces
  within one line — digits and Latin words especially.

A build that had Persian selected before the upgrade falls back to English; the
stored code `fa` is no longer known.

## 9. Dark theme — SPEC 9.2, criterion 3

Go through the editor with the SSTP section open, the certificate list, the
proxy sub-section and the status card in dark theme.

Expected: nothing unreadable, no light-theme surface left behind.

---

## What this phase does not settle

**The session card is a card, not a screen.** SPEC 9.1.7 calls for a status
screen; what phase 9 delivers is the same information on the VPN tab, under the
profile tile, which is where the user already is while connected. If it needs to
be its own screen, that is a small move.
