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

(deftest ground-contact-must-be-in-visible-lower-frame-band
  (let [sky-camera (assoc-in resolved [:camera :look-at] [0.0 -2.0 0.0])
        floating {:id :prop/floating-looking
                  :bounds {:min [2.1 0.0 -0.2] :max [2.6 0.10 0.3]}}
        failure (try
                  (composition/compose {:resolved-camera sky-camera
                                        :subject-bounds subject :candidates [floating]})
                  nil
                  (catch #?(:clj Exception :cljs js/Error) error error))
        data (ex-data failure)
        contact-y (get-in data [:evaluated 0 :ground-contact :projection :screen 1])]
    ;; The AABB is globally on-screen, but its base projects into sky/horizon.
    (is (<= 0.19 contact-y 0.21))
    (is (= [:ground-contact-outside-visible-ground-band]
           (get-in data [:evaluated 0 :reasons])))
    (is (= [0.38 0.92] (get-in data [:evidence :ground-contact-screen-y-range]))))
  (let [valid (composition/compose
               {:resolved-camera resolved :subject-bounds subject
                :candidates [{:id :prop/lower-frame
                              :bounds {:min [2.1 0.0 -0.2] :max [2.6 1.2 0.3]}}]})
        y (get-in valid [:placements 0 :ground-contact :projection :screen 1])]
    (is (<= 0.38 y 0.92))
    (is (= y (get-in valid [:evidence :selected-ground-contact-screen-y
                            :prop/lower-frame])))))

(deftest required-regions-reserve-slots-before-priority-fill
  (let [left (fn [id priority x]
               {:id id :priority priority :composition-region :foreground-left
                :bounds {:min [x 0.0 -0.2] :max [(+ x 0.35) 0.7 0.25]}})
        candidates [(left :prop/left-a 100 -2.8)
                    (left :prop/left-b 90 -2.4)
                    (left :prop/left-c 80 -2.0)
                    {:id :prop/right-only :priority 1
                     :composition-region :foreground-right
                     :bounds {:min [2.1 0.0 -0.2] :max [2.5 0.7 0.25]}}]
        result (composition/compose
                {:resolved-camera resolved :subject-bounds subject :candidates candidates
                 :policy {:maximum-selected 2
                          :required-composition-regions
                          #{:foreground-left :foreground-right}}})]
    (is (= #{:prop/left-a :prop/right-only} (set (map :id (:placements result)))))
    (is (= {:foreground-left 1 :foreground-right 1}
           (get-in result [:evidence :selected-region-counts])))
    (is (empty? (get-in result [:evidence :missing-composition-regions])))))

(deftest missing-required-composition-region-fails-closed
  (let [failure (try
                  (composition/compose
                   {:resolved-camera resolved :subject-bounds subject
                    :candidates [{:id :prop/left :composition-region :foreground-left
                                  :bounds {:min [-2.5 0.0 -0.2] :max [-2.1 0.7 0.25]}}]
                    :policy {:required-composition-regions
                             #{:foreground-left :foreground-right}}})
                  nil
                  (catch #?(:clj Exception :cljs js/Error) error error))]
    (is (= [:foreground-right]
           (get-in (ex-data failure) [:evidence :missing-composition-regions])))))
