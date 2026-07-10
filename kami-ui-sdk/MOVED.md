# kami-ui-sdk (nested JS) — retirement path

**Status (ADR-2607102200 addendum 10):** live port target is
`kotoba-lang/kami-engine-app-sdk` (CLJC browser chrome + `kotoba.ui`).

This nested JS grab-bag (`kami-ui.js`, `kami-motion.js`, `kami-sound.js`, …)
remains only as a reference until remaining consumers migrate. Do not add
new features here.

| need | go to |
|---|---|
| HUD / motion / sound / RTC | `kami-engine-app-sdk` |
| Game host surface | `kotoba-lang/host` (`kami.host`/`input`/`ui`/`audio`) |
