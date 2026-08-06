(ns datom.mcp-test
  (:require [clojure.test :refer :all]
            [datom.core :as datom]
            [datom.index :as index]
            [datom.ingest.luds :as luds]
            [datom.mcp :as mcp]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.client :as http]))

(defn- mcp-request [url sid method & {:keys [params]}]
  (let [body (json/generate-string
              (cond-> {:jsonrpc "2.0" :id 1 :method method}
                params (assoc :params params)))
        headers (merge {"Content-Type" "application/json"
                        "MCP-Protocol-Version" "2025-11-25"}
                       (when sid {"MCP-Session-Id" sid}))
        resp @(http/post url {:headers headers :body body})]
    {:status (:status resp)
     :body (json/parse-string (:body resp) true)
     :session-id (get-in resp [:headers :mcp-session-id])}))

(defn- mcp-delete [url sid]
  (let [resp @(http/delete url {:headers {"MCP-Session-Id" sid}})]
    (:status resp)))

(defn- temp-dir []
  (str "/tmp/datom-mcp-test-" (java.util.UUID/randomUUID)))

(defn- seed-embed-model! [db-dir]
  (let [src "/var/lib/datom/embed/multilingual-e5-small-Q8_0.gguf"
        dst (str db-dir "/embed/multilingual-e5-small-Q8_0.gguf")]
    (when (and (.exists (io/file src)) (not (.exists (io/file dst))))
      (.mkdirs (io/file (str db-dir "/embed")))
      (io/copy (io/file src) (io/file dst)))
    db-dir))

(deftest smoke-test
  (let [db-dir (seed-embed-model! (temp-dir))
        search-dir (temp-dir)]
    (try
      (let [sys (-> (datom/store-init {:datom.store/db-dir db-dir
                                       :datom.store/search-dir search-dir})
                    (index/init-search!)
                    (datom/ingest (luds/dir-source "test/fixtures/luds")))
            stop-fn (mcp/start-server sys {:port 0})
            port (-> stop-fn meta :local-port)
            url (str "http://127.0.0.1:" port "/mcp")]
        (testing "initialize creates session"
          (let [resp (mcp-request url nil "initialize")]
            (is (= 200 (:status resp)))
            (is (:session-id resp))))
        (let [sid (-> (mcp-request url nil "initialize") :session-id)]
          (testing "tools/list returns tools"
            (let [resp (mcp-request url sid "tools/list")]
              (is (= 200 (:status resp)))
              (let [tool-names (mapv :name (get-in resp [:body :result :tools]))]
                (is (= #{"search" "answer" "context" "lookup" "stats" "graph-expand" "ingest-luds" "remember" "forget"}
                       (set tool-names))))))
          (testing "tools/call stats returns stats"
            (let [resp (mcp-request url sid "tools/call" :params {:name "stats" :arguments {}})]
              (is (= 200 (:status resp)))
              (is (get-in resp [:body :result :content 0 :type]))))
          (testing "tools/call search returns results"
            (let [resp (mcp-request url sid "tools/call"
                                    :params {:name "search" :arguments {:query "summary" :top 2}})]
              (is (= 200 (:status resp)))
              (let [body (get-in resp [:body :result :content 0 :text])]
                (is (re-find #"lud" (str/lower-case body))))))
          (testing "DELETE /mcp terminates session"
            (is (= 200 (mcp-delete url sid)))))
        (stop-fn))
      (finally
        (io/delete-file db-dir true)
        (io/delete-file search-dir true)))))

(deftest test-remember-lookup
  (let [db-dir (seed-embed-model! (temp-dir))
        search-dir (temp-dir)]
    (try
      (let [sys (-> (datom/store-init {:datom.store/db-dir db-dir
                                       :datom.store/search-dir search-dir})
                    (index/init-search!))
            stop-fn (mcp/start-server sys {:port 0})
            port (-> stop-fn meta :local-port)
            url (str "http://127.0.0.1:" port "/mcp")
            sid (-> (mcp-request url nil "initialize") :session-id)]
        (testing "remember stores a document"
          (let [resp (mcp-request url sid "tools/call"
                                  :params {:name "remember"
                                           :arguments {:title "Test Note" :body "Hello world" :type "note"}})]
            (is (= 200 (:status resp)))
            (let [body (get-in resp [:body :result :content 0 :text])]
              (is (re-find #"id" body)))))
        (testing "forget deletes a document"
          (let [body (get-in (mcp-request url sid "tools/call"
                                          :params {:name "remember"
                                                   :arguments {:title "To Delete" :body "delete me"}})
                             [:body :result :content 0 :text])
                id (get (json/parse-string body) "id")
                resp (mcp-request url sid "tools/call"
                                  :params {:name "forget" :arguments {:id id}})]
            (is (= 200 (:status resp)))
            (let [body (get-in resp [:body :result :content 0 :text])]
              (is (re-find #"true" body)))))
        (mcp-delete url sid))
      (finally
        (io/delete-file db-dir true)
        (io/delete-file search-dir true)))))

(deftest test-mcp-error-paths
  (let [db-dir (temp-dir)
        search-dir (temp-dir)]
    (try
      (let [sys (-> (datom/store-init {:datom.store/db-dir db-dir
                                       :datom.store/search-dir search-dir})
                    (index/init-search!))
            stop-fn (mcp/start-server sys {:port 0})
            port (-> stop-fn meta :local-port)
            url (str "http://127.0.0.1:" port "/mcp")
            sid (-> (mcp-request url nil "initialize") :session-id)]
        (testing "unknown tool returns error"
          (let [resp (mcp-request url sid "tools/call"
                                  :params {:name "nonexistent" :arguments {}})]
            (is (= 200 (:status resp)))
            (is (get-in resp [:body :error]))
            (is (= -32602 (get-in resp [:body :error :code])))))
        (testing "forget nonexistent returns deleted=false"
          (let [resp (mcp-request url sid "tools/call"
                                  :params {:name "forget" :arguments {:id "ghost"}})]
            (is (= 200 (:status resp)))
            (let [body (get-in resp [:body :result :content 0 :text])]
              (is (re-find #"false" body)))))
        (mcp-delete url sid))
      (finally
        (io/delete-file db-dir true)
        (io/delete-file search-dir true)))))
