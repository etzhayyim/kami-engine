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

(deftest evidence-separates-unsafe-rejections-from-safe-capacity-truncation
  (let [candidates [{:id :prop/a :priority 3
                     :bounds {:min [2.0 0.0 -0.2] :max [2.25 0.6 0.2]}}
                    {:id :prop/b :priority 2
                     :bounds {:min [2.4 0.0 -0.2] :max [2.65 0.6 0.2]}}
                    {:id :prop/c :priority 1
                     :bounds {:min [-2.5 0.0 -0.2] :max [-2.2 0.6 0.2]}}
                    {:id :prop/unsafe-mask :priority 99
                     :bounds {:min [-0.2 0.0 0.1] :max [0.2 0.8 0.35]}}]
        result (composition/compose
                {:resolved-camera resolved :subject-bounds subject :candidates candidates
                 :policy {:maximum-selected 1}})
        evidence (:evidence result)]
    (is (= 1 (:selected-count evidence) (count (:placements result))))
    (is (= 1 (:rejected-count evidence) (count (:rejected result))))
    (is (= 2 (:unselected-safe-count evidence) (count (:unselected-safe result))))
    (is (= (set (:unselected-safe result)) (set (:unselected-safe evidence))))
    (is (= (:candidate-count evidence)
           (+ (:selected-count evidence) (:rejected-count evidence)
              (:unselected-safe-count evidence))))))

(deftest semantic-screen-side-must-match-actual-projection
  (let [right-bounds {:min [2.1 0.0 -0.2] :max [2.6 0.7 0.25]}
        failure (try
                  (composition/compose
                   {:resolved-camera resolved :subject-bounds subject
                    :candidates [{:id :prop/spoofed-left :screen-side :left
                                  :composition-region :foreground-left
                                  :bounds right-bounds}]
                    :policy {:required-composition-regions #{:foreground-left}}})
                  nil
                  (catch #?(:clj Exception :cljs js/Error) error error))]
    (is (= [:screen-side-mismatch]
           (get-in (ex-data failure) [:evaluated 0 :reasons])))
    (is (= [:foreground-left]
           (get-in (ex-data failure) [:evidence :missing-composition-regions]))))
  (let [valid (composition/compose
               {:resolved-camera resolved :subject-bounds subject
                :candidates [{:id :prop/right :screen-side :right
                              :composition-region :foreground-right
                              :bounds {:min [2.1 0.0 -0.2] :max [2.6 0.7 0.25]}}]
                :policy {:required-composition-regions #{:foreground-right}}})]
    (is (= [:prop/right] (mapv :id (:placements valid))))))

(deftest candidate-specific-ground-bands-support-building-and-foreground-depths
  (let [candidates [{:id :building/background :composition-region :building
                     :ground-contact-screen-y-range [0.44 0.52]
                     :screen-extent-range [0.02 0.35]
                     :bounds {:min [7.0 0.0 29.0] :max [9.0 5.0 31.0]}}
                    {:id :prop/foreground :composition-region :foreground-right
                     :ground-contact-screen-y-range [0.58 0.92]
                     :screen-extent-range [0.02 0.35]
                     :bounds {:min [3.5 0.0 2.7] :max [4.2 1.0 3.3]}}]
        result (composition/compose
                {:resolved-camera resolved :subject-bounds subject :candidates candidates
                 :policy {:required-composition-region-counts
                          {:building 1 :foreground-right 1}}})
        ys (get-in result [:evidence :selected-ground-contact-screen-y])]
    (is (<= 0.44 (get ys :building/background) 0.52))
    (is (<= 0.58 (get ys :prop/foreground) 0.92))
    (is (= [0.44 0.52] (get-in result [:evidence
                                       :selected-ground-contact-screen-y-ranges
                                       :building/background])))
    (is (= [0.58 0.92] (get-in result [:evidence
                                       :selected-ground-contact-screen-y-ranges
                                       :prop/foreground])))))

(deftest required-region-counts-reserve-three-per-side-before-fill
  (let [candidate (fn [side i priority]
                    (let [left? (= side :left)
                          x (+ (if left? -3.3 2.0) (* i 0.38))]
                      {:id (keyword (str "prop." (name side)) (str i))
                       :priority priority
                       :composition-region (if left? :foreground-left :foreground-right)
                       :screen-side side
                       :bounds {:min [x 0.0 0.0] :max [(+ x 0.25) 0.55 0.25]}}))
        lefts (mapv #(candidate :left % (- 100 %)) (range 5))
        rights (mapv #(candidate :right % (- 10 %)) (range 3))
        result (composition/compose
                {:resolved-camera resolved :subject-bounds subject
                 :candidates (into lefts rights)
                 :policy {:maximum-selected 6
                          :required-composition-region-counts
                          {:foreground-left 3 :foreground-right 3}}})]
    (is (= {:foreground-left 3 :foreground-right 3}
           (get-in result [:evidence :selected-region-counts])))
    (is (= {:foreground-left 3 :foreground-right 3}
           (get-in result [:evidence :required-composition-region-counts])))
    (is (empty? (get-in result [:evidence :composition-region-shortages])))
    (is (= 6 (count (:placements result))))))

(deftest region-quota-shortage-fails-closed-with-exact-count
  (let [failure (try
                  (composition/compose
                   {:resolved-camera resolved :subject-bounds subject
                    :candidates [{:id :prop/only-right
                                  :composition-region :foreground-right
                                  :bounds {:min [2.1 0.0 0.0] :max [2.4 0.5 0.2]}}]
                    :policy {:required-composition-region-counts
                             {:foreground-right 3}}})
                  nil
                  (catch #?(:clj Exception :cljs js/Error) error error))]
    (is (= {:foreground-right 2}
           (get-in (ex-data failure) [:evidence :composition-region-shortages])))))

(deftest projected-screen-extent-rejects-tiny-or-oversized-candidates
  (let [tiny {:id :prop/tiny :screen-extent-range [0.05 0.30]
              :bounds {:min [7.0 0.0 29.0] :max [7.01 0.01 29.01]}}
        oversized {:id :prop/oversized :screen-extent-range [0.001 0.05]
                   :bounds {:min [1.5 0.0 0.0] :max [3.0 1.0 0.3]}}
        failure (try
                  (composition/compose {:resolved-camera resolved :subject-bounds subject
                                        :candidates [tiny oversized]})
                  nil
                  (catch #?(:clj Exception :cljs js/Error) error error))
        evaluated (:evaluated (ex-data failure))]
    (is (every? #(some #{:screen-extent-outside-range} (:reasons %)) evaluated))
    (is (< (:screen-extent (first (filter #(= :prop/tiny (:id %)) evaluated))) 0.05))
    (is (> (:screen-extent (first (filter #(= :prop/oversized (:id %)) evaluated))) 0.05))))

(deftest render-grass-descriptor-contract-accepts-eleven-percent-extent
  (let [grass {:id :grass/right-0 :composition-region :foreground-right
               :screen-side :right :cluster-id :cluster/right-0
               :cluster-role :vegetation
               :ground-contact-screen-y-range [0.58 0.90]
               :screen-extent-range [0.025 0.11]
               ;; Final world AABB after Render descriptor offset and world-size scale.
               :bounds {:min [2.1 0.0 -0.2] :max [2.6 0.6 0.3]}}
        result (composition/compose
                {:resolved-camera resolved :subject-bounds subject :candidates [grass]
                 :policy {:required-composition-region-counts {:foreground-right 1}}})
        placement (first (:placements result))
        extent (:screen-extent placement)
        contact-y (get-in placement [:ground-contact :projection :screen 1])]
    ;; Both values are calculated from the final world AABB by compose/project-aabb,
    ;; not copied from descriptor intent.
    (is (<= 0.025 extent 0.11))
    (is (<= 0.58 contact-y 0.90))
    (is (= :cluster/right-0 (get-in placement [:candidate :cluster-id])))
    (is (= :vegetation (get-in placement [:candidate :cluster-role])))
    (is (= {:foreground-right 1}
           (get-in result [:evidence :selected-region-counts])))))

(deftest camera-ground-facing-comes-from-resolved-orbit-not-hardcoded-z
  (let [front (composition/camera-ground-facing resolved)
        rotated-camera (camera/resolve-camera {:subject-bounds subject
                                               :orbit :three-quarter-right})
        rotated (composition/camera-ground-facing rotated-camera)
        [_ qy _ qw] (:rotation rotated)
        rotated-positive-z [(* 2.0 qy qw) 0.0 (- 1.0 (* 2.0 qy qy))]]
    (is (< (Math/abs (double (first (:direction front)))) 1.0e-9))
    (is (< (Math/abs (double (+ 1.0 (nth (:direction front) 2)))) 1.0e-9))
    (is (> (first (:direction rotated)) 0.50))
    (is (< (nth (:direction rotated) 2) -0.75))
    (is (not= (:rotation front) (:rotation rotated)))
    (is (every? #(< (Math/abs (double %)) 1.0e-9)
                (map - rotated-positive-z (:direction rotated))))
    (is (= rotated (get-in (composition/compose
                            {:resolved-camera rotated-camera :subject-bounds subject
                             :candidates [{:id :prop/right
                                           :bounds {:min [2.1 0.0 -0.2]
                                                    :max [2.6 0.6 0.3]}}]})
                           [:camera-ground-facing])))))
