(ns datom.query
  "Search, answer, context — composed query primitives."
  (:require [datom.store :as store]
            [datom.index :as index]
            [datom.graph :as graph]
            [datalevin.core :as dl]
            [clojure.string :as str]))

(defn- filter-by-author
  [sys ranked author]
  (if-not author
    ranked
    (let [auth-ids (set (->> (dl/q '[:find ?id ?meta
                                     :where [?e :content/id ?id]
                                     [?e :content/meta ?meta]]
                                   @(::store/conn sys))
                             (filter (fn [[_ m]]
                                       (let [authors (or (:authors m) (:datom.ingest.luds/authors m) [])]
                                         (some #(= % author) authors))))
                             (mapv first)))]
      (filter #(auth-ids (:ref %)) ranked))))

(defn search
  "Hybrid fulltext + vector search with RRF fusion.
   Options: :top, :author, :expand, :raw"
  [sys query {:keys [top author expand raw] :or {top 5 expand 0}}]
  (let [ranked (index/rrf-search sys query top)
        ranked (filter-by-author sys ranked author)
        top-scored (take top ranked)
        top-ids (if (pos? expand)
                  (distinct (mapcat #(graph/neighbors sys (:ref %)) top-scored))
                  (mapv :ref top-scored))]
    (mapv (fn [id]
            (let [doc (store/lookup sys id)]
              (cond-> {:id id
                       :title (:content/title doc)
                       :meta (:content/meta doc)}
                raw (assoc :ft-rank (some #(when (= (:ref %) id) (:ft-rank %)) top-scored)
                           :sem-rank (some #(when (= (:ref %) id) (:sem-rank %)) top-scored)
                           :rrf (some #(when (= (:ref %) id) (:rrf %)) top-scored)))))
          top-ids)))

(defn answer
  "Search and return a human-readable string."
  [sys query & {:as opts}]
  (let [results (search sys query opts)]
    (str query "\n\n"
         (str/join "\n"
                   (map (fn [{:keys [id title meta]}]
                          (let [display (or (:title meta) title id)]
                            (str "• " id ": " display)))
                        results)))))
