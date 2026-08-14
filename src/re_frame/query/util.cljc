(ns re-frame.query.util
  "Internal utility functions for re-frame-query.")

(def default-query
  {:status :idle
   :data nil
   :error nil
   :fetching? false
   :stale? true
   :active? false
   :tags #{}})

(defn merge-with-default
  [& maps]
  (apply merge (into [default-query] maps)))

(defn now-ms
  "Returns current time in milliseconds since epoch."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn mono-now
  "Monotonic elapsed-time reading in fractional milliseconds.
   Diagnostics only (latency, watchdogs, inspector) — never an identity."
  []
  #?(:clj (/ (System/nanoTime) 1e6)
     :cljs (if (exists? js/performance) (.now js/performance) (.now js/Date))))

(defn query-id
  "Creates a canonical cache key from a query/mutation name and params."
  [k params]
  [k (or params {})])

(defn gen-request-id
  "Generate a UUID to be used as a request attempt id."
  []
  ; java.util.UUID rather than raising the minimum version for consumers.
  #?(:clj (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(def request-control-key
  "Metadata key under which the per-attempt request-control map travels on a
   result callback event vector. Metadata — not a positional element — so the
   event shape and the 3-arg effect-fn arity stay unchanged."
  :re-frame.query/request-control)

(defn with-request-control
  "Returns `event` with `control` attached as `request-control-key` metadata.
   The positional event vector is untouched, so adapters that append results
   with `conj`/`into` carry the stamp through for free."
  [event control]
  (vary-meta event assoc request-control-key control))

(defn request-control
  "Returns the request-control map carried by `event`, or nil when the event
   has none (a hand-dispatched event, or an adapter that rebuilt the vector)."
  [event]
  (get (meta event) request-control-key))

(defn current-attempt?
  "Determines if a result stamped with `req-id` still belongs to the attempt
   `query` is waiting for — i.e. whether it should be committed.

   Request ids are opaque unique values (UUIDs) supplied by the
   `:re-frame.query/request-id` coeffect, so an id is never reused.

   Fails OPEN: a nil `req-id` (no request-control metadata on the event) is
   always accepted, so a custom effect adapter that drops the metadata keeps
   working instead of silently swallowing every result.

   A non-nil `req-id` against a nil `query` returns false — the entry was
   evicted or wiped, and a late response must not resurrect it."
  [query req-id]
  (or (nil? req-id)
      (= req-id (:request-id query))))

(defn stale?
  "Determines if a query entry needs refetching.
   Returns true when:
   - query does not exist
   - query is explicitly marked stale
   - query is in an error state
   - stale-time-ms has elapsed since last fetch"
  [query now]
  (boolean
   (or (nil? query)
       (:stale? query)
       (= :error (:status query))
       (let [stale-time (:stale-time-ms query)
             fetched-at (:fetched-at query)]
         (and stale-time
              fetched-at
              (> (- now fetched-at) stale-time))))))

(defn tag-match?
  "Returns true if any of the `invalidation-tags` appear in `query-tags`."
  [query-tags invalidation-tags]
  (boolean
   (and (seq query-tags)
        (seq invalidation-tags)
        (some (set query-tags) invalidation-tags))))

(defn infinite-query?
  "Returns true if the query config has an :infinite key."
  [query-config]
  (boolean (:infinite query-config)))

(defn normalize-hook-events
  "Normalizes a mutation hook value into a vector of event vectors.
   Accepts a single event vector `[:evt ...]` or a collection of them."
  [hook]
  (cond
    (empty? hook) []
    (keyword? (first hook)) [hook]
    :else (vec hook)))

(defn parse-result-event
  "Parses one of the four rfq query result events into a map, hiding the
   positional event-vector shape from callers (interceptors, telemetry, etc.).

   Recognized events and the maps they produce:
     [:re-frame.query/query-success         k params data]
       => {:event-id ... :k ... :params ... :data data}

     [:re-frame.query/query-failure         k params error]
       => {:event-id ... :k ... :params ... :error error}

     [:re-frame.query/infinite-page-success k params mode page-data]
       => {:event-id ... :k ... :params ... :mode mode :data page-data}
          (mode is nil | :append | :prepend)

     [:re-frame.query/infinite-page-failure k params error]
       => {:event-id ... :k ... :params ... :error error}

   Also adds `:request-control` — `{:query-id ... :request-id ... :issued-at ...}`
   — when the event carries it, so callers never reach into raw metadata.

   Returns nil for any other event vector — callers can branch on truthiness."
  [event]
  (let [[event-id k params a b] event
        control (request-control event)
        rfq-result-event? (#{:re-frame.query/query-success
                             :re-frame.query/query-failure
                             :re-frame.query/infinite-page-success
                             :re-frame.query/infinite-page-failure}
                           event-id)]
    (when rfq-result-event?
      (cond-> {:event-id event-id :k k :params params}
        (some? control)
        (assoc :request-control control)

        (= event-id :re-frame.query/query-success)
        (assoc :data a)

        (= event-id :re-frame.query/query-failure)
        (assoc :error a)

        (= event-id :re-frame.query/infinite-page-success)
        (assoc :mode a :data b)

        (= event-id :re-frame.query/infinite-page-failure)
        (assoc :error a)))))
