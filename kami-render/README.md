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

Run the contract tests with `clojure -M:test` from this directory.
