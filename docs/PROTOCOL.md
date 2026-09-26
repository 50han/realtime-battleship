# WebSocket protocol

One socket per player carries everything: matchmaking, gameplay and chat.
All frames are JSON text with a `type` field.

Connect to `ws://<host>/ws?token=<jwt>`. The token is verified **before** the
upgrade; an invalid one gets a plain HTTP `401` rather than an opaque close.

Server-side sources of truth: `protocol/ServerMessage.java`,
`protocol/ClientMessage.java`, `protocol/ErrorCodes.java`.

## Board encoding

Boards are `10 × 10` arrays of arrays, row-major, `board[row][col]`.
Row 0 is displayed as `A`, column 0 as `1`.

| Code | Meaning                                                    |
| ---- | ---------------------------------------------------------- |
| `0`  | Unknown / open water                                       |
| `1`  | One of your own ships, undamaged (never sent for an enemy board before the match ends) |
| `2`  | Hit                                                        |
| `3`  | Miss                                                       |

`playerSlot` is `1` or `2`. Slot 1 always moves first.

The fleet, in the order used everywhere: Carrier (5), Battleship (4),
Cruiser (3), Submarine (3), Destroyer (2) — 17 cells in total.

## Client → server

| Type          | Fields       | Notes                                                        |
| ------------- | ------------ | ------------------------------------------------------------ |
| `QUEUE_JOIN`  | –            | Enter matchmaking. Rejected if already queued or in a match. |
| `QUEUE_LEAVE` | –            | Leave the queue.                                             |
| `FIRE`        | `row`, `col` | Fire at the opponent. Integers, or strings that parse as integers. |
| `CHAT`        | `text`       | Trimmed, then truncated to 300 characters.                   |
| `FORFEIT`     | –            | Resign. Counts as a loss.                                    |
| `PING`        | –            | Application-level keepalive; answered with `PONG`.           |

An unparseable frame or an unknown `type` yields one `ERROR` and leaves the
connection open.

## Server → client

### `HELLO`
Sent immediately after a successful upgrade.
```json
{ "type": "HELLO", "userId": 42, "username": "admiral",
  "instanceId": "backend-1", "hasActiveMatch": false, "serverTime": 1775000000000 }
```
`hasActiveMatch` means a `GAME_RESUME` (or a redirect) is following.

### `QUEUE_JOINED` / `QUEUE_STATUS` / `QUEUE_LEFT`
```json
{ "type": "QUEUE_JOINED", "queueSize": 3, "waitingSince": 1775000000000 }
{ "type": "QUEUE_STATUS", "queueSize": 3, "onlinePlayers": 11 }
{ "type": "QUEUE_LEFT",   "reason": "LEFT" }
```
`onlinePlayers` is `-1` if Redis could not be reached.

### `MATCH_FOUND`
```json
{ "type": "MATCH_FOUND", "matchId": "uuid", "playerSlot": 1,
  "opponent": { "userId": 43, "username": "captain" },
  "serverUrl": "ws://localhost:8080/ws", "reconnectRequired": false }
```
Always arrives **before** `GAME_START` for that match. Clients should treat it
as idempotent — a repeat for a match already under way must not reset state.

If `reconnectRequired` is `true`, this match is owned by a different server
instance: close this socket and reconnect to `serverUrl`. The remaining fields
may be placeholders in that case.

### `GAME_START`
```json
{ "type": "GAME_START", "matchId": "uuid", "playerSlot": 1,
  "myBoard": [[0,1,...], ...], "turn": 1, "turnDeadline": 1775000045000,
  "myName": "admiral", "opponentName": "captain" }
```
`turnDeadline` is an absolute epoch-millis timestamp, not a duration, so a
client that was backgrounded still renders an honest countdown.

### `GAME_RESUME`
Full state after a mid-match reconnect. Everything in `GAME_START`, plus:
```json
{ "opponentBoard": [[...]],
  "myFleet":       [{ "name": "Carrier", "size": 5, "hits": 2, "sunk": false }, ...],
  "opponentFleet": [{ "name": "Carrier", "size": 5, "hits": 0, "sunk": false }, ...],
  "opponentConnected": true,
  "chatLog": [{ "from": "captain", "text": "gl", "at": 1775000001000 }] }
```
`opponentFleet` reports `hits` only for ships already announced sunk — enemy
damage is not disclosed until a ship goes down. Up to 60 chat lines are replayed.

### `SHOT_RESULT`
Broadcast to both players for every accepted shot.
```json
{ "type": "SHOT_RESULT", "shooter": 1, "row": 3, "col": 7,
  "hit": true, "sunkShip": "Destroyer", "moveNo": 12 }
```
`sunkShip` is `null` unless this shot sank a ship. If `shooter` is your own
slot, apply the result to your view of the *enemy* board; otherwise to your own.

### `TURN_CHANGE`
```json
{ "type": "TURN_CHANGE", "turn": 2, "turnDeadline": 1775000090000, "reason": null }
```
`reason` is `"TIMEOUT"` when the previous player ran out of time, otherwise
`null`.

### `CHAT`
```json
{ "type": "CHAT", "from": "captain", "text": "nice shot", "at": 1775000002000 }
```
Echoed to the sender as well, so both clients render the same log.

### `GAME_OVER`
```json
{ "type": "GAME_OVER", "matchId": "uuid", "winner": 1, "reason": "FLEET_DESTROYED",
  "myFinalBoard": [[...]], "opponentFinalBoard": [[...]], "totalShots": 33 }
```
Both boards are fully revealed. `reason` is one of `FLEET_DESTROYED`,
`FORFEIT`, `OPPONENT_ABANDONED`, `TIMEOUT_FORFEIT`.

### `OPPONENT_DISCONNECTED` / `OPPONENT_RECONNECTED`
```json
{ "type": "OPPONENT_DISCONNECTED", "graceSeconds": 60 }
{ "type": "OPPONENT_RECONNECTED",  "opponentName": "captain" }
```
The match stays playable during the grace period. If it expires, `GAME_OVER`
follows with `OPPONENT_ABANDONED`.

### `ERROR`
```json
{ "type": "ERROR", "code": "NOT_YOUR_TURN", "message": "It is not your turn" }
```
Codes are stable; `message` is prose and may change.

| Code                | Meaning                                            |
| ------------------- | -------------------------------------------------- |
| `UNKNOWN_COMMAND`   | Unrecognised `type`                                |
| `NOT_IN_MATCH`      | Gameplay message with no active match              |
| `NOT_YOUR_TURN`     | Fired out of turn — the turn does **not** advance  |
| `OUT_OF_BOUNDS`     | Coordinate outside `0…9`                           |
| `ALREADY_TARGETED`  | That cell has already been fired at                |
| `GAME_NOT_ACTIVE`   | The match has finished                             |
| `ALREADY_QUEUED`    | Already in the matchmaking queue                   |
| `ALREADY_IN_MATCH`  | Finish or forfeit the current match first          |
| `NOT_QUEUED`        | `QUEUE_LEAVE` while not queued                     |
| `RATE_LIMITED`      | Too many messages of that kind                     |
| `MATCHMAKING_DOWN`  | Redis unreachable                                  |
| `EMPTY_MESSAGE`     | Empty chat text                                    |
| `SESSION_REPLACED`  | The same account connected from another window     |
| `SERVER_SHUTDOWN`   | The instance is stopping                           |
| `INTERNAL_ERROR`    | Unhandled server error; connection stays open      |

### `PONG`
```json
{ "type": "PONG", "serverTime": 1775000003000 }
```

## Typical sequences

**A new match**
```
C→S  QUEUE_JOIN
S→C  QUEUE_JOINED, QUEUE_STATUS
S→C  MATCH_FOUND
S→C  GAME_START
C→S  FIRE {row, col}                (slot 1 only)
S→C  SHOT_RESULT                    (both players)
S→C  TURN_CHANGE                    (both players)
     … repeat …
S→C  SHOT_RESULT, GAME_OVER
```

**A dropped connection**
```
                     (socket closes)
S→opponent  OPPONENT_DISCONNECTED {graceSeconds: 60}
C→S  (new socket, same JWT)
S→C  HELLO {hasActiveMatch: true}
S→C  GAME_RESUME
S→opponent  OPPONENT_RECONNECTED
```

**Paired onto another instance**
```
C→S  QUEUE_JOIN
S→C  QUEUE_JOINED
S→C  MATCH_FOUND {reconnectRequired: true, serverUrl: "ws://host-b/ws"}
     (client closes and reconnects to host-b)
S→C  HELLO, GAME_START
```

## Transport notes

- Server→client frames are never masked; client→server frames must be masked
  (RFC 6455 §5.1). An unmasked client frame closes the connection.
- Fragmented messages are reassembled across continuation frames. Control
  frames may be interleaved and do not interrupt reassembly.
- Binary frames are dropped; this protocol is text-only.
- The server pings every 25 seconds against a 90-second read timeout. Browsers
  answer automatically.
- WebSocket version 13 only.
