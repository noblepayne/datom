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
   Chunks long documents. Returns {:ids [...] :total N :chunks M}.
   Does NOT update search indices — call index-docs! separately."
  [sys source & {:keys [max-chars overlap] :or {max-chars 2000 overlap 200}}]
  (let [conn  (::store/conn sys)
        items (source-items source)
        ids   (atom [])]
    (doseq [item items]
      (let [body    (:content/body item)
            chunks  (chunk/split body {:max-chars max-chars :overlap overlap})
            chunks? (> (count chunks) 1)]
        (if chunks?
          (let [parent-id (:content/id item)
                children  (mapv (fn [c]
                                  (assoc item
                                         :content/id (str parent-id "-chunk-" (:index c))
                                         :content/body (:text c)
                                         :content/parent parent-id
                                         :content/chunk true
                                         :content/ts (Instant/now)))
                                (rest chunks))]
            (swap! ids into (concat [parent-id] (map :content/id children)))
            (dl/transact! conn (into [(assoc item
                                             :content/body (:text (first chunks))
                                             :content/chunk false
                                             :content/ts (Instant/now))]
                                     children)))
          (do
            (swap! ids conj (:content/id item))
            (dl/transact! conn [(assoc item
                                       :content/chunk false
                                       :content/ts (Instant/now))])))))
    {:total (count items)
     :chunks (->> @ids (filter #(re-find #"-chunk-" %)) count)
     :ids   @ids}))
