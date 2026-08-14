# Effect Overrides & Custom Callbacks

## Per-Query Effect Override

For queries that use a different transport (e.g., WebSocket instead of HTTP), provide a per-query `:effect-fn`:

```clojure
(rfq/reg-query :chat/messages
  {:query-fn  (fn [{:keys [room-id]}]
                {:channel (str "room:" room-id)
                 :event   "get-messages"})
   :effect-fn (fn [request on-success on-failure]
                {:ws-send (assoc request
                            :on-success on-success
                            :on-failure on-failure)})})
```

## Custom Success/Failure Callbacks

Need to run your own logic on success or failure? Extend the `on-success` / `on-failure` vectors in your `effect-fn`:

```clojure
;; Global — all queries/mutations dispatch ::my-app/on-success after the library handler
(rfq/set-default-effect-fn!
  (fn [request on-success on-failure]
    {:http-xhrio (assoc request
                   :on-success (into on-success [::my-app/on-success])
                   :on-failure (into on-failure [::my-app/on-failure]))}))

;; Per-query — only this query dispatches a custom event
(rfq/reg-query :books/list
  {:query-fn  (fn [_] {:method :get :url "/api/books"})
   :effect-fn (fn [request on-success on-failure]
                {:http-xhrio (assoc request
                                :on-success (into on-success [::books-loaded])
                                :on-failure on-failure)})})
```

Since `on-success` and `on-failure` are plain vectors, you have full control — append events, wrap them, or replace them entirely.

## Request-Control Metadata

Every `on-success` / `on-failure` vector re-frame-query hands your `effect-fn` carries the per-attempt request identity as **metadata** — `{:query-id :request-id :issued-at}`, readable with `rfq/request-control`. The library uses it to drop responses from requests a newer attempt superseded (see [`rfq/cancel-query`](lifecycle-hooks.md#advanced-cancelling-in-flight-requests)).

`conj` and `into` — as used in every example above — preserve metadata, so extending the vectors that way works transparently. **Rebuilding the vector strips the stamp silently, with no error**, and quietly turns supersession and `rfq/cancel-query` back off — stale responses will start overwriting fresh data again:

```clojure
;; WRONG — loses the request-control stamp
:on-success (vec (concat on-success [::my-app/on-success]))
:on-success [(first on-success) ::my-app/on-success]
```

If your adapter needs the stamp directly — e.g. to key an abort handle by `:query-id` — read it with `rfq/request-control`:

```clojure
(rfq/set-default-effect-fn!
  (fn [request on-success on-failure]
    (let [{:keys [query-id]} (rfq/request-control on-success)]
      {:http-xhrio (assoc request
                     :abort-key  query-id
                     :on-success on-success
                     :on-failure on-failure)})))
```

Queries using a complete legacy effects map are also protected when their
transport maps contain conventional `:on-success` and `:on-failure` callback
fields. Mutation callbacks do not carry query-attempt metadata.
