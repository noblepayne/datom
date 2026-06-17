(ns datom.graph
  "Document relationship graph — links, neighbors, dependents."
  (:require [datalevin.core :as dl]
            [datom.store :as store]))

(defn extract-links
  "Extract document links from markdown text. Returns source-id → target-id pairs."
  [text]
  (->> (re-seq #"\[([^\]]+)\]\(([^)]+\.md)\)" text)
       (map (fn [[_ label path]]
              (let [target (second (re-find #"(\d+)" path))]
                (when target
                  {:source label :target (str "doc-" target)}))))
       (remove nil?)))

(defn dependents
  "Which document ids depend on the given doc?"
  [sys id]
  (->> (dl/q '[:find ?id ?depends
               :where [?e :content/id ?id]
               [?e :content/depends ?depends]]
             @(::store/conn sys))
       (filter (fn [[_ deps]] (some #(= % id) deps)))
       (mapv first)))

(defn neighbors
  "Graph neighborhood of a document (1 hop)."
  [sys id]
  (let [doc    (store/lookup sys id)
        deps   (:content/depends doc [])
        dep-by (dependents sys id)]
    (vec (distinct (concat [id] deps dep-by)))))
