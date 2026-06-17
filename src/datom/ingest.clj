(ns datom.ingest
  "ContentSource protocol and generic ingestion."
  (:require [datom.store :as store]
            [datom.chunk :as chunk]
            [datalevin.core :as dl])
  (:import [java.time Instant]))

(defprotocol ContentSource
  (source-id [source])
  (source-type [source])
  (source-items [source]))

(defn ingest
  "Ingest items from a ContentSource into the store.
   Chunks long documents via chunk/chunk-doc. Returns {:ids [...] :total N :chunks M}.
   Does NOT update search indices — call index-docs! separately."
  [sys source & {:keys [max-chars overlap] :or {max-chars 2000 overlap 200}}]
  (let [conn (::store/conn sys)
        items (source-items source)
        ids (atom [])]
    (doseq [item items]
      (let [result (chunk/chunk-doc item :max-chars max-chars :overlap overlap)
            now (Instant/now)
            parent (assoc (:parent result) :content/chunk false :content/ts now)
            children (mapv #(assoc % :content/ts now) (:chunks result))
            all-docs (cons parent children)]
        (swap! ids into (map :content/id all-docs))
        (dl/transact! conn all-docs)))
    {:total (count items)
     :chunks (->> @ids (filter #(re-find #"-chunk-" %)) count)
     :ids @ids}))
