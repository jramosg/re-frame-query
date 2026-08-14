# Request Attempts and Cancellation

re-frame-query gives every query attempt a fresh `:request-id`. When attempts
overlap for the same query id and params, only the latest attempt may update
the cache. This prevents a slow response from an older request from
overwriting a newer response that arrived first.

## Callback metadata

The request id travels as metadata on the success and failure event vectors:

```clojure
(rfq/request-control on-success)
;; => {:query-id [:todos/list {:user-id 42}]
;;     :request-id <uuid>
;;     :issued-at <monotonic-ms>}
```

An effect adapter must preserve that metadata when appending the response:

```clojure
(rfq/set-default-effect-fn!
  (fn [request on-success on-failure]
    {:http-xhrio (assoc request
                        :on-success on-success
                        :on-failure on-failure)}))

;; Append response data with conj or into. Both preserve metadata.
(rf/dispatch (conj on-success response))
```

Do not rebuild the callback with `vec`, `concat`, or a new event literal.
Losing the metadata is a fail-open compatibility boundary: the result still
commits, but overlapping responses are no longer protected.

Queries whose `query-fn` returns a complete legacy effects map are stamped
automatically when conventional `:on-success` and `:on-failure` callback
fields are present in the transport effect map.

## Logical cancellation

Dispatch `cancel-query` to supersede the current attempt:

```clojure
(rf/dispatch [::rfq/cancel-query :todos/list {:user-id 42}])
```

The cancelled attempt's later success or failure is ignored. If a request was
in flight, the cache entry remains stale, so `ensure-query` can retry it. The
event does not abort the underlying HTTP, WebSocket, or other transport. For
physical abort, implement that in the effect adapter and use
`rfq/request-control` to associate the transport handle with the query or
attempt.

Infinite-query refetch chains share one request id. Cancelling a chain drops
late pages from the whole chain and leaves the already-loaded pages intact.
