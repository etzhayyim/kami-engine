(require '[clojure.edn :as edn])

(def fixture (edn/read-string (slurp "fixtures/scenes/kami-modeler-space.edn")))
(def objects (get-in fixture [:project/scene :scene/objects]))
(defn mesh-valid? [{:mesh/keys [vertices faces]}]
  (and (seq vertices) (seq faces) (every? #(= 3 (count %)) vertices)
       (every? (fn [face] (and (>= (count face) 3)
                               (every? #(< -1 % (count vertices)) face))) faces)))
(assert (= :modeler-project (:kami/document fixture)))
(assert (= 2 (:kami/version fixture)))
(assert (every? #(mesh-valid? (:object/mesh %)) objects))
(assert (= {:renderer/primary :webgpu :renderer/fallback :webgl2
            :space/roles #{:game-level :world-building :asset-preview}}
           (:space/runtime fixture)))
(println "3D suite contract: modeler → engine space → game/world OK")
