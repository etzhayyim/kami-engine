#!/usr/bin/env bb
;; services.clj — platform-services catalog tooling for a kami-clj game (ADR-0049).
;;
;;   bb services-lint   <game>          ; fail if logic.clj names a key not in services.edn
;;   bb services-config <game> <store>  ; project services.edn → per-store id artifacts
;;
;; The game authors logic in CLJ/EDN and names LOGICAL keys; services.edn maps
;; each key to every store's id. This script is the author-time guard that the
;; keys logic.clj references exist (and, per store, are fully mapped), plus the
;; package-time projection to the files each store consumes.
;;
;; Stores: steam | psn | gamecenter | google | switch
(ns services
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [babashka.fs :as fs]))

(def builtins
  "neutral builtin → which catalog set its first string arg (logical key) is in."
  '{achievement-unlock! :achievements
    stat-set!           :stats
    presence-set!       :rich-presence})

(def stores #{"steam" "psn" "gamecenter" "google" "switch"})

(defn game-dir [game] (str "kami-clj-play/games/" game))

(defn read-forms [path]
  (edn/read-string (str "[" (slurp path) "]")))

(defn referenced-keys
  "Logical keys passed as the first arg to each services builtin, by category."
  [forms]
  (let [acc (atom {:achievements #{} :stats #{} :rich-presence #{}})]
    (walk/postwalk
     (fn [node]
       (when (and (list? node) (symbol? (first node)))
         (when-let [cat (builtins (first node))]
           (let [arg (second node)]
             (when (string? arg) (swap! acc update cat conj arg)))))
       node)
     forms)
    @acc))

(defn declared-keys [catalog]
  {:achievements  (set (map :key (:services/achievements catalog)))
   :stats         (set (map :key (:services/stats catalog)))
   :rich-presence (set (map :key (:services/rich-presence catalog)))})

(defn load-catalog [game]
  (let [f (str (game-dir game) "/services.edn")]
    (when-not (fs/exists? f)
      (println "✗ no services.edn for game" game
               "\n  (a game that calls services builtins must declare its keys — ADR-0049)")
      (System/exit 1))
    (edn/read-string (slurp f))))

(defn lint [game]
  (let [logic-f (str (game-dir game) "/logic.clj")]
    (when-not (fs/exists? logic-f)
      (println "✗ no logic.clj for game" game) (System/exit 1))
    (let [catalog    (load-catalog game)
          declared   (declared-keys catalog)
          referenced (referenced-keys (read-forms logic-f))
          missing    (into {} (for [cat (keys declared)
                                    :let [m (set/difference (referenced cat) (declared cat))]
                                    :when (seq m)] [cat m]))
          unused     (into {} (for [cat (keys declared)
                                    :let [u (set/difference (declared cat) (referenced cat))]
                                    :when (seq u)] [cat u]))]
      (doseq [cat [:achievements :stats :rich-presence]]
        (println (format "  %-14s referenced %d / declared %d"
                        (name cat) (count (referenced cat)) (count (declared cat)))))
      (doseq [[cat u] unused]
        (println "  note: declared but unused" (name cat) "→" (str/join ", " u)))
      ;; per-store completeness: warn (not fail) if a declared id lacks a store mapping.
      (doseq [store [:steam :psn :gamecenter :google :switch]]
        (let [gaps (for [a (:services/achievements catalog) :when (nil? (get a store))] (:key a))]
          (when (seq gaps)
            (println "  warn: achievements missing" (name store) "id →" (str/join ", " gaps)))))
      (if (seq missing)
        (do (println "✗ services-lint:" game "names keys absent from services.edn:")
            (doseq [[cat m] missing] (println "   " (name cat) "→" (str/join ", " m)))
            (System/exit 2))
        (println "✓ services-lint:" game "— every referenced key is declared")))))

(defn steam-vdf [app-id depot content-root]
  (str "\"appbuild\"\n{\n"
       "  \"appid\" \"" app-id "\"\n"
       "  \"desc\"  \"kami-engine build\"\n"
       "  \"contentroot\" \"" content-root "\"\n"
       "  \"buildoutput\" \"./output\"\n"
       "  \"depots\"\n  {\n    \"" depot "\"\n    {\n"
       "      \"FileMapping\" { \"LocalPath\" \"*\" \"DepotPath\" \".\" \"recursive\" \"1\" }\n"
       "    }\n  }\n}\n"))

(defn store-id-map
  "logical key → this store's id, for achievements + stats."
  [catalog store]
  (let [k (keyword store)]
    {:achievements (into {} (for [a (:services/achievements catalog) :when (get a k)] [(:key a) (get a k)]))
     :stats        (into {} (for [s (:services/stats catalog)        :when (get s k)] [(:key s) (get s k)]))}))

(defn config [game store]
  (when-not (stores store)
    (println "✗ unknown store" store "— one of:" (str/join " " (sort stores))) (System/exit 1))
  (let [catalog (load-catalog game)
        out     (str "dist/" store "/" game)
        ids     (store-id-map catalog store)
        app-id  (get-in catalog [:services/app-id (keyword store)])]
    (fs/create-dirs out)
    ;; every store: the resolved logical→store id map the host loads into ServiceIds.
    (spit (str out "/" store "-ids.edn") (with-out-str (pp/pprint ids)))
    (spit (str out "/services-schema.edn")
          (with-out-str (pp/pprint {:store store :app-id app-id
                                    :achievements (:services/achievements catalog)
                                    :stats (:services/stats catalog)})))
    (case store
      "steam" (let [depot (get-in catalog [:services/app-id :steam])]  ; reuse appid as depot stub
                (spit (str out "/steam_appid.txt") (str app-id "\n"))
                (spit (str out "/app_build_" app-id ".vdf")
                      (steam-vdf app-id (inc depot) (str "../../../" (game-dir game)))))
      ("psn" "switch") (spit (str out "/NDA-README.txt")
                             (str store " trophy/achievement config is set via the "
                                  (if (= store "psn") "Sony Np" "Nintendo") " SDK toolset "
                                  "(NDA, out of repo). Use " store "-ids.edn for the id mapping.\n"))
      "gamecenter" (spit (str out "/gamecenter-achievements.edn")
                         (with-out-str (pp/pprint (:achievements ids))))
      "google" (spit (str out "/googleplay-ids.edn")
                     (with-out-str (pp/pprint ids))))
    (println "✓ services-config:" game "→" out (str "(store=" store ")"))))

(let [[mode game store] *command-line-args*]
  (case mode
    "config" (config (or game "survivors") (or store "steam"))
    (lint (or game "survivors"))))
