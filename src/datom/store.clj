(ns datom.store
  "Schema, connections, and document CRUD."
  (:require [datalevin.core :as dl]))

(def schema
  {:content/id         {:db/unique :db.unique/identity}
   :content/type       {:db/index true}
   :content/title      {:db/fulltext true}
   :content/body       {:db/fulltext true}
   :content/meta       {}
   :content/depends    {}
   :content/ts         {:db/index true}
   :content/parent     {}
   :content/chunk      {:db/index true}
   :content/tags       {}
   :content/importance {}})

(defn ensure-conn!
  [{::keys [db-dir]}]
  (or (try (dl/conn-from-db (dl/db db-dir)) (catch Throwable _ nil))
      (dl/create-conn db-dir schema)))

(defn store
  "Initialize the full storage stack."
  ([opts]
   (let [opts (merge {::db-dir     "/tmp/datom-db"
                      ::search-dir "/tmp/datom-search"
                      ::dimensions 384
                      ::metric     :cosine
                      ::rrf-k      60} opts)
         conn (ensure-conn! opts)
         lmdb (dl/open-kv (::search-dir opts))]
     {::conn conn ::lmdb lmdb ::opts opts}))
  ([]
   (store {})))

(defn lookup
  "Pull a document by string id."
  [sys id]
  (first
   (dl/q '[:find [(pull ?e [:content/id :content/type :content/title :content/body
                            :content/meta :content/depends :content/ts :content/parent
                            :content/chunk :content/tags :content/importance])]
           :where [?e :content/id ?id]
           :in $ ?id
           :args {:id id}]
         @(::conn sys) id)))

(defn transact!
  "Transact datoms into the store."
  [sys datoms]
  (dl/transact! (::conn sys) datoms))

(defn close!
  "Close LMDB and Datalog connections."
  [sys]
  (when-let [lmdb (::lmdb sys)]
    (try (.close lmdb) (catch Throwable _)))
  (when-let [conn (::conn sys)]
    (try (dl/close conn) (catch Throwable _))))
