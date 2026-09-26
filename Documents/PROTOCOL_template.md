# PROTOCOL.md — Message Protocol

**Name(s):** Sohan Shant
**EID(s):** sss5598

---

For each message type below, fill in all four fields:
- **Direction** — who sends it and who receives it
- **Fields** — every JSON field, its Java type, and whether it can be null/absent
- **When sent** — the exact condition that triggers this message
- **Receiver action** — what the recipient does upon receiving it

Add rows or sections for any message types you defined beyond the ones listed.

---

## ASSIGN

**Direction:** Server → Client

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"ASSIGN"`) | No |
| `playerNumber` | int | No |

**When sent:**
Sent right after the HTTP to WebSocket connection hanshake is completed.
**Receiver action:**
The client stores the player number that is assigned to it and then waits and sends a ready message to the server along with the user's name.

---

## WAITING

**Direction:** Server → Client (player 1 only)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"WAITING"`) | No |

**When sent:**
Immediately after the assign message, which tells that the other player has not connected yet.
**Receiver action:**
Clinet makes sure that its UI is set to waiting and displaying appropriate screen.

---

## READY

**Direction:** Client → Server

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"READY"`) | No |
| `name` | String | Yes |

**When sent:**
Sent by the client after it has recieved the assign message, and it includes the players name.
**Receiver action:**
Server stores the platers name and updates the ready count. Once both players have sent ready, server generates boards and fleets and sent start game message to all players.

---

## GAME_START

**Direction:** Server → Client

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"GAME_START"`) | No |
| `myBoard` | int\[\]\[\] | No |
| `turn` | int | No |

**When sent:**
Sent to both players when it has recieved ready from both players
**Receiver action:**
Client renders board and the starts the game, changing the UI state to playing and goes determined on if it is thier turn or not.

---

## FIRE

**Direction:** Client → Server

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"FIRE"`) | No |
| `row` | int | No |
| `col` | int | No |

**When sent:** 
When it is a players turn and they click a cell on the board to pick the cell to attack
**Receiver action:**
The server determines if the input was valid or not, depending on if it is not target already and then computes the result and updates the board and broadcasts the changes to both player and whether the game is over or turns are changed.

---

## SHOT_RESULT

**Direction:** Server → Client (both clients)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"SHOT_RESULT"`) | No |
| `shooter` | int | No |
| `row` | int | No |
| `col` | int | No |
| `hit` | boolean | No |
| `sunkShip` | String | Yes |

**When sent:** 
Sent by the server to both clients immediately after successfully processing a valid fire message.
**Receiver action:** 
The client updates the specific cell (row, col) on the respective board (opponent's board if they were the shooter, otherwise their own board) to a hit or miss. If ship is sunk, it marks that ship as sunk in the corresponding fleet status.

---

## TURN_CHANGE

**Direction:** Server → Client (both clients)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"TURN_CHANGE"`) | No |
| `turn` | int | No |

**When sent:** 
Sent by the server to both clients after a shot result is broadcast, provided the game has not ended.
**Receiver action:** 
The client updates its local turn indicator, enabling or disabling the ability to click on the opponent's board based on whether it is thier turn.

---

## CHAT (client → server)

**Direction:** Client → Server

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"CHAT"`) | No |
| `text` | String | No |

**When sent:** 
Sent by the client whenever the user submits text via the chat panel input.
**Receiver action:** 
The server extracts the text, identifies the sender's stored name, and immediately broadcasts a `CHAT` (server → client) message to both clients without waiting for any game-logic locks.

---

## CHAT (server → client)

**Direction:** Server → Client (both clients)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"CHAT"`) | No |
| `from` | String | No |
| `text` | String | No |

**When sent:** 
Sent by the server to both clients immediately upon receiving a chat message from any client.
**Receiver action:** 
The client appends the message text, sender name, and a local timestamp to its chat log state to display in the chat UI.

---

## GAME_OVER

**Direction:** Server → Client (both clients)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"GAME_OVER"`) | No |
| `winner` | int | No |
| `finalBoard` | int\[\]\[\] | No |

**When sent:** 
Sent by the server to both clients when a fire command results in the defender's fleet having 0 ships afloat.
**Receiver action:** 
The client displays the game-over screen, announces victory or defeat based on the winner field, and reveals the opponent's full hidden board layout using the provided data.

---

## OPPONENT_DISCONNECTED

**Direction:** Server → Client

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"OPPONENT_DISCONNECTED"`) | No |

**When sent:** 
Sent by the server to the remaining active client when the other client's socket connection closes gracefully or throws an I/O exception.
**Receiver action:** 
The client displays an error message indicating the opponent has disconnected and transitions the UI to the game-over state.

---

## ERROR

**Direction:** Server → Client

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"ERROR"`) | No |
| `message` | String | No |

**When sent:** 
Sent by the server to a specific client when it receives an invalid command, a command out of phase, an out-of-bounds coordinate, or an already targeted cell.
**Receiver action:** 
The client logs the provided error message to the console.