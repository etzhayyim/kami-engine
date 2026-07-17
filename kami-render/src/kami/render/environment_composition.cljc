(ns kami.render.environment-composition
  "Camera-safe environment composition using production character camera evidence."
  (:require [kami.render.character-camera :as character-camera]))

(def contract :kotoba.render/camera-safe-environment-composition-v1)
(def family-boundary {:families #{:stylized :photoreal}
                      :implemented-families #{:stylized} :same-api? true})

(def ^:private default-policy
  {:safe-screen-bounds {:min [0.04 0.04] :max [0.96 0.96]}
   :ground-contact-screen-y-range [0.38 0.92]
   :subject-padding 0.035 :ground-contact-tolerance 0.025
   :maximum-selected 8 :required-composition-regions #{}})

(defn- radians [degrees] (* degrees (/ #?(:clj Math/PI :cljs js/Math.PI) 180.0)))
(defn- v- [a b] (mapv - a b))
(defn- dot [a b] (reduce + (map * a b)))
(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
(defn- normalize [v]
  (let [n (#?(:clj Math/sqrt :cljs js/Math.sqrt) (dot v v))]
    (when (> n 1.0e-9) (mapv #(/ % n) v))))

(defn- corners [{:keys [min max]}]
  (for [x [(nth min 0) (nth max 0)]
        y [(nth min 1) (nth max 1)]
        z [(nth min 2) (nth max 2)]] [x y z]))

(defn- camera-basis [{:keys [position look-at up]}]
  (let [forward (normalize (v- look-at position))
        right (normalize (cross up forward))
        corrected-up (when (and forward right) (normalize (cross forward right)))]
    (when-not (and forward right corrected-up)
      (throw (ex-info "Environment projection requires a non-degenerate camera basis"
                      {:position position :look-at look-at :up up})))
    {:forward forward :right right :up corrected-up}))

(defn project-point
  "Project a world point to normalized screen coordinates using a resolved camera map."
  [camera point]
  (let [{:keys [forward right up]} (camera-basis camera)
        relative (v- point (:position camera))
        depth (dot relative forward)
        aspect (/ (double (get-in camera [:viewport :width]))
                  (double (get-in camera [:viewport :height])))
        tan-half (#?(:clj Math/tan :cljs js/Math.tan)
                  (* 0.5 (radians (:vertical-fov-deg camera))))]
    (when (> depth (:near camera))
      (let [ndc-x (/ (dot relative right) (* depth tan-half aspect))
            ndc-y (/ (dot relative up) (* depth tan-half))]
        {:screen [(+ 0.5 (* 0.5 ndc-x)) (- 0.5 (* 0.5 ndc-y))]
         :depth depth :in-front? true}))))

(defn project-aabb
  "Project all eight AABB corners; returns nil if any corner is behind the near plane."
  [camera bounds]
  (let [projected (mapv #(project-point camera %) (corners bounds))]
    (when (every? some? projected)
      (let [points (map :screen projected)
            mn (reduce #(mapv min %1 %2) [##Inf ##Inf] points)
            mx (reduce #(mapv max %1 %2) [##-Inf ##-Inf] points)]
        {:min mn :max mx :corners projected
         :depth-range [(apply min (map :depth projected))
                       (apply max (map :depth projected))]}))))

(defn- pad-rect [{:keys [min max]} padding]
  {:min (mapv #(- % padding) min) :max (mapv #(+ % padding) max)})

(defn- within? [{mn :min mx :max} {safe-min :min safe-max :max}]
  (every? true? (concat (map <= safe-min mn) (map <= mx safe-max))))

(defn- intersects? [{a-min :min a-max :max} {b-min :min b-max :max}]
  (every? true? (map (fn [amn amx bmn bmx] (and (<= amn bmx) (<= bmn amx)))
                     a-min a-max b-min b-max)))

(defn- ground-contact [{:keys [min max]}]
  [(* 0.5 (+ (nth min 0) (nth max 0))) (nth min 1)
   (* 0.5 (+ (nth min 2) (nth max 2)))])

(defn compose
  "Select deterministic environment candidates that are safe in a production camera.

  Candidates are `{:id ... :bounds {:min [...] :max [...]} :priority number}`.
  The returned placements retain world semantics and never alter skinned selection."
  [{:keys [family resolved-camera subject-bounds candidates ground-y policy]
    :or {family :stylized candidates [] ground-y 0.0 policy {}}}]
  (when-not (contains? (:implemented-families family-boundary) family)
    (throw (ex-info "Environment composition family is not implemented" {:family family})))
  (when-not (= character-camera/contract (:contract resolved-camera))
    (throw (ex-info "Environment composition requires a resolved production camera"
                    {:expected character-camera/contract :actual (:contract resolved-camera)})))
  (when-not (= :preserve-all (get-in resolved-camera [:render-selection :world :mode]))
    (throw (ex-info "Environment composition refuses a camera that removes world context"
                    {:render-selection (:render-selection resolved-camera)})))
  (let [policy (merge default-policy policy)
        camera (:camera resolved-camera)
        subject-screen (some-> (project-aabb camera subject-bounds)
                               (pad-rect (:subject-padding policy)))
        _ (when-not subject-screen
            (throw (ex-info "Environment composition cannot project subject bounds"
                            {:subject-bounds subject-bounds})))
        ordered (sort-by (juxt #( - (or (:priority %) 0)) #(pr-str (:id %))) candidates)
        evaluated
        (mapv (fn [{:keys [id bounds] :as candidate}]
                (let [screen (project-aabb camera bounds)
                      contact (ground-contact bounds)
                      contact-screen (project-point camera contact)
                      contact-screen-y (some-> contact-screen :screen second)
                      ground-band (:ground-contact-screen-y-range policy)
                      ground-error (#?(:clj Math/abs :cljs js/Math.abs) (- (second contact) ground-y))
                      reasons (cond-> []
                                (nil? screen) (conj :behind-camera)
                                (and screen (not (within? screen (:safe-screen-bounds policy))))
                                (conj :outside-safe-screen)
                                (and screen (intersects? screen subject-screen))
                                (conj :subject-exclusion)
                                (> ground-error (:ground-contact-tolerance policy))
                                (conj :not-grounded)
                                (or (nil? contact-screen)
                                    (and contact-screen
                                         (not (within? {:min (:screen contact-screen)
                                                       :max (:screen contact-screen)}
                                                      (:safe-screen-bounds policy)))))
                                (conj :ground-contact-not-visible))
                      reasons (cond-> reasons
                                (and contact-screen-y
                                     (not (<= (first ground-band) contact-screen-y
                                              (second ground-band))))
                                (conj :ground-contact-outside-visible-ground-band))]
                  {:id id :candidate candidate :composition-region (:composition-region candidate)
                   :screen-bounds screen
                   :ground-contact {:world contact :projection contact-screen :error ground-error}
                   :accepted? (empty? reasons) :reasons reasons}))
              ordered)
        eligible (vec (filter :accepted? evaluated))
        required-regions (vec (sort-by pr-str (:required-composition-regions policy)))
        reserved (vec (keep (fn [region]
                              (first (filter #(= region (:composition-region %)) eligible)))
                            required-regions))
        reserved-ids (set (map :id reserved))
        fillers (remove #(contains? reserved-ids (:id %)) eligible)
        selected (vec (take (:maximum-selected policy) (concat reserved fillers)))
        region-counts (frequencies (keep :composition-region selected))
        missing-regions (vec (remove #(pos? (get region-counts % 0)) required-regions))
        evidence {:valid? (and (boolean (seq selected)) (empty? missing-regions))
                  :candidate-count (count candidates)
                  :selected-count (count selected)
                  :rejected-count (- (count candidates) (count selected))
                  :safe-screen-bounds (:safe-screen-bounds policy)
                  :ground-contact-screen-y-range (:ground-contact-screen-y-range policy)
                  :selected-ground-contact-screen-y
                  (into {} (map (fn [entry]
                                  [(:id entry) (get-in entry [:ground-contact :projection :screen 1])])
                                selected))
                  :required-composition-regions required-regions
                  :selected-region-counts region-counts
                  :missing-composition-regions missing-regions
                  :subject-exclusion subject-screen :ground-y ground-y
                  :deterministic-order (mapv :id evaluated)
                  :world-context-retained? true}]
    (when-not (:valid? evidence)
      (throw (ex-info "Environment composition failed closed: unsafe or missing required regions"
                      {:contract contract :evidence evidence :evaluated evaluated})))
    {:contract contract :family family :camera-contract character-camera/contract
     :placements (mapv #(select-keys % [:id :candidate :screen-bounds :ground-contact]) selected)
     :rejected (mapv #(select-keys % [:id :reasons :screen-bounds :ground-contact])
                     (remove :accepted? evaluated))
     :render-selection {:world {:mode :preserve-all :removed? false}
                        :skinned (get-in resolved-camera [:render-selection :skinned])}
     :evidence evidence}))
