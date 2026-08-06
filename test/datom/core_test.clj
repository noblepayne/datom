(ns datom.core-test
  (:require [clojure.test :refer :all]
            [datom.core :as datom]
            [datom.index :as index]
            [datom.store :as store]
            [datom.ingest.luds :as luds]
            [datom.chunk :as chunk]
            [datom.graph :as graph]
            [clojure.java.io :as io]))

(defn- temp-dir []
  (str "/tmp/datom-test-" (java.util.UUID/randomUUID)))

(defn- seed-embed-model! [db-dir]
  (let [src "/var/lib/datom/embed/multilingual-e5-small-Q8_0.gguf"
        dst (str db-dir "/embed/multilingual-e5-small-Q8_0.gguf")]
    (when (and (.exists (io/file src)) (not (.exists (io/file dst))))
      (.mkdirs (io/file (str db-dir "/embed")))
      (io/copy (io/file src) (io/file dst)))
    db-dir))

(defmacro with-temp-store
  [[sys-sym] & body]
  (let [db-dir (gensym "db-dir")
        search-dir (gensym "search-dir")]
    `(let [~db-dir (seed-embed-model! (temp-dir))
           ~search-dir (temp-dir)
           ~sys-sym (datom/store-init {:datom.store/db-dir ~db-dir
                                       :datom.store/search-dir ~search-dir})]
       (try
         ~@body
         (finally
           (io/delete-file ~db-dir true)
           (io/delete-file ~search-dir true))))))

(deftest test-chunk-split
  (testing "splits long text into chunks"
    (let [text (str (apply str (repeat 10 "Lorem ipsum dolor sit amet.\n\n")) "END")
          chunks (chunk/split text {:max-chars 100 :overlap 20})]
      (is (> (count chunks) 1))
      (is (every? :text chunks))
      (is (every? :index chunks)))))

(deftest test-chunk-short-text
  (testing "short text stays as one chunk"
    (let [chunks (chunk/split "Hello world" {:max-chars 2000})]
      (is (= 1 (count chunks))))))

(deftest test-chunk-boundaries
  (testing "overlap=0 produces no overlap"
    (let [text (str "a.\n\nb.\n\nc.\n\nd.\n\ne.\n\nf.")
          chunks (chunk/split text {:max-chars 10 :overlap 0})]
      (is (= 2 (count chunks)))
      (is (every? #(re-find #"\n\n" (:text %)) chunks))))
  (testing "single paragraph longer than max-chars gets its own chunk"
    (let [text "a long paragraph that exceeds the maximum character limit for a single chunk"
          chunks (chunk/split text {:max-chars 20})]
      (is (= 1 (count chunks)))))
  (testing "empty text returns one chunk"
    (let [chunks (chunk/split "")]
      (is (= 1 (count chunks)))
      (is (= "" (:text (first chunks))))))
  (testing "asserts on non-positive max-chars"
    (is (thrown? AssertionError (chunk/split "hello" {:max-chars 0})))))

(deftest test-store-roundtrip
  (testing "transact then lookup"
    (with-temp-store [sys]
      (let [id "test-doc-1"]
        (store/transact! sys [{:content/id id
                               :content/type "doc"
                               :content/title "Test Document"
                               :content/body "Hello from the test"}])
        (let [doc (datom/lookup sys id)]
          (is (some? doc))
          (is (= id (:content/id doc)))
          (is (= "Test Document" (:content/title doc)))
          (is (= "Hello from the test" (:content/body doc)))))))
  (testing "lookup nonexistent returns nil"
    (with-temp-store [sys]
      (is (nil? (datom/lookup sys "nonexistent"))))))

(deftest test-ingest-search
  (testing "ingest then search returns expected results"
    (with-temp-store [sys]
      (let [sys (-> sys
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))]
        (let [results (datom/search sys "summary" {:top 10})]
          (is (pos? (count results)))
          (is (some #(= "lud-0" (:id %)) results))
          (is (some #(= "lud-1" (:id %)) results))
          (is (some #(= "readme" (:id %)) results) "README is included via fulltext search"))))))

(deftest test-graph-neighbors
  (testing "neighbors include linked docs"
    (with-temp-store [sys]
      (let [sys (-> sys
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))]
        (let [neighbors (graph/neighbors sys "lud-2")]
          (is (some #(= % "lud-2") neighbors))
          (is (some #(= % "lud-0") neighbors))))))
  (testing "dependents find backlinks"
    (with-temp-store [sys]
      (let [sys (-> sys
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))]
        (let [deps (graph/dependents sys "lud-0")]
          (is (some #(= % "lud-2") deps))
          (is (some #(= % "lud-1") deps)))))))

(deftest test-graph-expand
  (testing "graph-expand returns neighbors with titles"
    (with-temp-store [sys]
      (let [sys (-> sys
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))]
        (let [result (datom/graph-expand sys "lud-0")]
          (is (= "lud-0" (:id result)))
          (is (vector? (:neighbors result)))
          (is (vector? (:dependents result)))
          (is (some #(= "lud-0" (:id %)) (:neighbors result)))
          (is (every? :title (:neighbors result)))
          (is (every? :title (:dependents result)))))))
  (testing "graph-expand for lud-2 shows lud-0 in neighbors"
    (with-temp-store [sys]
      (let [sys (-> sys
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))]
        (let [result (datom/graph-expand sys "lud-2")]
          (is (some #(= "lud-0" (:id %)) (:neighbors result))))))))

(deftest test-context
  (testing "context returns results and neighbor map"
    (with-temp-store [sys]
      (let [sys (-> sys
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))]
        (let [result (datom/context sys "summary" {:top 3})]
          (is (vector? (:results result)))
          (is (map? (:neighbors result)))
          (is (pos? (count (:results result))))
          (doseq [r (:results result)]
            (is (:id r))
            (is (contains? (:neighbors result) (:id r))))
          (doseq [[id neighbors] (:neighbors result)]
            (is (vector? neighbors))
            (is (some #(= % id) neighbors) "each doc is its own neighbor")))))))

(deftest test-search-ranking
  (testing "search with :raw returns rank metadata"
    (with-temp-store [sys]
      (let [sys (-> sys
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))]
        (let [results (datom/search sys "summary" {:top 3 :raw true})]
          (is (pos? (count results)))
          (is (every? :rrf results))
          (is (every? :ft-rank results))
          (is (every? :sem-rank results))
          (let [rrfs (map :rrf results)]
            (is (= rrfs (sort > rrfs)) "results sorted by rrf descending")))))))

(deftest test-answer-format
  (testing "answer returns formatted string with bullets and IDs"
    (with-temp-store [sys]
      (let [sys (-> sys
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))]
        (let [result (datom/answer sys "summary")]
          (is (string? result))
          (is (re-find #"summary" result) "contains the query")
          (is (re-find #"• " result) "contains bullet markers")
          (is (re-find #"lud-0" result) "contains a doc ID"))))))

(deftest test-filter-by-author
  (testing "search with :author filters by LUDS author"
    (with-temp-store [sys]
      (let [sys (-> sys
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))]
        (let [all (datom/search sys "LNURL" {:top 20})
              filtered (datom/search sys "LNURL" {:top 20 :author "fiatjaf"})
              excluded (datom/search sys "LNURL" {:top 20 :author "nonexistent"})]
          (is (pos? (count all)))
          (is (pos? (count filtered)) "fiatjaf authored LUDS fixtures")
          (is (every? (set (map :id all)) (map :id filtered))
              "filtered results are a subset of unfiltered")
          (is (empty? excluded) "nonexistent author returns nothing"))))))

(deftest test-forget-edge-cases
  (testing "forget nonexistent returns {:deleted false}"
    (with-temp-store [sys]
      (let [sys (index/init-search! sys)]
        (is (= {:deleted false} (datom/forget sys "does-not-exist"))))))
  (testing "forget then lookup returns nil"
    (with-temp-store [sys]
      (let [sys (index/init-search! sys)
            {:keys [id]} (datom/remember sys {:title "X" :body "Y"})]
        (is (some? (datom/lookup sys id)))
        (datom/forget sys id)
        (is (nil? (datom/lookup sys id))))))
  (testing "double-forget returns {:deleted false} second time"
    (with-temp-store [sys]
      (let [sys (index/init-search! sys)
            {:keys [id]} (datom/remember sys {:title "X" :body "Y"})]
        (is (:deleted (datom/forget sys id)))
        (is (not (:deleted (datom/forget sys id))))))))
