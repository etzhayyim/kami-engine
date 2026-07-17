# kami-render

KAMI's backend shader assets and backend-neutral render-style contract.

## Reusable visual profiles

Scene authors select art-direction defaults with `:render/style`. This is not a
replacement for each material's `:model`; mixed surfaces remain valid.

```edn
{:render/style
 {:contract :kotoba.render/style-v1
  :profile :stylized
  :shading {:model :toon-pbr :bands 3 :threshold 0.46 :smoothness 0.06}
  :outline {:mode :screen-space :width-px 1.5
            :color [0.08 0.09 0.12 1.0]
            :depth-threshold 0.1 :normal-threshold 0.2}
  :color-grading {:tone-map :aces :saturation 1.08 :contrast 1.06}}
 :materials
 [{:id :hero :model :mtoon :base [0.8 0.4 0.2]}
  {:id :blade :model :pbr :metallic 0.9 :roughness 0.15}]}
```

Use `kami.render.style/scene-style` to validate the contract and
`pipeline-plan` to derive shader/pass intent. The two built-in profiles are:

- `:stylized`: MToon/skinned-MToon defaults, toon-PBR bands, screen-space
  outline through `kami-postfx`, and a stylized ACES grade.
- `:photoreal`: PBR defaults, no outline, and a neutral ACES grade.

The current executable outline mode is `:screen-space`. `:inverted-hull` is a
reserved contract value and deliberately fails validation until its geometry
pass exists. A renderer must never silently remove a requested visual feature.

Legacy MToon material `:outline 0.02` remains accepted as a per-material
`:outline-hint`; the scene-wide outline pass is controlled only by
`:render/style :outline`.

## GPU execution ABI

`shaders/style_postfx.wgsl` executes outline, ACES tone mapping, saturation,
and contrast in one fullscreen-triangle pass. `postfx-execution` produces its
portable bind-group and draw contract. Group 0 is stable across both profiles:

| Binding | Resource | Exact semantic |
|---:|---|---|
| 0 | `texture_2d<f32>` | resolved, single-sample, linear-HDR scene color |
| 1 | `texture_depth_2d` | WebGPU device depth `[0,1]`, clear value `1` |
| 2 | `texture_2d<f32>` | unit normal encoded `normal * 0.5 + 0.5`; zero means background |
| 3 | filtering sampler | scene-color sampling only |
| 4 | 64-byte uniform | `:kami.render/style-postfx-v1` |

Depth threshold is an absolute difference in device-depth units. Normal
threshold is `1 - dot(normalA, normalB)`. Outline width is rounded to
an integer radius and clamped to 1–8 pixels. The pass outputs display-linear
color to an sRGB target; hosts must not apply a second manual gamma transform.

The host declares `:normal-space :world` or `:view` in the execution resources.
Every pixel in an attachment must use the same space. Edge detection uses only
normal dot products, so either declared space produces the same result under a
rigid camera transform.

Both stylized and photoreal bind all resources. Photoreal sets
`outline-enabled = 0`, avoiding pipeline-layout variants while retaining ACES
and color grading. Upstream MSAA targets must be resolved before this pass.

Run the contract tests with `clojure -M:test` from this directory.

## Stylized character library

`kami.render.character-preset/resolve-character` composes reusable silhouette,
MToon material-role, outline, variation and LOD policies:

```clojure
(resolve-character
 {:family :stylized
  :preset :hero-balanced
  :silhouette-tier :hero
  :palette {:cloth [0.8 0.2 0.1 1.0]}})
```

Built-in presets are `:hero-balanced`, `:combat-readable`, and
`:crowd-efficient`; silhouette tiers are `:hero`, `:gameplay`, and `:crowd`.
Every result has stable `:skin`, `:cloth`, and `:metal` roles using the shared
`:kotoba.render/material-preset-v1` envelope. Each role contains MToon
base/shade, shade shift/tooniness, rim, highlight, metallic/roughness,
role-specific outline participation, deterministic variation, and the selected
silhouette's LOD policy.

The sibling `:photoreal` family and its PBR model are reserved in
`family-boundary`, but deliberately fail resolution until measured photoreal
presets exist. Its semantic roles and resolver envelope are already fixed, so
games will not need different asset slots or role ids when it is implemented.

### Equipment and silhouette detail kit

`kami.render.equipment-kit/resolve-kit` adds portable semantic mesh intents for
helmet, visor, left/right shoulder armour, chest armour, backpack, belt, and a
primary weapon. Parts attach to stable humanoid semantics such as
`:humanoid/head`, `:humanoid/chest`, and `:humanoid/right-hand`; the weapon also
declares `:weapon/grip-primary`.

```clojure
(resolve-kit {:family :stylized
              :tier :gameplay
              :entity-id :operator/alpha
              :character-preset :combat-readable})
```

The resolved `:meshes` reuse render-IR fields `:id`, `:skin`, and `:material`
while `:mesh-semantic` remains an asset-registry lookup rather than a fake URL.
Hero/gameplay/crowd each select explicit LOD and triangle budgets. Culled parts
remain in `:parts` with `:enabled? false` and a reason, providing auditable LOD
evidence. Variation uses a portable stable seed derived from entity and part
IDs. Every part carries its material role and outline policy.

The photoreal sibling reserves the same part, attachment, mesh and material-role
API but fails resolution until its assets and presets are genuinely available.

### Executor material lowering

`kami.render.character-material/lower-library` converts
`:kotoba.render/material-preset-v1` character envelopes into
`:kotoba.render/portable-material-v1` records. KAMI owns these semantic roles:

`skin`, `cloth`, `metal`, `visor`, `emissive`, `accent`, and `weapon`.

Each output contains base color, toon shade color/shift/tooniness,
metallic/roughness, emissive color/intensity, highlight, rim and outline. Its
`:executor :uniforms` directly matches the existing MToon uniform semantics:
`albedo`, `subsurface-color`, `sss-r0/r1/r2`, `hair-scatter`, and RGB emission.
Consequently each role differs in its complete shader response, not merely its
palette color.

```clojure
(lower-library character
               {:team-palette {:cloth [0.1 0.7 0.2 1.0]
                               :accent [0.95 0.8 0.1 1.0]}})
```

Team palettes may override only `cloth` and `accent`; skin, metal, visor,
emissive and weapon overrides fail validation. The equipment kit automatically
selects only materials referenced by enabled hero/gameplay/crowd parts for its
`:material-registry`. Photoreal uses the same API boundary but is rejected until
its PBR lowering is implemented.

### Actual stylized weapon meshes

`kami.render.weapon-mesh/resolve-weapon` generates an actual indexed rifle,
not a placeholder stick. It reuses `kotoba.render.mesh` primitives and combines
receiver, barrel, stock, grip, magazine, optic, muzzle, and handguard components
into flat `positions/normals/uvs/indices` arrays with contiguous material
ranges. Material roles are `weapon`, `accent`, and `emissive` and resolve through
the executor material library above.

Hero and gameplay retain all eight components with different radial detail;
crowd retains receiver/barrel/stock/magazine as a readable reduced rifle and
records the other components as LOD-culled. Each tier enforces its actual
generated triangle budget.

The result includes right-hand attachment, primary grip, and support-hand grip
semantic sockets, screen-space outline participation, and deterministic stock,
handguard, optic, and muzzle proportions derived from the entity id. The
photoreal family reserves exactly the same API but is rejected until implemented.
