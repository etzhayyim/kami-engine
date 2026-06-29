# ADR-0049: Cross-Platform Services Seam — One Achievement/Stat/Presence API for Steam, PSN, Switch, iOS, Android

**Date**: 2026-06-27
**Status**: Proposed — implemented (neutral seam + 5 store backends + per-store catalog/lint/config + per-target packaging skeletons). Only the Steam binding links a real SDK; the console/mobile backends are in-repo skeletons their native shells complete.
**Author**: kami-engine team
**Related**: ADR-0048 (Valve Steam — the seam this generalizes), ADR-0037 (cross-platform host/renderer/input/packaging for iOS/Android/PS5/Switch), ADR-0040 (everything describable is EDN), `kami-script-runtime/src/services.rs`, `kami-script-runtime/src/platform.rs`

---

## Context

ADR-0048 added a **Valve-specific** platform-services seam: the guest called `steam-unlock!` /
`steam-set-stat!` / `steam-rich-presence!`, which compiled to a `kami:engine/steam` interface a
Steamworks backend drained. That shipped achievements/stats/presence to Steam.

The product targets the *other four* stores too — PSN Trophies (PS5), Nintendo (Switch), Game Center
(iOS), Google Play Games (Android). ADR-0037 already ports the **host, renderer, input, and
packaging** to all of them; what is missing is the **store services**.

Naming the seam after one store was a mistake to repeat. The engine's defining premise (ADR-0037/0038)
is **write-once**: one `game.wasm` runs on every platform. A game that branched on
`steam-unlock!` vs `psn-unlock!` would violate that at the most visible layer — the gameplay script.
Achievements, stats, and presence are the *same concept* on every store; only the **id format**
differs (Steam API name `"FIRST_WIN"`, PSN trophy int `1`, Game Center reverse-DNS `"grp.first_win"`,
Google Play `"CgkI…"`, Nintendo title id). So the seam should be **platform-neutral**, and the
per-store id should be data, not code.

Two constraints carry over from ADR-0048 and must hold across *all five* stores:

1. **Determinism.** The services calls must stay **output-only** — nothing read back into the i64
   sim — or a Steam run would diverge from a PSN run, breaking the wasmtime↔wasmi golden-frame parity
   (ADR-0037) that lockstep co-op / replay / headless CI depend on. This matters most on the no-JIT
   consoles (iOS/PS5/Switch run on wasmi); the output-only queue works there unchanged.
2. **Gated SDKs.** Steamworks needs the SDK + App ID + client; PSN/Switch need NDA console SDKs;
   Game Center/Google Play need GameKit/JNI on a device. None build in CI, so — like the ADR-0037
   console GPU backend — the **seam ships now, the platform calls are gated**.

---

## Decision

Generalize ADR-0048's Steam seam to a **platform-neutral services seam**: one interface, logical
keys, a per-store id catalog, and one backend per store selected by target.

### 1. One neutral interface; the guest names logical keys

`kami:engine/steam` → **`kami:engine/services`** (output-only, like `audio`):

```wit
interface services {                           // wit/kami-game/world.wit (+ kami-interface.edn IDL)
    achievement-unlock: func(key-ptr: s32, key-len: s32);
    stat-set:           func(key-ptr: s32, key-len: s32, value: s64);
    presence-set:       func(key-ptr: s32, key-len: s32, val-ptr: s32, val-len: s32);
}
```

The CLJ builtins are store-neutral and name a **logical key**:

```clojure
(achievement-unlock! "first_blood")     ; NOT "FIRST_BLOOD" / 1 / "grp.first_blood"
(stat-set! "kills" n)
(presence-set! "status" "in_combat")
```

One `game.wasm` reports to every store; the host maps `"first_blood"` → the active store's id.

### 2. Per-store id is EDN data (`services.edn`), not branching code

The catalog (ADR-0040) declares each logical key once with its id in every store:

```clojure
{:services/app-id {:steam 480 :psn "NPWR00001_00" :gamecenter "group.…" :google "…" :switch "0x…"}
 :services/achievements
 [{:key "first_blood" :steam "FIRST_BLOOD" :psn 1 :gamecenter "grp.first_blood" :google "CgkI…" :switch 1}
  …]
 :services/stats [{:key "kills" :steam "kills" :psn 1 :gamecenter "grp.kills" :google "CgkI…"}]
 :services/rich-presence [{:key "status"}]}
```

The host loads the column for the active store into a `services::ServiceIds` (logical→store id) map;
the backend resolves at call time (unmapped keys pass through).

### 3. One backend per store, selected by target

`platform::Target::service_store()` maps each target to its store, and `ServiceStore::feature()`
to the cargo feature that links its backend:

| Target | Store | Backend | Feature | Reality |
|---|---|---|---|---|
| Mac/Linux/Windows | Steam | `steamworks-rs` | `steam-sdk` | real binding (needs SDK+AppID+client) — ADR-0048 |
| iOS | Game Center | GameKit skeleton | `gamecenter` | skeleton; real `GKAchievement` in the Swift shell (objc) |
| Android | Google Play | Play Games skeleton | `googleplay` | skeleton; real `AchievementsClient` via JNI in the NativeActivity shell |
| PS5 | PSN | NpTrophy skeleton | `psn-sdk` | skeleton; real `sceNpTrophy…` in the NDA console shell |
| Switch | Nintendo | title-side skeleton | `switch-sdk` | skeleton; title persists/NPLN in the NDA console shell |
| Web | none | `StubServices` | — | browser path |

Off-feature everywhere is `StubServices` (log + no-op), linked into CI/web/off-platform/headless so
the imports always resolve and the sim stays deterministic. The four console/mobile backends link
**no external crate** — they resolve the id and log the store call they *would* make, with the one
integration method marked `TODO(shell)`; the real GameKit/JNI/NpTrophy/Nintendo call is the
per-platform native shell's job (ADR-0037 §4), which holds the NDA SDK / objc / JNI. So they compile
under their feature without a device.

### 4. Host plumbing mirrors the audio queue, unchanged from ADR-0048

`HostState::service_queue: Vec<ServiceEvent>` (`AchievementUnlock | StatSet | PresenceSet`), filled
by `bind_services`, drained by `drain_service_queue()` into a `ServicesBackend::apply` each frame.
The player constructs the backend with `services::default_backend(ServiceIds)`.

### 5. Tooling: lint + per-store projection + per-target packaging

`scripts/services.clj` (the bb side):

- **`bb services-lint <game>`** — referenced logical keys (walked from `logic.clj`) ⊆ declared keys;
  **fails (exit 2)** on a missing key, and **warns** if a declared achievement lacks the id for a
  shipped store (per-store completeness).
- **`bb services-config <game> <store>`** — projects `services.edn` → `dist/<store>/<game>/`: always
  a `<store>-ids.edn` (the logical→store id map the host loads) + a schema; plus per-store extras
  (Steam: `steam_appid.txt` + SteamPipe VDF; PSN/Switch: NDA README; Game Center / Google Play: id
  manifests).
- **`bb services-package <game> <target>`** — lint + config(store-for-target) + the native-shell
  step, which diverges by target: desktop assembles the real Steam depot; iOS/Android stage the game
  data + resolved ids and print the remaining `.ipa`(Xcode+GameKit) / `.aab`(Gradle+JNI) shell step;
  PS5/Switch project the ids and flag the NDA console SDK. The real store SDK links only with
  `KAMI_STORE_SDK=1` (else `StubServices`).

---

## Architecture

```
   write-once guest (logical keys only)
   logic.clj: (achievement-unlock! "first_blood") (stat-set! "kills" n) (presence-set! …)
   services.edn: first_blood → {steam:FIRST_BLOOD psn:1 gamecenter:grp.… google:CgkI… switch:1}
          │ kami-engine-clj compiles → import kami:engine/services
          ▼
   kami-script-runtime host:  bind_services → service_queue → drain_service_queue()
          │                                   │ ServicesBackend::apply  (+ ServiceIds resolver)
          ▼ default_backend(ids), by target store feature
   ┌─────────┬────────────┬─────────────┬──────────┬───────────┬────────┐
  Steam     GameCenter   GooglePlay     PSN       Nintendo   (none)
 steamworks  GameKit*     Play Games*   NpTrophy*  title*     Stub
  (real)     (shell)      (shell)       (shell)    (shell)    (log)
   └──────────────── output-only → sim stays deterministic across all ───────────────┘
```

`*` = in-repo skeleton; the real platform call lives in the ADR-0037 native shell.

---

## Consequences

**Gained**
- One write-once `game.wasm` reports achievements/stats/presence to **all five stores** — the guest
  never branches on platform; it names logical keys, the host maps them via EDN data.
- Determinism holds across stores *and* backends (output-only), so the no-JIT consoles get services
  for free and golden-frame/replay/co-op are unaffected.
- Adding a store = one `ServicesBackend` impl + one catalog column + one `ServiceStore` arm; the
  guest, the seam, and every existing game are untouched.
- The ADR-0048 Steam work is subsumed, not duplicated: Steam is now one backend of five.

**Costs / risks**
- Only the Steam backend links a real SDK (unvalidated in CI — ADR-0048). The PSN/Switch/Game
  Center/Google Play backends are **skeletons**: they resolve ids and log; the actual NpTrophy /
  Nintendo / GameKit / Play Games calls are the native shell's NDA/objc/JNI work (ADR-0037 §4).
  "Cross-platform services" = "every layer portable and deterministic except the per-store SDK call."
- Output-only is deliberate: a game cannot *query* unlocked-state/owned-DLC from the sim (it would
  diverge runs). Such reads, if needed, enter as pre-tick host input — a future ADR.
- Logical-key indirection adds a catalog the author must keep complete; `services-lint`'s per-store
  warning is the guard, but a missing id silently passes through as the logical key on that store
  until lint is heeded.

**Phased rollout**
1. ✅ **Neutral seam** — `kami:engine/services` (EDN IDL + world.wit + ast.rs Builtins, all three
   agree via `bb wit-check`); `service_queue`/`bind_services`/`drain_service_queue`; `ServiceEvent` /
   `ServicesBackend` / `StubServices` / `ServiceIds` / `default_backend`. Tests: clj compiles +
   imports the interface; runtime fills/drains end-to-end (both wasmtime + wasmi); stub fan-out; id
   resolve/pass-through; `platform::service_store` matrix + spec_edn `:services` contract.
2. ✅ **Five store backends** — Steam (real `steamworks`), PSN/Switch/Game Center/Google Play
   (dep-free skeletons, compile under their feature). `Target::service_store` + `ServiceStore::feature`.
3. ✅ **Catalog + tooling** — `services.edn` (per-store ids), `bb services-lint` (green on survivors,
   red on undeclared key), `bb services-config <store>`, `bb services-package <target>` (desktop
   depot / iOS+Android staging / PS5+Switch NDA projection). `survivors` emits logical keys.
4. **Native shells fill the `TODO(shell)` calls** — Swift+GameKit (iOS), NativeActivity+JNI (Android),
   NDA console shells (PS5/Switch); validated on real devices/SDKs. Out of this repo's scope.

---

## Alternatives Considered

1. **Keep `steam-*` and add parallel `psn-*` / `gamecenter-*` builtins.** Rejected: the game would
   branch on platform, breaking write-once at the gameplay layer — the engine's core premise.

2. **Bake per-store ids into the guest at compile time (one wasm per store).** Rejected: forks the
   write-once artifact five ways and re-introduces a per-platform build of *gameplay*. The logical
   key + host-side `ServiceIds` keeps one wasm.

3. **Link every store SDK directly now.** Rejected: PSN/Switch are NDA, Game Center/Google Play need
   a device, Steamworks needs the SDK+AppID — none build in CI. Skeletons behind features keep the
   tree green; the shells complete them.

4. **A two-way interface (query unlocked-state from the sim).** Rejected: any read that steers the
   sim diverges store-to-store and backend-to-backend, breaking the determinism the console targets
   rely on. Output-only by construction.

---

## References

- ADR-0048 — Valve Steam seam (the Steam backend, now one of five)
- ADR-0037 — cross-platform host/renderer/input/packaging + the native-shell seam (§4) the store backends plug into
- ADR-0040 — everything describable is EDN (the `services.edn` catalog)
- `wit/kami-interface.edn` / `wit/kami-game/world.wit` — `interface services` (single-sourced, `bb wit-check`)
- `kami-script-runtime/src/services.rs` — `ServiceEvent` / `ServicesBackend` / `ServiceIds` / `default_backend` + 5 backends
- `kami-script-runtime/src/platform.rs` — `ServiceStore` / `Target::service_store`
- `scripts/services.clj` — lint + per-store projection + per-target packaging
