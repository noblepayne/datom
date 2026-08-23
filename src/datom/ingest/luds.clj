(ns datom.ingest.luds
  "LUDS markdown adapter — reference implementation of ContentSource."
  (:require [datom.ingest :as ingest]
            [clojure.string :as str]))

(defn- parse-luds-file
  "Parse a LUDS markdown file into a content map."
  [^java.io.File f]
  (let [content (slurp f)
        name    (.getName f)
        lud-num (when-let [[_ n] (re-find #"(\d+)" name)]
                  (Long/parseLong n))
        title   (str/replace (first (str/split-lines content)) #"^[=#\s]+" "")
        authors (mapv second (re-seq #"`author:\s*(\S+?)`" content))
        depends (mapv #(str "lud-" (Long/parseLong %))
                      (map second (re-seq #"\[LUD-(\d+)\]\(" content)))]
    {:content/id    (if lud-num (str "lud-" lud-num) (str "doc-" (str/lower-case title)))
     :content/type  "doc"
     :content/title title
     :content/body  content
     :content/depends depends
     :content/meta  (cond-> {}
                      lud-num       (assoc :datom.ingest.luds/lud lud-num)
                      (seq authors) (assoc :datom.ingest.luds/authors authors))}))

(defn dir-source
  "Create a ContentSource from a LUDS markdown directory."
  [dir]
  (reify ingest/ContentSource
    (source-id [_] (str "luds:" dir))
    (source-type [_] "doc")
    (source-items [_]
      (let [files (->> (.listFiles (java.io.File. ^String dir))
                       (filter #(re-matches #"\d+\.md" (.getName %)))
                       (sort-by #(Integer/parseInt (first (re-find #"(\d+)" (.getName %))))))
            items (mapv parse-luds-file files)
            readme (let [f (java.io.File. ^String dir "README.md")]
                     (when (.exists f)
                       {:content/id    "readme"
                        :content/type  "doc"
                        :content/title "LNURL Documents — Overview"
                        :content/body  (slurp f)
                        :content/meta  {}}))]
        (if readme (vec (cons readme items)) items)))))
