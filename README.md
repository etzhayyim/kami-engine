# kami-engine

> **Note (ADR-2607102200 addendum 11):** nested `kami-ui-sdk` JS retired → `kami-engine-app-sdk` (CLJC) + `kami-web/vendor/kami-ui-sdk` (demo only).

`kami-engine` is now a Kotoba/CLJ/EDN/WIT asset and contract repository for the
Kami engine family.

The former in-repository Rust workspace has been removed. Native render,
physics, robotics, WebGPU, and packaging runtimes should live in adapter
repositories and consume the data, WIT, scene, fixture, and CLJ/CLJC assets kept
here.

## Current Scope

- `wit/` defines the host interface contract.
- `docs/adapter-registry.edn` names adapter-owned native/backend targets and
  keeps them outside this default repository.
- `scripts/wit_test.clj` checks the EDN IDL, generated WIT, and kami-clj builtin
  host import map agree.
- `kami-*-scene/data/` and `fixtures/` retain EDN, YAML, CSV, URDF, and scene
  assets for adapter conformance.
- `kami-*-clj/` projects retain CLJ/CLJC authoring, manga, SIP, and web
  surfaces.
- `kami-web/`, `kami-web-modelb/`, and shader/data assets remain as web-facing
  non-Rust fixtures.

## Verify

```bash
bb wit-check
bb adapter-check
bb test
```

The default path should not contain `Cargo.toml`, `Cargo.lock`, `.rs`,
`rust-toolchain*`, or `.cargo/` files.

## Nested monorepo cleanup (ADR-2607102200)

- Removed nested `kami-engine-sdk-clj/` (standalone `kotoba-lang/kami-engine-sdk-clj` is SSoT).
- Moved `kami-webgpu-rs` WGSL assets to `fixtures/webgpu-rs-shaders/` (runtime is `webgpu`).
- Removed 1–2 file README stubs that duplicate standalone scene/app repos.
- Kept: `kami-engine-clj/`, `kami-render/` shaders, `kami-ui-sdk/` JS, scene data that is still asset-authoritative here, `wit/`, `fixtures/`.
