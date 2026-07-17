(ns kami.render.style-test
  (:require [clojure.test :refer [deftest is testing]]
            [kami.render.style :as style]))

(deftest profiles-compile-to-distinct-reusable-pipelines
  (let [toon (style/pipeline-plan {:contract style/contract :profile :stylized})
        real (style/pipeline-plan {:contract style/contract :profile :photoreal})]
    (is (= :mtoon (:surface-shader toon)))
    (is (= :skinned-mtoon (:skinned-surface-shader toon)))
    (is (= :pbr (:surface-shader real)))
    (is (= :pbr (:skinned-surface-shader real)))
    (is (some map? (:passes toon)))
    (is (not-any? #(= :outline (:pass %)) (filter map? (:passes real))))))

(deftest style-does-not-overwrite-explicit-material-model
  (let [m (style/material {:contract style/contract :profile :stylized}
                          {:id :wet-metal :model :pbr :metallic 0.9 :roughness 0.12})]
    (is (= :pbr (:model m)))
    (is (= 0.9 (:metallic m)))
    (is (= 3 (:shade-bands m)))))

(deftest overrides-use-the-shared-style-v1-shape
  (let [resolved (style/scene-style
                  {:render/style
                   {:contract :kotoba.render/style-v1
                    :profile :stylized
                    :shading {:bands 2}
                    :outline {:width-px 2.25}
                    :color-grading {:saturation 1.2}}})]
    (is (= :toon-pbr (get-in resolved [:shading :model])))
    (is (= 2 (get-in resolved [:shading :bands])))
    (is (= 2.25 (get-in resolved [:outline :width-px])))
    (is (= 1.2 (get-in resolved [:color-grading :saturation])))))

(deftest unsupported-capability-never-silently-downgrades
  (testing "reserved inverted hull must wait for an executable pipeline"
    (is (false? (style/supported?
                 {:contract style/contract :profile :stylized
                  :outline {:mode :inverted-hull}}))))
  (testing "contract mismatch is explicit"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (style/normalize {:contract :some.future/style-v2
                                   :profile :stylized})))))

(deftest legacy-outline-is-retained-as-a-non-pipeline-hint
  (let [m (style/material {:contract style/contract :profile :stylized}
                          {:model :mtoon :outline 0.02})]
    (is (= 0.02 (:outline m)))
    (is (= {:width-world 0.02} (:outline-hint m)))))
