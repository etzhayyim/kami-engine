(ns kami.render.authority
  "Render surface authority.

  The EDN artifact describes the stable render-IR defaults and built-in pipeline
  ids. Rust/wgpu remains the executor for :kami-render/scene-pipeline entries;
  policy above that adapter boundary lives here."
  #?(:clj (:require [clojure.edn :as edn]
                    [clojure.java.io :as io])))

(def authority-edn
  {:authority/id :kami.render/authority
   :authority/version 1
   :authority/surface :render
   :render/default-clear [0.94 0.917 0.839 1.0]
   :render/default-pass {:pass/id :main
                         :pass/target :swapchain}
   :render/pipelines
   [{:pipeline/id :pbr
     :pipeline/backend :kami-render/scene-pipeline
     :pipeline/role :material-lit
     :pipeline/adapter "kami_render::scene_pipelines::pbr"}
    {:pipeline/id :sky
     :pipeline/backend :kami-render/scene-pipeline
     :pipeline/role :environment
     :pipeline/adapter "kami_render::scene_pipelines::sky"}
    {:pipeline/id :terrain
     :pipeline/backend :kami-render/scene-pipeline
     :pipeline/role :landscape
     :pipeline/adapter "kami_render::scene_pipelines::terrain"}
    {:pipeline/id :vegetation
     :pipeline/backend :kami-render/scene-pipeline
     :pipeline/role :instanced-foliage
     :pipeline/adapter "kami_render::scene_pipelines::vegetation"}
    {:pipeline/id :character
     :pipeline/backend :kami-render/scene-pipeline
     :pipeline/role :skinned-character
     :pipeline/adapter "kami_render::scene_pipelines::character"}
    {:pipeline/id :water
     :pipeline/backend :kami-render/scene-pipeline
     :pipeline/role :surface-water
     :pipeline/adapter "kami_render::scene_pipelines::water"}
    {:pipeline/id :voxel
     :pipeline/backend :kami-render/scene-pipeline
     :pipeline/role :voxel-scene
     :pipeline/adapter "kami_render::scene_pipelines::voxel"}
    {:pipeline/id :particle
     :pipeline/backend :kami-render/scene-pipeline
     :pipeline/role :sprite-particle
     :pipeline/adapter "kami_render::scene_pipelines::particle"}
    {:pipeline/id :atlas
     :pipeline/backend :kami-render/scene-pipeline
     :pipeline/role :atlas-sprite
     :pipeline/adapter "kami_render::scene_pipelines::atlas"}]})

;; kami/render/authority.edn was datomic/datascript-ized by edn-datomize.bb
;; (wrap-map-keep-ns, ns="kami.render.authority"): top level is now
;; `[{:db/id -1 :authority/id ... :render/pipelines "..." ...}]` tx-data.
;; Every key here was already idiomatically namespaced (:authority/*,
;; :render/*) so keep-ns left them unchanged; only :render/default-clear,
;; :render/default-pass and :render/pipelines (non-scalar values) got
;; pr-str'd into blob string attrs. This reconstitutes the original raw map
;; so every reader below (and contract_test.cljc's direct equality check)
;; keeps working unchanged.
#?(:clj
   (defn- unblob [v]
     (if (string? v)
       (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
            (catch Exception _ v))
       v)))

#?(:clj
   (defn- tx-data? [content]
     (and (vector? content) (seq content) (map? (first content)) (contains? (first content) :db/id))))

#?(:clj
   (defn- reconstitute-entity [ns-name tx-data]
     (into {} (map (fn [[k v]]
                     [(if (= ns-name (namespace k)) (keyword (name k)) k)
                      (unblob v)]))
           (dissoc (first tx-data) :db/id))))

#?(:clj
   (defn- read-resource []
     (some-> "kami/render/authority.edn"
             io/resource
             slurp
             edn/read-string
             (as-> content
                   (if (tx-data? content)
                     (reconstitute-entity "kami.render.authority" content)
                     content)))))

(def authority
  #?(:clj (or (read-resource) authority-edn)
     :cljs authority-edn))

(def default-clear (:render/default-clear authority))
(def default-pass (:render/default-pass authority))

(def builtin-pipelines
  (->> (:render/pipelines authority)
       (filter #(= :kami-render/scene-pipeline (:pipeline/backend %)))
       (map :pipeline/id)
       set))

(defn builtin?
  "True when `pipeline` names a built-in Rust/wgpu scene-pipeline adapter."
  [pipeline]
  (contains? builtin-pipelines pipeline))

(defn pipeline
  "Return the authority entry for a built-in pipeline id, or nil."
  [pipeline-id]
  (some #(when (= pipeline-id (:pipeline/id %)) %)
        (:render/pipelines authority)))

(defn validate
  "Validate the render authority artifact. Returns true or throws ex-info."
  ([] (validate authority))
  ([a]
   (let [ids (map :pipeline/id (:render/pipelines a))
         missing (remove a [:authority/id :authority/version :authority/surface
                            :render/default-clear :render/default-pass :render/pipelines])]
     (when (seq missing)
       (throw (ex-info "render authority missing keys" {:missing (vec missing)})))
     (when-not (= :render (:authority/surface a))
       (throw (ex-info "render authority has wrong surface" {:surface (:authority/surface a)})))
     (when-not (= 4 (count (:render/default-clear a)))
       (throw (ex-info "render authority default clear must be rgba4"
                       {:clear (:render/default-clear a)})))
     (when-not (= (count ids) (count (set ids)))
       (throw (ex-info "render authority has duplicate pipeline ids" {:ids (vec ids)})))
     (when-not ((set ids) :pbr)
       (throw (ex-info "render authority must declare :pbr fallback" {:ids (vec ids)})))
     (when-not (= {:pass/id :main :pass/target :swapchain} (:render/default-pass a))
       (throw (ex-info "render authority default pass changed incompatibly"
                       {:pass (:render/default-pass a)})))
     true)))
