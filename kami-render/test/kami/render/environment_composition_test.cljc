(ns kami.render.environment-composition-test
  (:require [clojure.test :refer [deftest is]]
            [kami.render.character-camera :as camera]
            [kami.render.environment-composition :as composition]))

(def subject {:min [-0.62 0.0 -0.45] :max [0.62 2.08 0.45]})
(def resolved (camera/resolve-camera {:subject-id :operator/hero :subject-bounds subject
                                     :orbit :front}))

(deftest projects-all-aabb-corners-to-safe-screen-bounds
  (let [bounds {:min [2.1 0.0 -0.2] :max [2.6 1.2 0.3]}
        projected (composition/project-aabb (:camera resolved) bounds)]
    (is (= 8 (count (:corners projected))))
    (is (every? #(<= 0.0 % 1.0) (concat (:min projected) (:max projected))))
    (is (> (* 0.5 (+ (get-in projected [:min 0]) (get-in projected [:max 0]))) 0.5))
    (is (< (first (:depth-range projected)) (second (:depth-range projected))))))

(deftest selection-is-deterministic-grounded-and-outside-subject-padding
  (let [candidates [{:id :prop/right :priority 4
                     :bounds {:min [2.1 0.0 -0.2] :max [2.6 1.2 0.3]}}
                    {:id :prop/left :priority 7
                     :bounds {:min [-2.5 0.0 0.0] :max [-2.0 0.8 0.4]}}
                    {:id :prop/behind-subject :priority 99
                     :bounds {:min [-0.2 0.0 0.1] :max [0.2 0.8 0.35]}}]
        input {:resolved-camera resolved :subject-bounds subject :candidates candidates}
        a (composition/compose input) b (composition/compose input)]
    (is (= a b))
    (is (= [:prop/left :prop/right] (mapv :id (:placements a))))
    (is (= [:prop/behind-subject] (mapv :id (:rejected a))))
    (is (= [:subject-exclusion] (get-in a [:rejected 0 :reasons])))
    (is (every? #(<= (get-in % [:ground-contact :error]) 0.025) (:placements a)))
    (is (:world-context-retained? (:evidence a)))))

(deftest selection-never-removes-world-or-changes-subject-selection
  (let [result (composition/compose
                {:resolved-camera resolved :subject-bounds subject
                 :candidates [{:id :prop/right
                               :bounds {:min [2.1 0.0 -0.2] :max [2.6 1.2 0.3]}}]})]
    (is (= :preserve-all (get-in result [:render-selection :world :mode])))
    (is (false? (get-in result [:render-selection :world :removed?])))
    (is (= #{:operator/hero} (get-in result [:render-selection :skinned :entity-ids])))))

(deftest invalid-compositions-fail-closed-with-evidence
  (let [unsafe [{:id :prop/mask :bounds {:min [-0.3 0.0 0.0] :max [0.3 1.5 0.3]}}]]
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"failed closed"
                          (composition/compose {:resolved-camera resolved
                                                :subject-bounds subject
                                                :candidates unsafe}))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (composition/compose {:family :photoreal :resolved-camera resolved
                                     :subject-bounds subject :candidates []}))))
