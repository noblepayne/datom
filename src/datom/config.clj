(ns datom.config
  "Environment variable reading and system configuration.
   Wraps System/getenv for testability with with-redefs.")

(defn get-env
  "Read an environment variable. Two arities:
    (get-env \"KEY\")     — required, throws if missing
    (get-env \"KEY\" default) — optional, returns default if missing"
  ([key]
   (or (System/getenv key)
       (throw (ex-info "Missing required environment variable"
                       {:var key}))))
  ([key default]
   (or (System/getenv key) default)))

(defn parse-int
  "Parse a string to Integer, returning nil on failure."
  [s]
  (try (Integer/parseInt s) (catch Exception _ nil)))