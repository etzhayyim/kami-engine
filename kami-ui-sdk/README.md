# kami-ui-sdk (nested JS — retired)

> **SSoT is no longer here** (ADR-2607102200 addendum 11).

| need | go to |
|---|---|
| Portable math + DOM chrome (CLJC) | [`kami-engine-app-sdk`](https://github.com/kotoba-lang/kami-engine-app-sdk) — `kami-ui-sdk.*` + `kotoba.ui` |
| Game host surface (host/input/ui/audio) | [`host`](https://github.com/kotoba-lang/host) |
| **Legacy demo scripts** (graph.html) | `kami-web/vendor/kami-ui-sdk/*.js` (vendored copy for static demos only) |

The historical nested JS (`kami-ui.js`, `kami-motion.js`, …) was the port source for
app-sdk; live apps must not depend on this nested path.
