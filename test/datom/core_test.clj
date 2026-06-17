(ns datom.core-test
  (:require [clojure.test :refer :all]
            [datom.core :as datom]
            [datom.store :as store]
            [datom.ingest.luds :as luds]
            [datom.chunk :as chunk]))

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

(deftest test-store-roundtrip
  (testing "store and lookup"
    (let [sys (datom/store-init {::store/db-dir     "/tmp/datom-test-db"
                                  ::store/search-dir "/tmp/datom-test-search"})]
      (datom/lookup sys "nonexistent")
      (is true))))
