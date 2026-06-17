(ns datom.core
  "datom — composable agent memory. Orchestrator module."
  (:require [datom.store :as store]
            [datom.index :as index]
            [datom.graph :as graph]
            [datom.query :as query]
            [datom.ingest :as ingest]
            [datalevin.core :as dl]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; System
;; ---------------------------------------------------------------------------

(defn store-init
  "Initialize the full storage stack."
  ([] (store/store))
  ([opts] (store/store opts)))

;; ---------------------------------------------------------------------------
;; Ingest
;; ---------------------------------------------------------------------------

(defn ingest
  "Ingest items from a ContentSource. Chunks long docs, builds indices.
   Assumes init-search! has been called on sys before this."
  [sys source & {:keys [max-chars overlap] :or {max-chars 2000 overlap 200}}]
  (let [opts (cond-> {} max-chars (assoc :max-chars max-chars) overlap (assoc :overlap overlap))
        summary (ingest/ingest sys source opts)]
    (index/index-docs! sys (:ids summary))
    sys))

;; ---------------------------------------------------------------------------
;; Search
;; ---------------------------------------------------------------------------

(defn search
  "Hybrid fulltext + vector search with RRF fusion."
  [sys query & {:as opts}]
  (query/search sys query opts))

(defn answer
  "Search and return a human-readable string."
  [sys query & {:as opts}]
  (query/answer sys query opts))

;; ---------------------------------------------------------------------------
;; Context
;; ---------------------------------------------------------------------------

(defn context
  "Search + graph expansion. Returns results with neighbor metadata."
  [sys query & {:keys [top] :or {top 5}}]
  (let [results (query/search sys query {:top top :raw true})
        neighbors (into {} (map (fn [r] [(:id r) (graph/neighbors sys (:id r))]) results))]
    {:results results
     :neighbors neighbors}))

;; ---------------------------------------------------------------------------
;; Graph
;; ---------------------------------------------------------------------------

(defn graph-expand
  "Expand a node to show its full 1-hop neighborhood."
  [sys id]
  (let [neighbors (graph/neighbors sys id)
        dependents (graph/dependents sys id)]
    {:id id
     :neighbors neighbors
     :dependents dependents}))

;; ---------------------------------------------------------------------------
;; Lookup
;; ---------------------------------------------------------------------------

(defn lookup
  "Pull a document by string id."
  [sys id]
  (store/lookup sys id))

;; ---------------------------------------------------------------------------
;; Stats
;; ---------------------------------------------------------------------------

(defn stats
  "System statistics."
  [sys]
  (let [conn (::store/conn sys)
        total (or (ffirst (dl/q '[:find (count ?e) :where [?e :content/body]] @conn)) 0)
        chunks (or (ffirst (dl/q '[:find (count ?e) :where [?e :content/chunk true]] @conn)) 0)
        types (dl/q '[:find ?type (count ?e) :where [?e :content/type ?type]] @conn)]
    {:docs (- total chunks)
     :chunks chunks
     :total total
     :sources (into {} types)}))

;; ---------------------------------------------------------------------------
;; Compact (placeholder — memory tiers)
;; ---------------------------------------------------------------------------

(defn compact
  "Consolidate low-importance chunks. Placeholder for Phase 2."
  [_]
  {:compacted 0 :kept 0 :note "Not yet implemented"})

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn- parse-args [args]
  (loop [args (seq args) opts {}]
    (if-not args opts
            (let [arg (first args)
                  [k v] (cond
                          (= arg "--author") [:author (second args)]
                          (= arg "--top") [:top (parse-long (second args))]
                          (= arg "--expand") [:expand (parse-long (second args))]
                          (= arg "--source") [:source (second args)]
                          :else [:query arg])]
              (recur (nnext args) (assoc opts k v))))))

(defn -main [& args]
  (if (empty? args)
    (println "Usage: datom <search query> [--author name] [--top n] [--expand n] [--source type]")
    (let [parsed (parse-args args)
          sys (-> (store-init) index/index!)
          query (str/join " " (:query parsed))
          opts (cond-> {}
                 (:author parsed) (assoc :author (:author parsed))
                 (:top parsed) (assoc :top (:top parsed))
                 (:expand parsed) (assoc :expand (:expand parsed)))]
      (println (answer sys query opts)))))
