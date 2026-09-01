# Manual device test — phase 10

Phase 10 is the failover group: an ordered list of profiles the tunnel tries
one after another until one comes up. The host side has been in place since the
earlier phase 10 commits; what this test covers is the part that makes it
reachable — the groups tab in the profile picker, the group editor, and the
connect button starting a group rather than a profile.

SPEC 10.2 (auto-selection by network) is **not implemented**; see
`docs/PHASE10.md` for the decision and for which acceptance criteria it voids.
Nothing below tests it.

**Build under test:** debug build of `spec/phase-1` at or after the phase 10 UI
commits.

**What you need on the device:** two saved profiles pointing at the same server
by different routes — an L2TP/IPsec one and an SSTP one on 443 — plus one
profile whose password is deliberately wrong. Section 4 needs a way to make the
L2TP profile unreachable; the simplest is to point its server at an address
nothing answers on (`192.0.2.1` is reserved for exactly this).

---

## 1. A group is built in the UI — SPEC 10.1.1

1. VPN tab → tap the profile tile to open the picker.
2. Switch the segmented control at the top from **Profiles** to
   **Failover groups**.

Expected: an empty list explaining what a group is, and a **+** in the header.

3. Tap **+**. Name the group `Work`. Leave the per-profile time at 15 seconds.
4. **Add a profile** → the L2TP profile. **Add a profile** again → the SSTP one.

Expected: the two sit in a numbered list, 1 and 2, in the order they were added,
each showing its protocol and server. **Add a profile** greys out once every
saved profile is in the group.

5. Save.

Expected: the sheet returns to the group list, a toast says *Group saved*, and
the group's row shows `2 profiles` and `Work L2TP (L2TP) → Work SSTP (SSTP)`.

Then check what the form refuses:

6. **+** again, save with no name → *Give the group a name.*
7. Give it a name, save with no profiles → *Add at least one profile to the
   group.*
8. Add a profile, set the time to `1`, save → *Choose between 5 and 120
   seconds.* Set it to `120` and it saves. (The host clamps as well; this is the
   editor saying so before the round trip.)

## 2. The order is the user's — SPEC 10.1.1

1. Edit `Work`. Drag the second member above the first by its numbered handle.
2. Save, re-open the group.

Expected: the new order stuck, and the numbers renumbered.

3. Do the same again with the per-row menu: **Move up** / **Move down**. Both
   routes have to produce the same list — they go through one function, and
   `failover_group_editor_test.dart` covers them, but the drag is the part only
   a device can check.
4. Put the order back to L2TP first, SSTP second, and save. The rest of this
   test assumes that order.

## 3. A group is what the button starts — SPEC 10.1.3

1. Picker → **Failover groups** → tap the `Work` row.

Expected: the sheet closes and the profile tile on the VPN tab now shows the
**group's** name, with the members and their protocols in order underneath
instead of a server address.

2. Open the picker again, switch to **Profiles**, tap any profile.

Expected: the tile goes back to that profile. A group and a profile are
alternatives — choosing one drops the other; there is never a highlighted row in
both tabs at once.

3. Choose the group again, force-stop the app, and reopen it.

Expected: the group is still what the tile shows. (It is remembered separately
from the last profile, in preferences.)

## 4. An unreachable first member falls through — SPEC 10.3, criterion 1

This is the acceptance criterion. **Start a stopwatch when you tap connect.**

1. Point the L2TP member at an address nothing answers on, and make sure the
   SSTP member is a server that works.
2. Choose the group. Tap connect. Grant the VPN permission if asked.

Expected: **the tunnel is up in under 20 seconds**, on the SSTP member.

3. While it is connecting, watch the **profile tile** — the one that normally
   lists the members in order.

Expected: it is replaced by the member being tried — `Trying 1 of 2: Work L2TP`,
then `Trying 2 of 2: Work SSTP` — and goes back to the static order once a
member is up. The badge above the button still says *Connecting… tap to
cancel*; the tile is what names the member.

4. Logs tab, after it comes up:

```
Failover Work: trying 1 of 2 -- Work L2TP over L2TP/IPsec
Member failed with engine.error.timed_out; trying the next one
Failover Work: trying 2 of 2 -- Work SSTP over SSTP
Failover Work: member 2 of 2 is up
```

The middle line's error may be `engine.error.network_unreachable` instead,
depending on how your unreachable address fails. Either advances the group; an
address that answers with a refusal rather than silence will advance faster than
the 15-second budget, which is fine — the criterion is an upper bound.

5. Disconnect.

## 5. A wrong password stops the group — SPEC 10.3, criterion 2

The other acceptance criterion, and the one that matters most: walking the list
after a refused password would spread failed logins across every server in it.

1. Edit `Work` — or make a second group — so that the **first** member is the
   profile with the deliberately wrong password, and a working profile is second.
2. Choose it. Tap connect.

Expected: an authentication failure is reported, and **the second member is
never tried**. The whole thing ends on member 1.

3. Logs:

```
Failover Work: stopping after member 1 of 2 -- the failure stops a group:
every member would answer the same way
```

Expected: no `trying 2 of 2` line anywhere after it.

## 6. The cases that are refused before anything starts

1. **An empty group.** Delete every profile a group names (Profiles tab →
   profile menu → Delete). Go back to the groups tab.

Expected: the row now says `No profiles`, and tapping it says *This group has no
profiles to try. Add one to it first.* rather than selecting it. A group whose
members were deleted while it was the chosen one stays chosen but will not
start — the connect button says the same sentence.

2. **Proxy-only mode.** Settings → connection mode → **Local proxy only**. Go
   back, choose a group, tap connect.

Expected: *A failover group runs as a VPN tunnel. Turn off proxy-only mode to
start it.* Nothing starts, and the mode setting is left as the user set it.

3. **Cancel mid-walk.** Start the group from section 4 again and tap the button
   while it is on member 1.

Expected: it stops there. The walk does not carry on to member 2 behind a
cancel.

## 7. Deleting

1. Groups tab → group menu → **Delete group** → confirm.

Expected: the dialog says the profiles are kept. After it, the group is gone,
the profiles are all still in the Profiles tab, and if the deleted group was the
chosen one the VPN tab falls back to *Quick connect* with nothing selected.

2. Delete a profile that two groups both name.

Expected: it disappears from both, and neither group is deleted. (The membership
rows cascade off the profile in the database; the lists reload after the
deletion.)

## 8. Russian, and dark theme

1. Settings → Language → **Русский**. Walk the groups tab, the editor, the
   delete dialog and the VPN tile.

Expected: no English left behind. Watch the member count especially — Russian
needs three plural forms (`1 профиль`, `2 профиля`, `5 профилей`), and the ARB
carries them; `localization_test.dart` fails if either file gains a key the
other lacks, but it cannot check that the forms are right.

2. Repeat the editor and the group list in dark theme.

Expected: nothing unreadable, no light-theme surface left behind.

---

## What this phase does not settle

**A group cannot contain a group.** Members are profiles. Nesting was never
asked for and would need a cycle check to be safe.

**Nothing re-runs the walk after it succeeds.** Once a member is up it stays up;
if it drops, the reconnect is the ordinary one for that profile, not a fresh
walk from the top of the group. SPEC 10.1 does not ask for more, and doing it
would need a policy for when a group is allowed to re-race its members.

**The per-member budget is the only knob.** There is no per-member override, and
no "skip this member for now".
