(ns datom.chunk
  "Paragraph-level document chunking with overlap."
  (:require [clojure.string :as str]))

(defn split
  "Split text into chunks by paragraph boundaries.
   Options: :max-chars (default 2000), :overlap (default 200)"
  ([text] (split text {}))
  ([text {:keys [max-chars overlap] :or {max-chars 2000 overlap 200}}]
   (assert (pos? max-chars) "max-chars must be positive")
   (let [overlap (min overlap (dec max-chars))
         paragraphs (vec (str/split text #"\n\n+"))
         chunks (loop [remaining paragraphs
                       current []
                       current-len 0
                       results []]
                  (if-not remaining
                    (if (seq current)
                      (conj results (str/join "\n\n" current))
                      results)
                    (let [para (first remaining)
                          para-len (count para)
                          new-len (+ current-len para-len (if (seq current) 2 0))]
                      (if (<= new-len max-chars)
                        (recur (next remaining)
                               (conj current para)
                               new-len
                               results)
                        (let [chunk (str/join "\n\n" current)
                              overlap-paragraphs
                              (when (and (pos? overlap) (seq current))
                                (loop [cs (reverse current)
                                       acc []
                                       len 0]
                                  (if (or (empty? cs) (> len overlap))
                                    (vec (reverse acc))
                                    (recur (rest cs)
                                           (conj acc (first cs))
                                           (+ len (count (first cs)) 2)))))]
                          (recur (next remaining)
                                 (vec (concat overlap-paragraphs [para]))
                                 (+ (reduce + (map count overlap-paragraphs))
                                    para-len)
                                 (if (seq chunk)
                                   (conj results chunk)
                                   results)))))))]
     (mapv (fn [i text] {:index i :text text})
           (range) chunks))))

(defn chunk-doc
  "Split a document into parent + child chunks.
   Returns the original doc with :chunks added, plus child docs."
  [doc & {:keys [max-chars overlap] :or {max-chars 2000 overlap 200}}]
  (let [body (:content/body doc)
        chunks (split body {:max-chars max-chars :overlap overlap})]
    (if (<= (count chunks) 1)
      {:parent doc :chunks []}
      (let [parent (assoc doc
                          :content/body (first chunks)
                          :content/chunk false)
            children (mapv (fn [c]
                             (assoc doc
                                    :content/id (str (:content/id doc) "-chunk-" (:index c))
                                    :content/body (:text c)
                                    :content/parent (:content/id doc)
                                    :content/chunk true
                                    :content/ts (java.time.Instant/now)))
                           (rest chunks))]
        {:parent parent :chunks children}))))
