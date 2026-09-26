# Real-Time Multiplayer Battleship

Java · React · WebSockets · PostgreSQL · Redis

A concurrent Java game server that runs many Battleship matches in parallel over
WebSockets, with Redis-backed matchmaking, PostgreSQL-backed accounts and match
history, and JWT-authenticated sessions. The React client talks to it over one
persistent socket and survives a refresh or a dropped connection mid-match.

This grew out of the ECE 422C Lab 5 assignment. The original two-player,
single-match submission is preserved untouched in [`src/`](src/); everything
described below is the new system in [`backend/`](backend/).

---

## What it does

- **Accounts.** Register and sign in. Passwords are stored as PBKDF2-HMAC-SHA256
  hashes; sessions are stateless HS256 JWTs, so any server instance can serve any
  client.
- **Matchmaking.** Players join a Redis sorted-set queue and are paired
  two-at-a-time by a single atomic Lua script. Pairing works across server
  instances, and the client is routed to whichever instance owns its match.
- **Concurrent gameplay.** Every match is an independent session with its own
  lock; unlimited matches run in parallel. Turn order, duplicate-shot rejection
  and sinking are all enforced server-side.
- **Chat** that is never delayed by game processing — it deliberately bypasses
  the game lock.
- **Reconnect.** Close the tab mid-match and come back: the server holds the
  match for a grace period, then replays full state from a Redis snapshot.
  If a server instance restarts, the snapshot rebuilds the session.
- **Turn clock.** Each turn has a deadline; three consecutive timeouts forfeit.
- **History and standings.** Every match and every shot is persisted. The lobby
  shows your record, your last 20 matches, and the top of the leaderboard.

## Architecture

```
                    ┌─────────────────────────────────────┐
  Browser           │  React (Vite)                       │
  ──────────        │  · REST for auth / history          │
                    │  · one WebSocket for all gameplay   │
                    └───────────────┬─────────────────────┘
                          HTTP + WS │ (same port)
                    ┌───────────────▼─────────────────────┐
                    │  Java server  (no web framework)    │
                    │                                     │
                    │  accept loop ─→ bounded conn pool   │
                    │       │                             │
                    │       ├─ REST router (auth, stats)  │
                    │       └─ WebSocket endpoint         │
                    │            thread per connection    │
                    │            + 1 writer thread each   │
                    │                                     │
                    │  GameSession   one lock per match   │
                    │  timers        turn + reconnect     │
                    │  writers       async persistence    │
                    └───────┬───────────────────┬─────────┘
                            │                   │
                  ┌─────────▼──────┐   ┌────────▼──────────┐
                  │  Redis         │   │  PostgreSQL       │
                  │  · queue (Lua) │   │  · users          │
                  │  · presence    │   │  · matches        │
                  │  · rate limits │   │  · match_moves    │
                  │  · snapshots   │   │  · leaderboard    │
                  │  · pub/sub     │   │                   │
                  └────────────────┘   └───────────────────┘
```

The server is built directly on `java.net.ServerSocket` — the WebSocket
handshake, frame codec, HTTP parsing and thread management are all hand-written.
That is deliberate: the concurrency is the point of the project, and a framework
would hide it. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the thread
inventory and the locking rules that keep it deadlock-free.

## Quick start

Requires Docker (and Node 20+ if you want to run the integration tests).

```bash
cp .env.example .env
docker compose up --build
```

Then open <http://localhost:5173>, register two accounts in two browser windows,
and hit **Find a match** in both.

Running the pieces separately:

```bash
# infrastructure only
docker compose up -d postgres redis

# backend (needs a JDK 21 and Maven, or use the Docker image)
cd backend && mvn package && java -jar target/battleship-server.jar

# frontend
cd frontend && npm install && npm run dev
```

## Tests

```bash
cd backend && mvn test          # 34 unit tests, including a concurrency stress test
./tests/run.sh                  # integration tests against a running stack
./tests/run.sh --multi          # also the cross-instance and load tests
```

The integration suite is not mocked — it plays real games over real WebSockets
against the running server. Notably, `tests/02-protocol-conformance.mjs` imports
the client's actual reducer and replays a real match's messages through it, so a
client/server protocol drift fails the build rather than showing up as a blank
board.

The load test starts 40 players across two instances, has them all queue in the
same tick, and asserts that exactly 20 matches form with no player paired twice.
On a laptop that burst clears in ~55 ms, about 2.7 ms per pairing.

## Resilience

If Redis becomes unavailable the server does not fall over: matchmaking returns
`MATCHMAKING_DOWN`, rate limiting fails open, presence counts report `-1`, and
matches already in memory keep playing. If PostgreSQL becomes unavailable, a
failed move-log batch is logged and dropped rather than retried, so gameplay is
unaffected — the move log is an audit trail, not authoritative state.

Load shedding is explicit rather than emergent: the connection pool is bounded
and returns a 503 with `Retry-After` once saturated, and a client whose outbox
backs up past 1024 frames is disconnected instead of being allowed to consume
server memory.

## Horizontal scaling

```bash
docker compose --profile scale up -d backend2   # a second instance on :8081
```

Instances share nothing but Redis and PostgreSQL. Matchmaking pairs players
regardless of which instance they are connected to; the instance that wins the
pairing owns the match and publishes it on a Redis Pub/Sub channel, and any
player connected elsewhere is handed that instance's URL and reconnects there.
`ADVERTISED_WS_URL` must therefore be the URL that reaches each instance from
the browser.

## HTTP API

| Method | Path                  | Auth   | Purpose                                  |
| ------ | --------------------- | ------ | ---------------------------------------- |
| GET    | `/api/health`         | –      | Liveness, instance id                    |
| GET    | `/api/stats`          | –      | Connections, live matches, queue depth   |
| POST   | `/api/auth/register`  | –      | Create an account, returns a JWT         |
| POST   | `/api/auth/login`     | –      | Sign in, returns a JWT                   |
| GET    | `/api/me`             | Bearer | Current account and record               |
| GET    | `/api/matches`        | Bearer | Your match history (`?limit=`)           |
| GET    | `/api/matches/{id}`   | Bearer | One match plus its full move log          |
| GET    | `/api/leaderboard`    | –      | Top players by wins (`?limit=`)          |
| —      | `/ws?token=<jwt>`     | Query  | WebSocket upgrade for all gameplay       |

Gameplay never goes over REST. The WebSocket message set is documented in
[docs/PROTOCOL.md](docs/PROTOCOL.md).

The token is passed as a query parameter on the WebSocket upgrade because the
browser `WebSocket` API cannot set an `Authorization` header. Behind TLS the
query string is encrypted in transit; it is still worth keeping out of access
logs in a real deployment.

## Configuration

Everything is environment-driven with development defaults — see
[`.env.example`](.env.example). The values worth knowing:

| Variable                  | Default              | Notes                                             |
| ------------------------- | -------------------- | ------------------------------------------------- |
| `JWT_SECRET`              | dev placeholder      | Startup **fails** with the default when `APP_ENV=production` |
| `ADVERTISED_WS_URL`       | `ws://localhost:8080/ws` | Must reach this instance from the browser     |
| `INSTANCE_ID`             | random               | Identifies the match owner across instances       |
| `MAX_CONNECTIONS`         | `512`                | Beyond this, new connections get a 503            |
| `TURN_TIMEOUT_SECONDS`    | `45`                 | Three consecutive timeouts forfeit                |
| `RECONNECT_GRACE_SECONDS` | `60`                 | How long a match is held for a dropped player     |
| `CORS_ORIGIN`             | `*`                  | Set to your frontend origin in production         |

## Repository layout

```
backend/                  Maven project — the game server
  src/main/java/com/battleship/
    api/                  REST router and handlers
    auth/                  password hashing, JWT, registration/login
    config/                environment-driven configuration
    db/                    JDBC pool, migrations, repositories
    game/                  boards, fleets, GameSession (the authoritative state)
    matchmaking/           queue dispatcher, live match registry, async writers
    net/                   HTTP parsing, WebSocket codec, accept loop, endpoint
    protocol/              JSON wire format
    redis/                 Redis client, Lua scripts, presence, rate limits
  src/main/resources/db/migration/   SQL migrations
frontend/                 React client (Vite, no runtime dependencies beyond React)
  src/hooks/useGameSocket.js         the socket and all live game state
  src/screens/                       login, lobby, game, result
tests/                    Integration tests (plain Node, no npm dependencies)
docs/                     Architecture and protocol reference
src/                      The original ECE 422C Lab 5 submission, unmodified
Documents/                The original lab handouts
```

## Notes on what is deliberately not here

- **No matchmaking by skill.** The queue is first-come, first-served; ranking
  exists only as a leaderboard.
- **No spectators or rematch.** Both are straightforward additions on top of
  `GameSession`, but neither is implemented.
- **Ship placement is server-generated.** Players do not place their own fleets.
- **The move log is an audit trail, not a source of truth.** It is written
  asynchronously and a failed batch is logged rather than retried, so gameplay
  never blocks on the database. Match results, which do matter, are written
  synchronously on the persistence pool and guarded against double-counting.
