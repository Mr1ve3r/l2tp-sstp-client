# Licensing

## Short version

This project is distributed under **GPL-3.0-or-later**. It combines code from
two upstream projects with different licences, and the combination is what
forces that choice.

## Where the code comes from

| Component | Upstream | Upstream licence |
|---|---|---|
| Flutter UI, Android host application, native L2TP/IPsec engine | [TunnelForge](https://github.com/evokelektrique/tunnel-forge) | GPL-3.0 |
| SSTP protocol implementation, PPP frames and clients | [Open SSTP Client](https://github.com/kittoku/Open-SSTP-Client) | MIT |

## Why GPL-3.0-or-later

TunnelForge is GPL-3.0. A derivative work of GPL-3.0 code must itself be
distributed under GPL-3.0 (or a later version, when the upstream grant allows
it). That is not a preference — it is the condition on which the TunnelForge
code may be used at all.

MIT is one-way compatible with GPL-3.0: MIT-licensed code may be incorporated
into a GPL-3.0 work provided the MIT copyright notice and permission notice
travel with it. The reverse is not true, which is why the combined work cannot
be MIT.

So: GPL-3.0 in, MIT in, GPL-3.0 out.

## Obligations this creates

### For this project

- The full GPL-3.0 text is in [`LICENSE`](../LICENSE).
- [`NOTICE`](../NOTICE) lists both upstream projects and their licences.
- The verbatim MIT text of Open SSTP Client is at
  [`third_party/open-sstp-client/LICENSE`](../third_party/open-sstp-client/LICENSE).
- Every source file derived from Open SSTP Client carries the MIT attribution
  header reproduced below. Renaming the package from `kittoku.osc.*` to
  `io.github.mr1ve3r.combined.engine.sstp.*` does not discharge that obligation.
- [`third_party/open-sstp-client/PROVENANCE.md`](../third_party/open-sstp-client/PROVENANCE.md)
  records, per file, which upstream file it came from and how heavily it was
  changed — including files deliberately *not* imported.
- Existing TunnelForge copyright headers are never removed or rewritten.

The attribution header, added by us because upstream ships no per-file headers:

```kotlin
/*
 * Derived from Open SSTP Client
 * https://github.com/kittoku/Open-SSTP-Client
 * Copyright (c) 2019 KOBAYASHI Ittoku
 * Licensed under the MIT License.
 * See third_party/open-sstp-client/LICENSE for the full text.
 *
 * Modifications Copyright (C) <year> <owner>
 * Licensed under GPL-3.0-or-later as part of this project.
 */
```

### If you fork this project

GPL-3.0 travels with the code. A fork, a rebrand, or a repackaged APK must:

1. Stay under GPL-3.0-or-later.
2. Ship the complete corresponding source of whatever binary is distributed,
   including any modifications, and including the native C engine.
3. Keep `LICENSE`, `NOTICE`, `third_party/open-sstp-client/LICENSE` and the
   per-file MIT headers intact.
4. Keep attribution to both TunnelForge and Open SSTP Client.
5. Not add further restrictions — no "no commercial use" clause, no anti-fork
   clause. GPL-3.0 §7 does not permit them.

Distributing the APK without the source, or under a different licence, is a
licence violation with respect to two separate copyright holders.

## Trademarks and endorsement

This is an independent fork. It is not affiliated with, sponsored by, or
endorsed by the authors of TunnelForge or Open SSTP Client. Do not imply
otherwise in a fork of this fork.

## Verification

The provenance checklist in
[`third_party/open-sstp-client/PROVENANCE.md`](../third_party/open-sstp-client/PROVENANCE.md#5-verification-checklist)
runs before every release. A failure there is a licence violation, not a lint
warning.
