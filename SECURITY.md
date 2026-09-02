# Security policy

## Reporting a vulnerability

**Do not open a public issue for a security problem.** This is a VPN client:
a bug report that describes how to read someone's traffic is itself the exploit
until there is a fix.

Report privately through GitHub:

1. Go to the [Security tab](https://github.com/Mr1ve3r/l2tp-sstp-client/security)
   of this repository.
2. **Report a vulnerability** → fill in the advisory form.

That opens a private thread visible only to the maintainers. If GitHub private
reporting is unavailable to you, open a public issue that says only *"security
report, please provide a private channel"* and nothing else — no details.

Please include, as far as you have them:

- The version or commit you tested, and the Android version.
- What an attacker gains: reading traffic, forging a server, extracting a
  stored secret, escaping the tunnel.
- Steps to reproduce, or a proof of concept.
- Whether the problem is inherited from an upstream project (see below).

You will get an acknowledgement within a week. This is a volunteer project with
no paid on-call, so that is a realistic figure rather than an aspirational one;
if a week passes with no reply, please ping the thread.

## Scope

In scope:

- Anything that leaks traffic, DNS or credentials outside the tunnel.
- Anything that accepts a server certificate the configured trust policy should
  have rejected — the trust store, the pinning, the hostname check, the
  pre-flight, the proxy path.
- Anything that exposes a stored secret: the profile password, the IPsec
  pre-shared key, the proxy password.
- Anything reachable from another application on the device: exported
  components, the Intent surface, the tile.
- Weakened cryptography in the ported SSTP and PPP code.

Out of scope:

- Weaknesses of the VPN protocols themselves. L2TP/IPsec with a pre-shared key
  and SSTP with MSCHAPv2 have known properties; using them is the point of this
  client, not a defect in it.
- Configurations the user chose knowingly, such as the `INSECURE` trust policy
  in a debug build.
- A rooted or otherwise compromised device. If an attacker already runs code as
  root, the Keystore-backed secret store is not the boundary that failed.
- Reports produced by a scanner with no demonstrated impact.

## Upstream

Much of this code comes from two upstream projects:

- [TunnelForge](https://github.com/evokelektrique/tunnel-forge) — the Flutter
  application, the Android host and the native L2TP/IPsec engine.
- [Open SSTP Client](https://github.com/kittoku/Open-SSTP-Client) — the SSTP
  and PPP implementation.

If a problem is in code this project inherited unchanged, report it here as
well: this fork ships it, so this fork has to fix it. We will coordinate with
upstream rather than disclosing a shared vulnerability on our own schedule.

## Disclosure

We aim to have a fix released within 90 days of a confirmed report, and to
publish an advisory when it ships. Reporters are credited unless they ask not
to be. If a problem is being exploited, we will move faster and say so.
