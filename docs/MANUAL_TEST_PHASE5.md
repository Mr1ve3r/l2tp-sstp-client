# Manual device test — phase 5

The certificate store is checked by unit tests where it can be, and by an
instrumentation test for the part that needs a device (`TrustStoreInstrumentedTest`:
import, restart, still there). What is left here needs a person: a document
picker, a real server to download a chain from, and a release build to confirm
something is *absent* from it.

This closes the acceptance criteria of SPEC 5.10 that CI cannot reach.

**What is being tested:** phase 5 added the store the SSTP trust policies act
on — Room for the metadata, PEM files under `filesDir/trust`, three import
paths, and the certificates screen. Nothing in the L2TP path changed, so the
tunnel itself is only checked for "still works".

**Build under test:** branch `spec/phase-1`, the phase 5 commits or later.

**Getting an APK.** Local `assembleDebug` is blocked by an unrelated Windows
filesystem problem, so take the artifact from CI: the `build-debug-apk` job on
the branch publishes `l2tp-sstp-client-debug-apk`. Item 7 needs a release
build, which the `build-android` job produces on `main` and `release/**`; a
locally built release APK does as well.

**Status: run 2026-08-28, one failure.** Results are recorded per item
below. Items 5 and 7 were skipped by the tester and are still open; item 4
failed and is written up under "What this found".

---

## Gating checks

**1. Import from a file.**
*Result: `pass`.*

Settings → Server certificates → Add → From a file. Pick a `.pem` or `.crt`
exported from the router. The review sheet appears with the certificate's name,
its SHA-256 fingerprint and any warnings. Keep it. It appears in the list with
the name given, and "Not used by any profile".

**2. The fingerprint matches the router.**
*Result: `pass`. The fingerprints matched.*

Compare the SHA-256 on the certificate card, character by character, with the
one the router's own interface shows. They must be identical. This is the check
the whole screen exists to make possible — if the formatting differs enough
that comparing is awkward, that is a finding.

**3. A bundle offers a choice.**
*Result: `partial`. The certificate card showed "Certificate authority" and
"Self-signed" as it should. The bundle case itself — a file holding a CA and
the certificate it signed, offering both and keeping one — was not exercised,
so the choice list is still unverified.*

Import a file holding both a CA and the certificate it signed. Both appear in
the review sheet; keeping only one stores only that one.

**4. Import by pasting.**
*Result: `fail`. The paste dialog throws:*

```
'package:flutter/src/widgets/framework.dart': Failed assertion: line 6268
pos 12: '_dependents.isEmpty': is not true.
```

*Written up as finding F1 below.*

Add → Paste PEM text, paste the same certificate. The store says it is already
imported, and confirming changes the name rather than creating a second entry.
The list length does not change.

**5. Download from the server.**
*Result: `skipped` at the tester's request. Still open.*

Add → Download from server, the SSTP server's host and port. The chain appears
leaf first, with the warning about comparing fingerprints out of band shown
before anything can be kept. Compare the leaf's fingerprint with item 2.
Nothing is stored until "Keep selected" is pressed.

Also try a port that speaks no TLS (22, say) and a host that does not resolve:
both must report a failure and leave the screen usable.

**6. Survives a restart, and export.**
*Result: `pass`. Certificates survive a restart.*

Force-stop the application and reopen it. Every imported certificate is still
listed with its name. Open one and export the PEM: what is shown is the
certificate that was imported.

**7. `INSECURE` is absent from a release build.**
*Result: `skipped`. Still open, and it needs a release build — a debug build
cannot answer it.*

SPEC 5.5 and 5.10. On a release APK, nothing in the UI offers a trust policy
that skips verification. On the debug build it may appear. This is the one item
that cannot be checked on a debug build, and the one worth being pedantic
about.

**8. Deleting.**
*Result: `pass`, with the usage count untested. No profile could be pointed at
a certificate — profiles do not carry `trustedCertificateIds` until phase 6
(SPEC В.6) — so the confirmation was only seen in its general form, warning
that profiles trusting the certificate will stop connecting. The count itself
is checked by `TrustStoreInstrumentedTest`; on screen it stays unverified until
phase 6.*

Delete a certificate. The confirmation says how many profiles use it. After
deleting, it is gone from the list and gone after a restart.

**9. The tunnel still works.**
*Result: `pass`.*

Connect over L2TP as before. Phase 5 touched nothing in that path, so this is a
smoke check: connect, traffic flows, disconnect.

---

## What this found

### F1. The paste-PEM dialog trips a framework assertion

*Item 4.* Opening Add → Paste PEM text and confirming throws:

```
'package:flutter/src/widgets/framework.dart': Failed assertion: line 6268
pos 12: '_dependents.isEmpty': is not true.
```

That line is `InheritedElement.debugDeactivated`, which asserts that nothing
still depends on an inherited widget when its element goes away.

**Cause.** `CertificatesPage` created the dialog's `TextEditingController`
itself and disposed it on the line after `Navigator.pop`. The dialog route is
still animating out at that moment and its `TextField` is still mounted, so the
field went on using a controller that had been disposed. The same shape was in
the download-from-server dialog, with two controllers, so item 5 would have
failed the same way once it was run.

**Fix.** Each dialog now owns its controllers in its own `State` and disposes
them in `dispose()`, which runs after the route is gone. Regression tests drive
both dialogs end to end.

### Still open

- Item 3, the bundle case: a file with more than one certificate offering a
  choice.
- Item 5, downloading a chain from a server, including the two failure paths.
- Item 7, `INSECURE` absent from a release build.
- Item 8's usage count, which needs a profile that can reference a
  certificate — phase 6.
