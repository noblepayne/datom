(ns datom.api
  (:require [datom.core :as datom]
            [datom.index :as index]
            [datom.store :as store]
            [cheshire.core :as json]
            [org.httpkit.server :as http])
  (:gen-class))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- read-body [request]
  (json/parse-string (slurp (:body request)) true))

(defn- handler [sys request]
  (let [method (:request-method request)
        path (:uri request)]
    (case [method path]
      [:get "/api/stats"]
      (json-response 200 (datom/stats sys))

      [:post "/api/search"]
      (let [body (read-body request)
            results (datom/search sys (:query body) (dissoc body :query))]
        (json-response 200 {:results results}))

      [:post "/api/answer"]
      (let [body (read-body request)
            answer (datom/answer sys (:query body) (dissoc body :query))]
        (json-response 200 {:answer answer}))

      [:post "/api/remember"]
      (let [body (read-body request)
            result (datom/remember sys body)]
        (json-response 200 result))

      [:post "/api/forget"]
      (let [body (read-body request)
            result (datom/forget sys (:id body))]
        (json-response 200 result))

      (json-response 404 {:error "Not found"}))))

(defn start-server [sys & [{:keys [port host] :or {port 0 host "127.0.0.1"}}]]
  (http/run-server (partial handler sys) {:port port :host host}))

(defn -main [& args]
  (let [sys (-> (datom/store-init) index/init-search!)
        port (Long/parseLong (or (first args) (System/getenv "DATOM_API_PORT") "9091"))
        host (or (System/getenv "DATOM_API_HOST") "127.0.0.1")]
    (println "Starting datom-api on" (str host ":" port))
    (start-server sys {:port port :host host})
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(store/close! sys)))
    @(promise)))
