# Request Attempts

The query id `[query-key params]` identifies both cached data and its current
request attempt. Every value that changes the result belongs in `params`.
Different pages and filters therefore have different identities: they may
fetch independently and populate reusable cache entries.

This is the same boundary used by TanStack Query. Starting a request for one
query key does not cancel a different key merely because both feed the same
screen.

## Protecting Refetches

Every adapter-backed regular query attempt has a unique request id. The id is
attached to the callback vector as metadata, so the public result-event shape
does not change. A result is committed only when its id still matches the
current attempt for that exact query.

Effect adapters must preserve the supplied callback vector and append the
result with `conj` or `into`. Rebuilding the vector discards its metadata and
the generation guard.

This prevents a late response from an older forced refetch from overwriting a
newer result, even when the transport cannot abort or cancellation loses a
race.

## Transport Cancellation

Physical cancellation remains transport-specific. The effect adapter receives
this namespaced value in every regular query request:

```clojure
{:re-frame.query/request-control
 {:query-id [:patients/page {:page 2}]
  :request-id #uuid "..."}}
```

An adapter may keep request handles outside app-db, indexed by `:query-id`.
Before starting a replacement for that exact query, it aborts the handle in the
slot and records the new `:request-id`. It should dispatch callbacks only for
the slot's current attempt. Browser handles remain outside app-db so the cache
stays serializable and inspectable.

Physical abort is an optimization, not the correctness boundary. The request
id check in re-frame-query remains necessary because a response can complete
while cancellation races with the network.

## Pagination Policy

For numbered or cursor-linked tables, put every request-changing value in the
query params:

```clojure
[:patients/page {:filters filters :cursor cursor :page-size 25}]
```

Returning to a successful page shows cached rows immediately. When its stale
time has elapsed, `ensure-query` keeps those rows visible and revalidates in
the background. A filter or page-size change selects a different cache entry;
it does not invalidate or cancel the old entry.

Use `refetch-query` for an explicit refresh, tag invalidation after relevant
mutations, and `reset-api-state!` when the authenticated identity changes.
