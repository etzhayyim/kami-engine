(ns kami.render.style
  "Stable render-style boundary shared by games and KAMI renderer hosts.

  A style selects defaults and a render pipeline; a material `:model` still
  selects the shader for one surface.  Keeping those concepts separate lets a
  stylized scene use a physically shaded metal and a photoreal scene use an
  unlit display without inventing incompatible material schemas.")

(def contract :kotoba.render/style-v1)

(def ^:private capabilities
  {:shading-models #{:toon-pbr :pbr}
   :outline-modes #{:none :screen-space}
   :reserved-outline-modes #{:inverted-hull}})

(def profiles
  {:stylized
   {:contract contract
    :profile :stylized
    :shading {:model :toon-pbr
              :bands 3
              :threshold 0.46
              :smoothness 0.06}
    :outline {:mode :screen-space
              :width-px 1.5
              :color [0.08 0.09 0.12 1.0]
              :depth-threshold 0.1
              :normal-threshold 0.2}
    :color-grading {:tone-map :aces
                    :saturation 1.08
                    :contrast 1.06}}

   :photoreal
   {:contract contract
    :profile :photoreal
    :shading {:model :pbr}
    :outline {:mode :none
              :width-px 0.0
              :color [0.0 0.0 0.0 1.0]
              :depth-threshold 0.1
              :normal-threshold 0.2}
    :color-grading {:tone-map :aces
                    :saturation 1.0
                    :contrast 1.0}}})

(defn- deep-merge
  [& maps]
  (letfn [(merge* [& xs]
            (if (every? map? xs)
              (apply merge-with merge* xs)
              (last xs)))]
    (apply merge* maps)))

(defn normalize
  "Resolve a partial `:render/style` value against its named profile.

  Unknown contracts, profiles and currently unavailable pipeline modes fail
  loudly.  This prevents a requested visual tier from silently becoming a
  cheaper/different renderer."
  [style]
  (let [style (or style {})
        profile (or (:profile style) :photoreal)
        base (get profiles profile)
        result (deep-merge base style)
        requested-contract (:contract result)
        shading-model (get-in result [:shading :model])
        outline-mode (get-in result [:outline :mode])]
    (when-not base
      (throw (ex-info "Unknown KAMI render style profile"
                      {:profile profile :known-profiles (set (keys profiles))})))
    (when-not (= contract requested-contract)
      (throw (ex-info "Unsupported KAMI render style contract"
                      {:contract requested-contract :supported contract})))
    (when-not (contains? (:shading-models capabilities) shading-model)
      (throw (ex-info "Unsupported KAMI shading model"
                      {:model shading-model
                       :supported (:shading-models capabilities)})))
    (when (contains? (:reserved-outline-modes capabilities) outline-mode)
      (throw (ex-info "KAMI outline mode is reserved but not executable"
                      {:mode outline-mode :supported (:outline-modes capabilities)})))
    (when-not (contains? (:outline-modes capabilities) outline-mode)
      (throw (ex-info "Unsupported KAMI outline mode"
                      {:mode outline-mode :supported (:outline-modes capabilities)})))
    result))

(defn scene-style
  "Return a validated style from a render-IR/scene map."
  [scene]
  (normalize (:render/style scene)))

(defn pipeline-plan
  "Compile style data into renderer-neutral shader/pass intent.

  Hosts map these stable ids to WebGPU/WebGL/native pipelines.  The result is
  data rather than backend objects, so kotoba-lang/webgpu can consume it
  without duplicating style policy."
  [style]
  (let [{:keys [profile shading outline color-grading] :as resolved}
        (normalize style)
        toon? (= :toon-pbr (:model shading))
        outlined? (= :screen-space (:mode outline))]
    {:contract contract
     :profile profile
     :surface-shader (if toon? :mtoon :pbr)
     :skinned-surface-shader (if toon? :skinned-mtoon :pbr)
     :passes (cond-> [:opaque :alpha-mask :alpha-blend]
               outlined? (conj {:pass :outline
                                :implementation :kami-postfx/screen-space
                                :params (dissoc outline :mode)})
               true (conj {:pass :color-grading :params color-grading}))
     :material-defaults (if toon?
                          {:model :mtoon
                           :shade-bands (:bands shading)
                           :shade-threshold (:threshold shading)
                           :shade-smoothness (:smoothness shading)}
                          {:model :pbr :metallic 0.0 :roughness 0.5})
     :resolved-style resolved}))

(defn material
  "Apply style defaults without replacing explicit per-material choices.

  Legacy numeric `:outline` is translated into a material-local hint only;
  scene outline execution remains owned by `:render/style`."
  [style material]
  (let [defaults (:material-defaults (pipeline-plan style))
        legacy-outline (:outline material)]
    (cond-> (merge defaults material)
      (number? legacy-outline)
      (assoc :outline-hint {:width-world legacy-outline}))))

(defn supported?
  "True when a style can execute without a silent fallback."
  [style]
  (try (normalize style) true
       (catch #?(:clj Exception :cljs :default) _ false)))
