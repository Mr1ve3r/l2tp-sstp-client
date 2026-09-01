# Phase 10 — failover and protocol auto-selection

SPEC phase 10 has two items. One is being built; one is not. This file records
which, and why, so that neither has to be re-argued from the SPEC text alone.

---

## 10.2 — auto-selection by network: not implemented

**Decision, 2026-09-01, by the project owner: SPEC 10.2 is dropped. It is not
implemented in phase 10 and nothing in phase 10 leaves a place for it.**

SPEC 10.2 asked for rules of the form `SSID == X → profile A`,
`network type == CELLULAR → profile B`, `otherwise → the default group`, driven
by `NetworkCapabilities` and the current SSID.

The SSID half cannot be built the way the SPEC wants it. The SPEC's own
constraint is that **geolocation is not used** — and on Android 10 and later
reading the SSID of the connected network *is* a location-permitted operation:
`WifiInfo.getSSID()` returns `<unknown ssid>` unless the app holds
`ACCESS_FINE_LOCATION` and location services are switched on. SPEC 10.2
anticipated this and proposed degrading the rule with an explanation in the UI,
which leaves a feature whose main case is a rule that announces it cannot run.

The network-*type* half (`CELLULAR` vs `WIFI`) needs no permission and would
work. It is dropped with the rest rather than shipped alone: half of a rules
engine is a settings screen that mostly says no, and `NetworkMonitor` already
gives the tunnel what it needs to react to a network change without any rules.
If per-network selection returns, it returns as its own item with its own
design, not as the remainder of this one.

**What this means for anyone reading SPEC 10.2:** the section stands as written
history. Nothing in the code implements it, and the acceptance criterion in
10.3 that depends on it — "the network-type rule fires when Wi-Fi ↔ LTE
switches" — is void. The other two criteria in 10.3 belong to 10.1 and stand.

---

## 10.1 — failover group: in scope

What the SPEC asks for:

1. A group of profiles with an ordered list of members.
2. Try the first member with a `connectTimeoutSec` budget (15 by default).
   Advance to the next member on `NetworkUnreachable`, `TimedOut`,
   `IpsecFailed`. **Stop** on `AuthenticationFailed` — the credentials are
   wrong and walking the list only spreads failed logins across servers.
3. The UI shows which member is active.
4. The case it exists for: L2TP/IPsec first because UDP is quicker, SSTP on 443
   as the fallback for networks where UDP/500 and ESP are blocked.

`EngineError` was already written with this in mind — the doc comment on
`AuthenticationFailed` names SPEC 10.1 — so the vocabulary the decision needs
is in place and no error type has to change.

### Acceptance (SPEC 10.3, the two criteria that survive)

- An unreachable L2TP server falls through to SSTP in under 20 seconds.
- A wrong password shows an authentication failure and does **not** walk the
  list.

---

## Where 10.1 stands

**Built, on the host side, end to end:**

- `EngineError.failoverDecision` (`engine-api`) — which failures advance a group
  and which stop it. The `when` is exhaustive with no `else`, so a new error
  variant cannot take an unconsidered default.
- `FailoverGroup`, `FailoverGroupMember`, `FailoverGroupDao`,
  `FailoverGroupStore` (`core-trust`) — the ordered group, in the database the
  profiles are already in. Migration 2 → 3 is additive; nothing existing moves.
- `FailoverRun` (`app`) — the walk itself: which member is current, what to try
  next, when to stop. Tested off-device, so it runs in CI.
- `TunnelVpnService.ACTION_START_GROUP` — resolves every member up front, tries
  them in order, arms the group's `connectTimeoutSec` against each, and hands
  each failure to `FailoverRun` before reporting it. A member that comes up
  ends the run; a stop from the user ends it; a budget that runs out advances
  the same way a refusal does.
- `connectGroup` on the VPN method channel and group CRUD on the profile
  channel, with the Dart contract keys and `VpnClient.connectGroup` to match.

**What the log shows during a run**, which is what makes the acceptance
criteria checkable:

```
Failover Work: trying 1 of 2 -- Work L2TP over L2TP/IPsec
Member failed with engine.error.timed_out; trying the next one
Failover Work: trying 2 of 2 -- Work SSTP over SSTP
Failover Work: member 2 of 2 is up
```

and, for the case that must not walk the list:

```
Failover Work: stopping after member 1 of 2 -- the failure stops a group:
every member would answer the same way
```

**Built, on the Flutter side, end to end:**

- `FailoverGroup` (`lib/features/profiles/domain/`) — the group as Dart sees it,
  with the same budget range the host clamps to, so the editor can say no before
  the round trip rather than silently changing what was typed.
- Group CRUD on `ProfileBackend` — the method-channel implementation and the
  in-memory one tests run against, which mirrors the host closely enough to be
  worth trusting: it drops members that name no profile, and a deleted profile
  leaves every group that mentioned it, the way the foreign key does.
- `ProfileStore.loadFailoverGroups` / `saveFailoverGroup` / `deleteFailoverGroup`
  and a last-chosen-group id in preferences. It is in preferences rather than
  beside the host's last-profile id because nothing on the host needs it: a group
  is only ever started by this application, while the last *profile* is what the
  service reads when the system starts it with no Dart running (SPEC В.13).
- `ProfilesBloc` — the groups, which one is chosen, and the rule that a group and
  a profile are alternatives. Choosing one clears the other on both sides, so a
  restart cannot pick the dropped one back up.
- `TunnelBloc.TunnelConnectGroupRequested` — the two things a group can be wrong
  about before it starts (being empty, being asked for in proxy-only mode), and
  a wait long enough for the whole walk: a group of three at 90 seconds each
  cannot finish inside the 60 seconds one profile gets, and timing out on a host
  that is still working is a lie the interface used to be able to tell.
- The groups tab in the profile picker, and the group editor beside the profile
  editor: name, budget, and an ordered membership that reorders by drag or by
  per-row menu — both through one function, so they cannot disagree.

### The active member, which was the loose end

SPEC 10.1.3 asks that the UI show which member is active. The host emits it as
the detail on every `connecting` event (`Trying 2 of 2: Work SSTP`), but that
detail only ever reached the log: the status badge shows the button's own label
and always did. `TunnelState.connectingDetail` now carries it, and while a group
is walking the profile tile shows it in place of the static member order —
which is the one thing about the run a user cannot work out for themselves,
because the members are resolved on the host.

The badge keeps saying *Connecting… tap to cancel*, because that is the
affordance and it is still true.

### What a user can now do

Build a group, arrange it, choose it instead of a profile, and start it. Which
means `docs/MANUAL_TEST_PHASE10.md` can be written and now is: both surviving
acceptance criteria in SPEC 10.3 can be walked on a device.

## What phase 10 still does not do

- **A group cannot contain a group.** Members are profiles. Nesting was not
  asked for and would need a cycle check to be safe.
- **Nothing re-runs the walk after it succeeds.** Once a member is up it stays
  up; if it drops, the reconnect is the ordinary one for that profile, not a
  fresh walk from the top. Doing otherwise needs a policy for when a group may
  re-race its members, and SPEC 10.1 does not ask for one.
- **The per-member budget is the only knob.** No per-member override, and no way
  to skip a member without removing it.
