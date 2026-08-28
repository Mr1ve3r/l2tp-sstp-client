# Manual device test — phase 6

**There is nothing to run on a device for phase 6, and that is not an
oversight.** `SstpEngine` exists, compiles and is unit-tested, but nothing can
start it: the only `VpnService` in the application is the L2TP one inherited
from upstream, the protocol dispatcher is phase 7, and there is no way to create
an SSTP profile until phases 8 and 9. Eight of the fourteen acceptance criteria
in SPEC 6.6 need a live server on the other end of a socket this build cannot
open.

This document is the checklist those eight become, so that phase 7 inherits them
written down rather than re-derived. The six criteria that *can* be checked
without a server were checked, and are recorded at the bottom.

**Build under test when this list is run:** the phase 7 dispatcher, on branch
`spec/phase-1` or later.

---

## Lab needed before any of this can start

Three servers, because the trust policies differ and a single server cannot
exercise them:

1. **SoftEther**, certificate from a public CA (or any CA the device trusts) —
   the `SYSTEM` policy.
2. **MikroTik**, self-signed certificate — the `PIN_LEAF` policy. Note the
   SHA-256 fingerprint from the router before starting.
3. **MikroTik**, certificate issued by a CA you control — the `CUSTOM_ONLY`
   policy. Import the CA certificate through the certificates screen first.

Plus an HTTP proxy that allows `CONNECT` to port 443, configurable with and
without basic authentication (squid with and without `auth_param` is enough),
and a way to put a wrong certificate in front of the server — a second
certificate on the same listener, or a TLS-terminating box in the path.

---

## Deferred from SPEC 6.6

**1. SoftEther, `SYSTEM` policy.** Connects; traffic flows; the notification
shows the session timer running.

**2. MikroTik with a self-signed certificate, `PIN_LEAF`.** Connects with the
fingerprint pinned in the profile. Expiry is not checked by this policy — the
pre-flight is what warns about it, so confirm that warning appears if the
certificate is past its date.

**3. MikroTik with a private CA, `CUSTOM_ONLY`.** Connects using only the
imported CA. Confirm it still connects with the system store empty of that CA,
which is the whole point of the policy.

**4. Public CA, `SYSTEM`.** Connects without importing anything.

**5. Substituted certificate.** Put a different certificate in front of the
server. Expected: `EngineError.CertificateRejected`, and the fingerprint in the
message is the one actually presented — compare it against the substitute, not
against the real certificate.

**6. Name mismatch.** Connect to the server by an address the certificate does
not cover. Expected: `HostnameMismatch`, listing every name the certificate does
carry, so the user can put one of them in `expectedHostname`. The error must not
offer a way to switch verification off.

**7. Reconnect after a network change.** Connect over Wi-Fi, then turn Wi-Fi off
so the device falls back to mobile data. The tunnel comes back. The check that
matters is that it comes back *at all*: an unprotected socket on the second
attempt routes the transport into the tunnel it carries, and the symptom is a
hang, not an error. Confirm in the log that the new socket was protected.

**8. Ten connect/disconnect cycles.** `lsof -p <pid>` (or
`ls /proc/<pid>/fd | wc -l`) after each cycle. The count must not climb. Sockets
and the TUN descriptor are the two things that leak here.

## Proxy cases, also deferred

**9. Proxy without authentication.** Connects through `CONNECT`.

**10. Proxy with basic authentication.** Connects.

**11. Certificate checked at the server, not at the proxy.** With the proxy in
the path, substitute the *server's* certificate: it must still be rejected. Then
confirm that the proxy's own certificate plays no part — a proxy speaking plain
HTTP on the `CONNECT` leg is the normal case and must not affect the result.

**12. Wrong proxy password.** Expected: an authentication failure within a
second or two, naming the proxy. Not a timeout. (The unit tests cover the status
line parsing; this confirms it against a real proxy.)

**13. Proxy password absent from the exported log.** Connect through an
authenticating proxy, export the log, search it for the password. Nothing.
(Redaction is unit-tested; this confirms nothing writes around it.)

---

## Checked during phase 6

These needed no server and were verified as part of the phase.

**A. Attribution is complete and confined.** Both greps from
`third_party/open-sstp-client/PROVENANCE.md` §5 return nothing: every file in
`engine-sstp/src/main/java` carries the MIT header, and no file outside the
module carries it.

**B. No engine code can reach a `VpnService.Builder`.**
`grep -rn "\.builder" engine-sstp/src/main/java` is empty. The only occurrences
of `VpnService` in the module are comments explaining its absence.

**C. Every socket is protected, before `connect()`.** Covered by
`SslTerminalTransportTest`: the direct socket, the socket to a proxy, and a
fresh socket on each attempt, each asserted to be still unconnected at the
moment protection is applied. A socket protection refuses is not connected at
all.

**D. `CONNECT` names the server.** Asserted against a fake proxy, along with
basic credentials being sent only when the profile carries them.

**E. A rejected proxy password is an authentication failure.** Asserted, along
with the password not appearing in the resulting error.

**F. The build is clean.** `:engine-sstp:compileDebugKotlin`,
`:engine-sstp:ktlintCheck` and `:engine-sstp:testDebugUnitTest` all pass; the
L2TP path is untouched by this phase.
