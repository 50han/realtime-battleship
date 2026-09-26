# Architecture

How the server stays correct while many matches run at once, and why each piece
of infrastructure is there.

## Threads

Every thread in a running server, and what it does:

| Thread(s)              | Count                  | Role                                                         |
| ---------------------- | ---------------------- | ------------------------------------------------------------ |
| `main`                 | 1                      | Boots dependencies, then parks until shutdown                |
| `accept`               | 1                      | `ServerSocket.accept()` only; hands each socket to the pool  |
| `conn-N`               | 8 … `MAX_CONNECTIONS`  | Parses one request; then either answers REST and exits, or becomes the read loop for a WebSocket connection for its lifetime |
| `writer-<user>`        | 1 per connection       | The *only* thread that writes to that socket                 |
| `game-timer-N`         | 2                      | Turn deadlines and reconnect grace timers for all matches     |
| `persist-N`            | 4                      | Match results and Redis snapshots                            |
| `move-writer`          | 1                      | Batches shot rows into PostgreSQL                            |
| `matchmaker`           | 1                      | Drains the Redis queue two players at a time                 |
| `mm-subscriber`        | 1                      | Redis Pub/Sub listener for cross-instance match notifications |
| `heartbeat`            | 1                      | Pings every connection every 25s                             |

Thread-per-connection is the right shape for this workload: a game connection is
long-lived and mostly idle, the read is genuinely blocking, and it makes message
ordering for a single client trivially correct. The cost is one thread per
player, which is why the pool is bounded.

## The locking rules

Three rules, and together they make deadlock structurally impossible rather than
merely unlikely.

### 1. One lock per match, and it is a leaf

All state for a match — both boards, both fleets, the turn, the move counter,
the phase, the timers — is guarded by a single `ReentrantLock` inside
`GameSession`. Nothing else in the system has a lock that a session lock is
taken underneath:

- The live-session and connection registries are `ConcurrentHashMap`s, so
  callers never hold a lock across a lookup.
- A session never acquires *another* session's lock. There is no cross-match
  operation.
- Per-object locks inside `PlayerBoard` and `Fleet` were deliberately not
  added; those objects are unsynchronized and rely on the session lock above
  them. Giving them their own locks would create a hierarchy to get wrong.

With no lock nesting there is no lock ordering, and with no lock ordering there
is nothing to violate. The one nested acquisition that does exist — the chat
log's `synchronized` block, taken while building a resume payload under the
session lock — is strictly one-directional: the chat lock never reaches back for
the session lock.

`GameSessionTest.concurrentFireFromBothPlayersStaysConsistent` drives 800
unsynchronized `handleFire` calls from two threads and asserts both that the
threads finish (no deadlock) and that the move counter matches the number of
accepted shots exactly (no lost updates).

### 2. No I/O while a lock is held

Handlers follow the same shape throughout:

```java
lock.lock();
try {
    // mutate state, append outbound messages to a local list
} finally {
    lock.unlock();
}
flush(outbound);        // hand messages to writer queues
lifecycle.onMove(...);  // hand persistence to a background executor
```

Sends go into a per-client bounded queue drained by that client's
`WriterThread`, so a slow or malicious client cannot stall a match. Database and
Redis work goes through `MatchLifecycle` onto the `persist` pool or the
`move-writer` thread. No game thread ever waits on a socket or a query.

The `FinishPlan` returned by `finishLocked` exists purely to carry the
end-of-match persistence work back out past the `unlock()`.

### 3. Chat bypasses the game lock entirely

Inherited from the original lab and still worth keeping: a chat line must reach
both players even while a shot is being resolved. `handleChat` touches only the
chat log's own leaf lock and the two writer queues. It never contends with
gameplay.

## Redis

| Use                | Structure                     | Why Redis                                              |
| ------------------ | ----------------------------- | ------------------------------------------------------ |
| Matchmaking queue  | sorted set, scored by arrival | Atomic multi-key pairing via Lua                       |
| Queue tickets      | hash per player, TTL          | Records which instance a waiting player is on          |
| Active match       | string per player, TTL        | Drives the reconnect lookup                            |
| Match ownership    | hash per match                | Tells other instances where a match lives              |
| Match snapshot     | string per match, TTL 1h      | Rebuilds a session after a reconnect or restart        |
| Presence           | set of user ids               | Online count that is correct across instances          |
| Rate limits        | counter per user/window       | Shared limits regardless of which instance you hit     |
| Match notifications| Pub/Sub channel               | Reaches a paired player connected to another instance  |

### Why the pairing is a Lua script

Pairing touches six keys at once: the queue, both tickets, both active-match
pointers, and the ownership hash. Done from Java that means either a distributed
lock over all of them — which deadlocks the moment two callers take keys in
different orders — or a `WATCH`/`MULTI` retry loop, which livelocks when the
queue is hot and many servers are competing for the same two players.

Redis runs a script atomically on its single command thread. So the whole
multi-key update is one indivisible step: no intermediate state is observable,
there is no lock to acquire, and any number of server instances can run the
pairing loop concurrently with at most one of them being handed any given pair.

The script also handles the awkward case honestly: `ZPOPMIN … 2` on a queue of
one returns one player, and the script puts them back **at their original
score**, so they keep their place in line rather than being sent to the back.

Every script lives in `redis/LuaScripts.java`, is cached by SHA, and is invoked
with `EVALSHA` with an automatic `EVAL` fallback if Redis has been restarted and
forgotten it.

## PostgreSQL

Three tables and a view (`resources/db/migration/`):

- `users` — credentials and the win/loss counters. Usernames are
  case-insensitively unique via a `lower(username)` index, which avoids needing
  the `citext` extension.
- `matches` — one row per game, opened `IN_PROGRESS` and closed with a status,
  winner, shot count and end time.
- `match_moves` — every shot, with its move number, unique per
  `(match_id, move_no)`.
- `leaderboard` — a view, so the ranking rule lives in one place.

Migrations are applied forward-only, each in a transaction, recorded in
`schema_migrations`, and the whole run is wrapped in a PostgreSQL advisory lock
so starting several instances simultaneously cannot apply one twice.

Two details worth calling out:

- **Idempotent finishing.** `finishMatch` only updates rows where
  `ended_at IS NULL` and reports whether it was the call that actually closed
  the match. Only that call adjusts win/loss counters, so a disconnect racing the
  winning shot cannot double-count.
- **Ordered row locks.** `recordResult` issues its two `UPDATE`s in ascending
  user-id order, so two matches finishing simultaneously and sharing a player
  always take row locks in the same order.

Connections come from a HikariCP pool. A JDBC `Connection` is not thread-safe;
pooling is what stops N parallel matches serializing behind one shared
connection.

## Authentication

Registration hashes the password with PBKDF2-HMAC-SHA256, 210,000 iterations, a
random 16-byte salt, stored as `pbkdf2_sha256$<iters>$<salt>$<hash>` so the cost
parameter can be raised later without invalidating existing accounts.
Verification is constant-time.

Login always runs the KDF — even for a username that does not exist, against a
decoy hash — so response timing does not reveal which accounts exist.

Sessions are HS256 JWTs carrying the user id and username. Verification is
stateless, which is what lets any instance accept a token minted by any other
(`tests/03-multi-instance.mjs` asserts exactly this). The same token
authenticates REST calls via `Authorization: Bearer` and the WebSocket upgrade
via `?token=`.

## Reconnect and match ownership

When a socket dies mid-match the server does *not* end the game. It marks the
slot empty, tells the opponent, and starts a grace timer. Three things can
happen next:

1. **The player comes back to the same instance.** The session is still in
   memory; they get `GAME_RESUME` with both boards, both fleets, the turn
   deadline and the chat backlog.
2. **The player comes back to a different instance.** Redis says which instance
   owns the match; the client is handed that instance's URL and reconnects there.
3. **Nobody comes back before the grace period expires.** The opponent wins by
   `OPPONENT_ABANDONED`.

If the owning instance itself restarted, its Redis snapshot is replayed into a
fresh `GameSession`. The restore path uses `computeIfAbsent`, so two players
reconnecting at the same instant cannot each build their own copy of the same
match; and `Fleet.restoreFrom` recomputes how many ships are afloat from the hit
counts rather than trusting the stored number, so a truncated snapshot cannot
invent a ship back into play.

## Load shedding and abuse

- The connection pool is bounded and backed by a `SynchronousQueue`. Once
  `MAX_CONNECTIONS` threads are busy, new arrivals get an immediate 503 with
  `Retry-After` rather than being accepted and left hanging.
- Each client's outbox is capped at 1024 frames. A client that stops reading is
  disconnected instead of being allowed to grow the queue without bound.
- Rate limits (Redis counters): 30 shots per 10s, 10 chat messages per 5s, 10
  queue joins per 60s. The limiter **fails open** if Redis is briefly
  unavailable — losing a rate limit is much less bad than losing the game server.
- Frames are capped at 256 KB reassembled, request bodies at 64 KB, headers at
  16 KB.
- A 25s server-side ping against a 90s socket read timeout surfaces peers that
  vanished without a TCP FIN.

## Notable implementation details

**HTTP headers are read one byte at a time.** A `BufferedReader` would pull
bytes past the blank line into its own buffer — and on a WebSocket upgrade those
bytes are the connection's first frames, which would then be lost.
`HttpRequest.parse` stops exactly at the CRLF CRLF, leaving the stream
positioned for the frame codec. This is what lets REST and WebSocket share one
port.

**Control frames go through the writer queue.** An inbound ping needs a pong,
but the reader thread must not write to the socket directly — that would
interleave with a game update from a game thread and corrupt the frame stream.
`WebSocketUtil.readMessage` takes a callback and hands the pong to the client's
`WriterThread` instead.

**JSON is a real serializer.** The original lab built protocol strings by
concatenation, which broke on any chat message containing a quote, backslash or
newline. Jackson removes that whole class of bug, and the injection risk with it.
`ClientMessageTest` pins the behaviour.

**Hidden information is enforced by construction.** `PlayerBoard` has three view
methods: `ownerView()` reveals ship positions, `attackerView()` cannot (it is
built only from shot results), and `revealedView()` is used only after the match
ends. `Fleet.toOpponentJson()` likewise reports damage only on ships that have
already been announced sunk. A test asserts the attacker view never contains a
ship marker.

## Known limitations

- A match lives on one instance. If that instance dies, players reconnect and
  the match is rebuilt from Redis — but only if the snapshot is current, and a
  snapshot write that failed silently leaves the match unrecoverable. The
  active-match pointer is cleared in that case so players are not stuck.
- The reconnect redirect requires `ADVERTISED_WS_URL` to be externally correct
  per instance. There is no service discovery.
- Rate limiting is fixed-window, so a burst straddling a window boundary can
  briefly get double the allowance.
- `abandonStaleMatches` uses a two-hour cutoff at startup, so a crashed
  instance's matches show as `IN_PROGRESS` in history until then.
