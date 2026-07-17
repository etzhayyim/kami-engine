;; wit_test.clj — codegen + consistency gate for the kami:engine interface.
;;
;; Reads wit/kami-interface.edn (the ONE source), generates WIT, and asserts the
;; generated WIT is ABI-equivalent to the committed wit/kami-game/world.wit.
;;
;;   bb scripts/wit_test.clj           # check (throws on drift)
;;   bb scripts/wit_test.clj --gen     # print the regenerated WIT
(require '[clojure.set :as set]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

;; wit/kami-interface.edn was datomic/datascript-ized by edn-datomize.cljs
;; (wrap-map, ns="wit.kami-interface"): top level is now `[{:db/id -1
;; :wit.kami-interface/package ... :wit.kami-interface/interfaces "..." ...}]`
;; tx-data, with the nested :interfaces/:exports maps pr-str'd into blob string
;; attrs. This reconstitutes the original raw {:package :world :interfaces
;; :exports} map so every reader below keeps working unchanged.
(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch Exception _ v))
    v))

(defn- tx-data? [content]
  (and (vector? content) (seq content) (map? (first content)) (contains? (first content) :db/id)))

(defn- reconstitute-entity [ns-name tx-data]
  (into {} (map (fn [[k v]]
                  [(if (= ns-name (namespace k)) (keyword (name k)) k)
                   (unblob v)]))
        (dissoc (first tx-data) :db/id)))

(defn read-idl [f]
  (let [content (edn/read-string (slurp f))]
    (if (tx-data? content)
      (reconstitute-entity "wit.kami-interface" content)
      content)))

(def idl   (read-idl "wit/kami-interface.edn"))
(def world (slurp "wit/kami-game/world.wit"))

;; ── semantic type → WASM ABI (the lowering the host/guest agree on) ──────────────────────
(defn wit-type [t] (case t (:eid :i64) "s64" (:f32 :i32) "s32"))

(defn- param-strs [[nm t]]
  (if (= t :str)
    [(str (name nm) "-ptr: s32") (str (name nm) "-len: s32")]   ;; a string lowers to (ptr, len)
    [(str (name nm) ": " (wit-type t))]))

(defn wit-func [fname {:keys [params ret]}]
  (str (name fname) ": func(" (str/join ", " (mapcat param-strs params)) ")"
       (when (and ret (not= ret :unit)) (str " -> " (wit-type ret))) ";"))

(defn gen-wit
  "Regenerate the full world.wit text from the EDN IDL."
  [idl]
  (str "package " (:package idl) ";\n\n"
       (str/join "\n\n"
         (for [[iname ispec] (:interfaces idl)]
           (str "// " (:doc ispec) "\ninterface " (name iname) " {\n"
                (str/join "\n" (for [[fn spec] (:funcs ispec)] (str "    " (wit-func fn spec))))
                "\n}")))
       "\n\nworld " (:world idl) " {\n"
       (str/join "\n" (for [[iname _] (:interfaces idl)]
                        (str "    import " (str/replace (:package idl) "@" (str "/" (name iname) "@")) ";")))
       "\n    export memory;\n"
       (str/join "\n" (for [[en spec] (:exports idl)] (str "    export " (wit-func en spec))))
       "\n}\n"))

;; ── canonical ABI signature: "iface.fn(types…)ret" — name/whitespace independent ─────────
(defn gen-canon [idl]
  (set (for [[iname ispec] (:interfaces idl), [fname fspec] (:funcs ispec)]
         (str (name iname) "." (name fname) "("
              (str/join "," (mapcat (fn [[_ t]] (if (= t :str) ["s32" "s32"] [(wit-type t)])) (:params fspec)))
              ")" (when (and (:ret fspec) (not= (:ret fspec) :unit)) (wit-type (:ret fspec)))))))

(defn wit-canon [wit]
  (let [nc (str/replace wit #"//[^\n]*" "")]
    (set (mapcat (fn [[_ iname body]]
                   (map (fn [[_ fname params ret]]
                          (str iname "." fname "("
                               (str/join "," (map second (re-seq #":\s*(s\d+)" params)))
                               ")" (when ret (second (re-find #"(s\d+)" ret)))))
                        (re-seq #"([\w-]+)\s*:\s*func\s*\(([^)]*)\)\s*(->\s*s\d+)?\s*;" body)))
                 (re-seq #"interface\s+([\w-]+)\s*\{([^}]*)\}" nc)))))

;; ── run ──────────────────────────────────────────────────────────────────────────────────
(if (some #{"--gen"} *command-line-args*)
  (println (gen-wit idl))
  (let [g (gen-canon idl), w (wit-canon world)
        edn-only (sort (set/difference g w))
        wit-only (sort (set/difference w g))]
    (println "── kami:engine interface — single-source consistency ──")
    (println (format "  EDN IDL: %d host functions across %d interfaces" (count g) (count (:interfaces idl))))
    (println (format "  WIT:     %d" (count w)))
    (when (seq edn-only) (println "  WIT drift — only in EDN:" (vec edn-only)))
    (when (seq wit-only) (println "  WIT drift — only in WIT:" (vec wit-only)))
    (if (= g w)
      (println "  ✓ EDN IDL and world.wit agree.")
      (throw (ex-info "kami:engine interface DRIFT"
                      {:wit-only wit-only :edn-only edn-only})))))
