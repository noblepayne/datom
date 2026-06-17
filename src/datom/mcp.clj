(ns datom.mcp
  (:require [datom.core :as datom]
            [datom.index :as index]
            [datom.ingest :as ingest]
            [datom.ingest.luds :as luds]
            [mcp-toolkit.transport.streamable-http :as http]
            [cheshire.core :as json]
            [cheshire.generate :as json-gen])
  (:import [java.time Instant])
  (:gen-class))

(json-gen/add-encoder Instant
  (fn [inst jg]
    (.writeString jg (str inst))))

(defonce system (atom nil))

(defn- init-system! []
  (when (nil? @system)
    (reset! system (-> (datom/store-init) index/init-search!)))
  @system)

(defn- tool-search [sys {:keys [query top author expand]}]
  (let [opts (cond-> []
               top    (conj :top (int top))
               author (conj :author author)
               expand (conj :expand (int expand)))]
    (json/generate-string (apply datom/search sys query opts))))

(defn- tool-answer [sys {:keys [query]}]
  (datom/answer sys query))

(defn- tool-context [sys {:keys [query top]}]
  (let [opts (cond-> [] top (conj :top (int top)))]
    (json/generate-string (apply datom/context sys query opts))))

(defn- tool-lookup [sys {:keys [id]}]
  (json/generate-string (datom/lookup sys id)))

(defn- tool-stats [sys _]
  (json/generate-string (datom/stats sys)))

(defn- tool-graph-expand [sys {:keys [id]}]
  (json/generate-string (datom/graph-expand sys id)))

(defn- tool-ingest-luds [sys {:keys [path max-chars overlap]}]
  (let [opts    {:max-chars (or (some-> max-chars int) 2000)
                 :overlap   (or (some-> overlap int) 200)}
        summary (ingest/ingest sys (luds/dir-source path) opts)]
    (index/index-docs! sys (:ids summary))
    (json/generate-string summary)))

(def tools
  [{:name "search" :description "Hybrid fulltext + vector search" :handler tool-search
    :inputSchema {:type "object" :properties {:query {:type "string"} :top {:type "number"} :author {:type "string"} :expand {:type "number"}} :required ["query"]}}
   {:name "answer" :description "Search returning a human-readable string" :handler tool-answer
    :inputSchema {:type "object" :properties {:query {:type "string"}} :required ["query"]}}
   {:name "context" :description "Search with graph neighbor expansion" :handler tool-context
    :inputSchema {:type "object" :properties {:query {:type "string"} :top {:type "number"}} :required ["query"]}}
   {:name "lookup" :description "Pull a document by ID" :handler tool-lookup
    :inputSchema {:type "object" :properties {:id {:type "string"}} :required ["id"]}}
   {:name "stats" :description "System statistics" :handler tool-stats
    :inputSchema {:type "object" :properties {} :required []}}
   {:name "graph-expand" :description "Show 1-hop neighborhood" :handler tool-graph-expand
    :inputSchema {:type "object" :properties {:id {:type "string"}} :required ["id"]}}
   {:name "ingest-luds" :description "Ingest LUDS markdown specs from a directory" :handler tool-ingest-luds
    :inputSchema {:type "object" :properties {:path {:type "string"} :max-chars {:type "number"} :overlap {:type "number"}} :required ["path"]}}])

(def tool-by-name (into {} (map (fn [t] [(:name t) t]) tools)))

(defn dispatch [sys msg session-id]
  (case (:method msg)
    "initialize"
    {:jsonrpc "2.0" :id (:id msg)
     :result {:protocolVersion "2025-11-25"
              :capabilities {:tools {:listChanged true}}
              :serverInfo {:name "datom-mcp" :version "0.1.0"}
              :sessionId session-id}}
    "tools/list"
    {:jsonrpc "2.0" :id (:id msg)
     :result {:tools (mapv #(select-keys % [:name :description :inputSchema]) tools)}}
    "tools/call"
    (let [{:keys [name arguments]} (:params msg)
          tool (get tool-by-name name)]
      (if tool
        (try
          {:jsonrpc "2.0" :id (:id msg)
           :result {:content [{:type "text" :text ((:handler tool) sys arguments)}]}}
          (catch Throwable t
            {:jsonrpc "2.0" :id (:id msg)
             :result {:content [{:type "text" :text (str "Error: " (.getMessage t))}] :isError true}}))
        {:jsonrpc "2.0" :id (:id msg)
         :result {:content [{:type "text" :text (str "Unknown tool: " name)}] :isError true}}))
    nil))

(defn start-server [sys & [{:keys [port host] :or {port 0 host "127.0.0.1"}}]]
  (http/run-server (partial dispatch sys) {:port port :host host}))

(defn -main [& args]
  (let [sys  (init-system!)
        port (Long/parseLong (or (first args) (System/getenv "DATOM_MCP_PORT") "8080"))
        host (or (System/getenv "DATOM_MCP_HOST") "127.0.0.1")]
    (println "Starting datom-mcp on" (str host ":" port))
    (start-server sys {:port port :host host})
    @(promise)))