(ns datom.api-test
  (:require [clojure.test :refer :all]
            [datom.api :as api]
            [datom.core :as datom]
            [datom.index :as index]
            [datom.ingest.luds :as luds]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [org.httpkit.client :as http]))

(defn- temp-dir []
  (str "/tmp/datom-api-test-" (java.util.UUID/randomUUID)))

(defn- seed-embed-model! [db-dir]
  (let [src "/var/lib/datom/embed/multilingual-e5-small-Q8_0.gguf"
        dst (str db-dir "/embed/multilingual-e5-small-Q8_0.gguf")]
    (when (and (.exists (io/file src)) (not (.exists (io/file dst))))
      (.mkdirs (io/file (str db-dir "/embed")))
      (io/copy (io/file src) (io/file dst)))
    db-dir))

(defn- json-post [url path body]
  (let [resp @(http/post (str url path)
                         {:headers {"Content-Type" "application/json"}
                          :body (json/generate-string body)})]
    {:status (:status resp)
     :body (json/parse-string (:body resp) true)}))

(defn- json-get [url path]
  (let [resp @(http/get (str url path))]
    {:status (:status resp)
     :body (json/parse-string (:body resp) true)}))

(deftest test-api-smoke
  (let [db-dir (seed-embed-model! (temp-dir))
        search-dir (temp-dir)]
    (try
      (let [sys (-> (datom/store-init {:datom.store/db-dir db-dir
                                       :datom.store/search-dir search-dir})
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))
            stop-fn (api/start-server sys {:port 0})
            port (-> stop-fn meta :local-port)
            base (str "http://127.0.0.1:" port)]
        (testing "GET /api/stats returns stats"
          (let [resp (json-get base "/api/stats")]
            (is (= 200 (:status resp)))
            (is (pos? (:total (:body resp))))))
        (testing "POST /api/search returns results"
          (let [resp (json-post base "/api/search" {:query "summary" :top 2})]
            (is (= 200 (:status resp)))
            (is (vector? (:results (:body resp))))))
        (testing "POST /api/answer returns string"
          (let [resp (json-post base "/api/answer" {:query "summary"})]
            (is (= 200 (:status resp)))
            (is (string? (:answer (:body resp))))))
        (testing "POST /api/remember stores document"
          (let [resp (json-post base "/api/remember" {:title "API Test" :body "hello" :type "note"})]
            (is (= 200 (:status resp)))
            (is (string? (:id (:body resp))))))
        (testing "POST /api/forget removes document"
          (let [resp (json-post base "/api/remember" {:title "To Delete" :body "bye"})
                id (:id (:body resp))
                resp (json-post base "/api/forget" {:id id})]
            (is (= 200 (:status resp)))
            (is (true? (:deleted (:body resp))))))
        (stop-fn))
      (finally
        (io/delete-file db-dir true)
        (io/delete-file search-dir true)))))