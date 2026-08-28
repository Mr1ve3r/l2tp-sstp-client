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

**Status: not yet run.**

---

## Gating checks

**1. Import from a file.**
Settings → Server certificates → Add → From a file. Pick a `.pem` or `.crt`
exported from the router. The review sheet appears with the certificate's name,
its SHA-256 fingerprint and any warnings. Keep it. It appears in the list with
the name given, and "Not used by any profile".

**2. The fingerprint matches the router.**
Compare the SHA-256 on the certificate card, character by character, with the
one the router's own interface shows. They must be identical. This is the check
the whole screen exists to make possible — if the formatting differs enough
that comparing is awkward, that is a finding.

**3. A bundle offers a choice.**
Import a file holding both a CA and the certificate it signed. Both appear in
the review sheet; keeping only one stores only that one.

**4. Import by pasting.**
Add → Paste PEM text, paste the same certificate. The store says it is already
imported, and confirming changes the name rather than creating a second entry.
The list length does not change.

**5. Download from the server.**
Add → Download from server, the SSTP server's host and port. The chain appears
leaf first, with the warning about comparing fingerprints out of band shown
before anything can be kept. Compare the leaf's fingerprint with item 2.
Nothing is stored until "Keep selected" is pressed.

Also try a port that speaks no TLS (22, say) and a host that does not resolve:
both must report a failure and leave the screen usable.

**6. Survives a restart, and export.**
Force-stop the application and reopen it. Every imported certificate is still
listed with its name. Open one and export the PEM: what is shown is the
certificate that was imported.

**7. `INSECURE` is absent from a release build.**
SPEC 5.5 and 5.10. On a release APK, nothing in the UI offers a trust policy
that skips verification. On the debug build it may appear. This is the one item
that cannot be checked on a debug build, and the one worth being pedantic
about.

**8. Deleting.**
Delete a certificate. The confirmation says how many profiles use it. After
deleting, it is gone from the list and gone after a restart.

**9. The tunnel still works.**
Connect over L2TP as before. Phase 5 touched nothing in that path, so this is a
smoke check: connect, traffic flows, disconnect.

---

## What this found

*(To be filled in when the checklist is run: one entry per finding, with what
was expected, what happened, and where it was written up.)*
