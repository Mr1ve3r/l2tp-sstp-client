# Manual device test — phase 8

Phase 8 moved the profile store into Kotlin: Room for the rows, an Android
keystore key for the secrets, and a one-time handover of the profiles the
Flutter layer used to own. That move is what makes an always-on tunnel and the
Quick Settings tile able to connect without the application running, so the two
items deferred from phase 7 are checkable here.

What is **not** checkable is anything that needs the SSTP fields to be filled
in: the protocol selector and the SSTP section of the editor are phase 9. The
row holds those fields and survives a restart with them, but the only way to
put one on a device today is to import a profile file that carries them.

**Build under test:** debug build of `spec/phase-1` at or after the phase 8
commits.

---

## 1. The upgrade does not lose profiles — SPEC 8.2, criterion 1

This is the one test that cannot be repeated on the same device without going
back, so do it first.

1. Install the **previous** build (any commit before the phase 8 commits).
2. Create two profiles with different servers, passwords and pre-shared keys.
   Connect with the second one so it becomes the last used.
3. Install the phase 8 build **over** it — `adb install -r`, no uninstall.
4. Open the application.

Expected: both profiles are in the list, in the same order, and the second is
selected. Open each in the editor: the password and PSK fields are filled.
Connect with each.

Then check the handover really moved them rather than copying them:

```bash
adb shell run-as io.github.evokelektrique.tunnelforge cat shared_prefs/profile_secrets.xml
```

Expected: one entry per secret, each an opaque base64 string. Neither password
appears anywhere in it. The old `flutter_secure_storage` entries for those
profiles are gone.

## 2. The database holds no secret — SPEC 8.2

```bash
adb shell run-as io.github.evokelektrique.tunnelforge strings databases/trust.db | grep -i <the-password>
```

Expected: no match. The row carries `profile/<id>/password` and the like, never
the password itself. `ProfileStoreInstrumentedTest` asserts the same thing, but
this is the version that reads the file a backup would pick up.

## 3. Always-on without opening the application — SPEC В.13

1. Connect once, so a last-used profile is recorded, then disconnect.
2. Settings → Network → VPN → TunnelForge → enable **Always-on VPN** and
   **Block connections without VPN**.
3. Force-stop the application (Settings → Apps → Force stop).
4. Reboot the device. Do **not** open the application.

Expected: the tunnel comes up on its own. `adb logcat -s TunnelVpnService`
shows `Start without arguments accepted protocol=l2tp server=…` with an
attempt id beginning `auto-`. Traffic flows before the application is opened.

Then check the refusal case: delete every profile, force-stop, reboot again.
Expected: the log says `Start without arguments: no profile to connect` and the
service stops instead of connecting to something the user did not choose. With
lockdown on, no traffic leaves the device — which is the correct outcome, not a
failure.

## 4. One tap from the Quick Settings tile

With the application force-stopped and at least one profile stored, tap the
tile. Expected: the tunnel connects, and the log shows the same
`Start without arguments accepted` line. This is the phase 7 tile item that
could not be closed there.

## 5. A restart after the service is killed

While connected:

```bash
adb shell am force-stop io.github.evokelektrique.tunnelforge
```

Expected: the service is restarted by the system with a null intent and
reconnects the same profile, rather than sitting idle.

## 6. Export without secrets — SPEC 8.2, criterion 3

Export a profile from the picker sheet and open the `.tfp` file in a text
editor.

Expected: valid JSON, `"v": 4`, a `profile` object with every field, and no
`password`, `psk` or `proxyPassword` key anywhere. Import it back on a second
device: everything but the passwords is there, and the editor asks for them.

The share link (**Copy share link**) is the opposite by design: it carries the
secrets, because handing someone the link is the act of handing them the
connection. Paste one into a text editor to confirm it is a `tf://p/…` payload,
then import it on a second device and confirm the connection works without
retyping anything.

## 7. A profile file from the previous build still imports

Keep a `.tfp` file exported by the pre-phase-8 build and import it. Expected: it
imports as an L2TP profile with its password and PSK, and connects.

---

## Deferred to phase 9

- **Everything SSTP in the editor.** The row holds `protocol`, `port`,
  `trustPolicy`, `expectedHostname`, `minTlsVersion`, `pppAuthMethods` and the
  proxy fields, and `ProfileStoreInstrumentedTest` shows they survive a
  restart, but no screen writes them yet. The two acceptance criteria of SPEC
  7.2 that need profiles of both protocols stay deferred with them.
- **The password prompt for an encrypted export.** `ProfileContainer` and the
  channel calls are in place and unit-tested; the dialog that asks for the
  password, and the one that asks for it on import, are phase 9. Until then an
  encrypted container can be produced only from a test.
- **Per-application routing and the kill switch per profile.** The columns
  exist and an always-on start already honours `perAppMode`. The editor still
  writes the global split-tunnel setting instead.
