(ns datom.index
  "Fulltext + vector indexing with RRF fusion."
  (:require [datalevin.core :as dl]
            [datom.store :as store]))

(defn init-search!
  "Open persisted search indices and load embedding model.
   Call once at server startup (or before first search).
   Does NOT re-index documents — use index! for that."
  [sys]
  (let [lmdb (::store/lmdb sys)
        opts (::store/opts sys)]
    (assoc sys
           ::search-engine (dl/new-search-engine lmdb)
           ::vec-index     (dl/new-vector-index lmdb
                             {:dimensions      (::store/dimensions opts)
                              :metric-type     (::store/metric opts)
                              :connectivity    16
                              :expansion-add   128
                              :expansion-search 64})
           ::emb-provider  (dl/new-embedding-provider
                             {:provider :default
                              :dir      (::store/db-dir opts)}))))

(defn index-docs!
  "Add specific documents to fulltext + vector indices. Incremental.
   Requires init-search! to have been called on sys first."
  [sys ids]
  (let [se   (::search-engine sys)
        vi   (::vec-index sys)
        emb  (::emb-provider sys)
        _    (assert se "call init-search! before index-docs!")]
    (doseq [id ids]
      (when-let [doc (store/lookup sys id)]
        (let [title (:content/title doc)
              body  (:content/body doc)]
          (dl/add-doc se id body false)
          (dl/add-vec vi id (dl/embed-text emb (str title "\n\n" (subs body 0 (min (count body) 2000))))))))
    sys))

(defn index!
  "Rebuild fulltext + vector indices from all documents.
   Calls init-search! internally; safe for one-shot CLI use."
  [sys]
  (let [sys  (init-search! sys)
        conn (::store/conn sys)
        se   (::search-engine sys)
        vi   (::vec-index sys)
        emb  (::emb-provider sys)
        docs (dl/q '[:find ?id ?title ?body
                      :where [?e :content/id ?id]
                             [?e :content/title ?title]
                             [?e :content/body ?body]]
                    @conn)]
    (doseq [[id _ body] docs]
      (dl/add-doc se id body false))
    (doseq [[id title body] docs]
      (dl/add-vec vi id (dl/embed-text emb (str title "\n\n" (subs body 0 (min (count body) 2000))))))
    sys))

(defn rrf-search
  "Hybrid fulltext + vector search with Reciprocal Rank Fusion.
   Returns ranked [{:ref :rrf :ft-rank :sem-rank}]
   Requires init-search! to have been called on sys first."
  [sys query top]
  (let [se  (::search-engine sys)
        vi  (::vec-index sys)
        emb (::emb-provider sys)
        _   (assert se "call init-search! before rrf-search")
        k   (::store/rrf-k (::store/opts sys))

        ft       (dl/search se query {:display :refs+scores :top (* top 3)})
        ft-ranks (into {} (map-indexed (fn [i [ref _]] [ref (inc i)]) ft))

        qvec      (dl/embed-text emb query)
        sem       (dl/search-vec vi qvec {:top (* top 3) :display :refs+dists})
        sem-ranks (into {} (map-indexed (fn [i [ref _]] [ref (inc i)]) sem))

        all-refs (distinct (concat (keys ft-ranks) (keys sem-ranks)))]
    (->> all-refs
         (map (fn [ref]
                 (let [fr (get ft-ranks ref (+ k 1))
                       sr (get sem-ranks ref (+ k 1))]
                   {:ref      ref
                    :rrf      (+ (/ 1.0 (+ k fr)) (/ 1.0 (+ k sr)))
                    :ft-rank  (get ft-ranks ref)
                    :sem-rank (get sem-ranks ref)})))
         (sort-by :rrf >))))