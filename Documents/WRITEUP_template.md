# Lab 5 Design Writeup — Networked Battleship

**Name(s):** Sohan Shant
**EID(s):** sss5598
**Date submitted:** 04/13/2026

---

## What This Document Is

2–3 pages of honest reflection on the decisions you made and the problems you hit.  Not a summary of the lab spec — we wrote the spec, we know what it says.

Full credit requires:

- Two specific design choices with a stated reason and a named alternative
- A complete message sequence diagram covering one full game
- Three concurrency questions answered with specific references to your code
- Two concrete bugs (symptom → cause → fix → lesson learned)
- A testing section that describes what you actually ran, not what you planned to run

Vague entries ("I synchronized the shared state") earn no credit. Entries like "I made `handleMessage` synchronized because both reader threads call it concurrently and I need turn/readyCount/boards to be updated atomically — a `BlockingQueu`e approach would have also worked but requires a fifth thread and complicates the CHAT bypass" earn full credit.

---

## Section 1 — Design Choices (two required, ~100 words each)

For each: what did you choose, what was the alternative, and why?

Candidate topics (pick two; you may substitute your own if more interesting):

- synchronized method on handleMessage vs. a dedicated BlockingQueue consumer thread for game logic — why did you choose one over the other?
- How you handle CHAT without the game-logic lock — what exactly does your code do differently for CHAT vs. FIRE?
- WriterThread + LinkedBlockingQueue per client vs. making writeFrame calls directly from GameServer with a per-client lock
- Where you send ASSIGN — why the timing relative to the WebSocket handshake matters and what goes wrong if you get it wrong
- How you signal threads to exit after game over — sentinel in queue, socket close, interrupt, or something else

### 1.A — Synchronization approach for Game Logic

*What I chose:* 
I wrapped the READY and FIRE message processing in GameServer.handleMessage with a synchronized(this) block to act as a monitor lock when updating the game state.

*What I considered instead:* 
Making a totally separate game-logic thread that pulls messages from a LinkedBlockingQueue fed by both ClientHandlers.

*Why:* 
Using the monitor lock was much simpler because I didn't have to manage or shut down a fifth thread. If I had used a queue, all messages would be forced into a single line. That means I would have had to catch and pull out CHAT messages *before* they hit the queue just to make the chat bypass requirement work. The synchronized block just lets me update state quickly while keeping shared variables like turn and readyCount safe from race conditions.

---

### 1.B — Outbound Message Delivery

*What I chose:* 
A dedicated WriterThread for each client, using an unbounded LinkedBlockingQueue<String> for their outbox.

*What I considered instead:* 
Doing blocking writeFrame calls directly from the GameServer or ClientHandler threads and just wrapping the output stream in a synchronized block.

*Why:* 
Network I/O can be really slow and sometimes blocks the thread. If the GameServer got stuck holding a lock while waiting for Player 1's network connection to catch up, Player 2 would just be sitting there waiting, and the whole game would freeze. Passing the writes off to a dedicated thread means the main game lock is only held for fast memory updates, keeping everything moving smoothly without network lag causing logic stalls.

---

## Section 2 — Message Protocol

### 2.1 — Full message sequence for one complete game

Write out the complete sequence of messages exchanged between the server and both clients, from the moment each browser tab connects through to GAME_OVER. Include who sends each message and who receives it.  Use the format below.

Do not skip the handshake phase or abbreviate the mid-game turns — show at least three full fire/result/turn-change cycles.

    [Browser tab 1]  →  [Server]   TCP connect
    [Server]         →  [Tab 1]    ASSIGN {"type":"ASSIGN","playerNumber":1}
    [Server]         →  [Tab 1]    WAITING {"type":"WAITING"}
    [Browser tab 2]  →  [Server]   TCP connect
    [Server]         →  [Tab 2]    ASSIGN {"type":"ASSIGN","playerNumber":2}
    [Tab 1]          →  [Server]   READY {"type":"READY","name":"Player 1"}
    [Tab 2]          →  [Server]   READY {"type":"READY","name":"Player 2"}
    [Server]         →  [Tab 1]    GAME_START {"type":"GAME_START","myBoard":[[0,1...]],"turn":1}
    [Server]         →  [Tab 2]    GAME_START {"type":"GAME_START","myBoard":[[0,0...]],"turn":1}
    [Tab 1]          →  [Server]   FIRE {"type":"FIRE","row":0,"col":0}
    [Server]         →  [Tab 1]    SHOT_RESULT {"type":"SHOT_RESULT","shooter":1,"row":0,"col":0,"hit":false,"sunkShip":null}
    [Server]         →  [Tab 2]    SHOT_RESULT {"type":"SHOT_RESULT","shooter":1,"row":0,"col":0,"hit":false,"sunkShip":null}
    [Server]         →  [Tab 1]    TURN_CHANGE {"type":"TURN_CHANGE","turn":2}
    [Server]         →  [Tab 2]    TURN_CHANGE {"type":"TURN_CHANGE","turn":2}
    [Tab 2]          →  [Server]   FIRE {"type":"FIRE","row":4,"col":4}
    [Server]         →  [Tab 1]    SHOT_RESULT {"type":"SHOT_RESULT","shooter":2,"row":4,"col":4,"hit":true,"sunkShip":null}
    [Server]         →  [Tab 2]    SHOT_RESULT {"type":"SHOT_RESULT","shooter":2,"row":4,"col":4,"hit":true,"sunkShip":null}
    [Server]         →  [Tab 1]    TURN_CHANGE {"type":"TURN_CHANGE","turn":1}
    [Server]         →  [Tab 2]    TURN_CHANGE {"type":"TURN_CHANGE","turn":1}
    [Tab 1]          →  [Server]   FIRE {"type":"FIRE","row":1,"col":2}
    [Server]         →  [Tab 1]    SHOT_RESULT {"type":"SHOT_RESULT","shooter":1,"row":1,"col":2,"hit":true,"sunkShip":"Destroyer"}
    [Server]         →  [Tab 2]    SHOT_RESULT {"type":"SHOT_RESULT","shooter":1,"row":1,"col":2,"hit":true,"sunkShip":"Destroyer"}
    [Server]         →  [Tab 1]    GAME_OVER {"type":"GAME_OVER","winner":1,"finalBoard":[[0,1,2...]]}
    [Server]         →  [Tab 2]    GAME_OVER {"type":"GAME_OVER","winner":1,"finalBoard":[[0,1,2...]]}

### 2.2 — Any message types you added or changed

No changes. I followed the spec exactly.

---

## Section 3 — Concurrency

Answer each question in 3–6 sentences.  Reference specific class and method names from your own code.

**3.1 — How does your implementation ensure that a CHAT message sent by player 2 is never delayed by a FIRE message being processed for player 1?**

In GameServer.java, right at the top of handleMessage, I check if ("CHAT".equals(msg.type)) *before* the code ever hits the synchronized(this) block. If it's a chat, it calls broadcast() right away and dumps the JSON into both clients' outbox queues. If I had put that check inside the lock, a slow FIRE calculation from Player 1 would make Player 2's chat message wait in line at the monitor boundary, which breaks the real-time chat requirement.

---

**3.2 — Two ClientHandler threads call handleMessage concurrently. What shared state could be corrupted without synchronization, and what is the specific failure mode?**

The readyCount and the boards arrays are the main vulnerable shared states. If both ClientHandlers process a "READY" message at the exact same time without a lock, Thread 1 and Thread 2 might both read readyCount as 0. They'd both bump it to 1 and save it. Then the if (readyCount < 2) check would end up being true for both threads. Because of that, GAME_START never actually gets sent, and both players are just stuck on the waiting screen in a deadlock forever.

---

**3.3 — How do all five threads exit cleanly when the game ends?**

When handleFire sees a player is out of ships (getShipsAfloat() == 0), it triggers shutdown(). That method calls shutdown() on the two WriterThreads, which tosses a "POISON_PILL" string into their outbox queues. That unblocks outbox.take(), breaks the writing loop, and lets those threads die normally. Right after that, the main shutdown explicitly closes both sockets (socket[0].close()). Closing those sockets makes the blocking WebSocketUtil.readFrame(in) in the ClientHandlers throw an IOException, which breaks their read loops so they can finish up cleanly too.

---

## Section 4 — Bugs

If you genuinely only hit one bug, describe a second plausible one — but only one you could actually imagine hitting, not one you invented from thin air. Graders can tell the difference.

### 4.A — Premature WebSocket Write

*Symptom:* 
Loading the lobby instantly disconnected the browser, and the dev tools threw an error about a corrupted or invalid WebSocket frame.

*Cause:* 
I was calling server.sendAssign(playerNum) before the WebSocketUtil.handshake method actually finished. So the server was jamming the ASSIGN JSON frame into the output stream while the HTTP 101 Switching Protocols response was still trying to flush out.

*Fix:* 
I just moved server.sendAssign(playerNum) down so it runs explicitly after the handshake call in the ClientHandler's run loop.

*Lesson:* 
You can't mix raw HTTP handshake bytes with the actual WebSocket message frames on the same buffer.

---

### 4.B — Stale Closure on Turn State

*Symptom:* 
The clients connected and the game started fine, but when a TURN_CHANGE message came in, the UI turn indicator wouldn't update and clicking the board did nothing.

*Cause:* 
In my React code, the ws.onmessage callback grabbed the initial state of playerNumber (which was null) and held onto it. Because of the closure, checking msg.turn === playerNumber always failed.

*Fix:* 
I added a useRef to track the player number and synced it up with a useEffect. Then I swapped out the direct state read in the callback for playerNumberRef.current.

*Lesson:* 
React callbacks that only initialize once (like in an empty useEffect dependency array) need refs to look at dynamic state, otherwise they just see stale data.

---

## Section 5 — Testing

Describe what you actually ran.  "I tested it and it worked" earns no credit.

**5.1 — How did you verify the server before building the frontend?**

Name the tool and the specific JSON messages you sent.  What did you check in the server's output to confirm each step was working?

I opened two terminals and ran wscat -c ws://localhost:8080. I checked that the first one got ASSIGN and WAITING, and the second got ASSIGN. Then I typed in {"type":"READY"} on both and made sure the server spit back the full GAME_START JSON object with the right 2D array syntax. 

---

**5.2 — How did you verify turn enforcement?**

Describe the exact test: which player sent FIRE out of turn, what JSON did you send, and what did the server return?

I used the two wscat terminals to get a game going. When the server said turn: 1, I went to Player 2's terminal and intentionally tried to cheat by typing {"type":"FIRE","row":0,"col":0}. The server instantly rejected it with an ERROR message and didn't broadcast any TURN_CHANGE, so I knew the turn state was locked down.

---

**5.3 — How did you verify that CHAT is not blocked by concurrent FIRE processing?**

This is hard to test with two browser tabs.  Describe the specific setup you used — did you add an artificial delay to FIRE processing, use two wscat terminals, or something else?

I stuck a Thread.sleep(5000) inside the handleFire synchronized block just to fake a slow server. Using wscat, I sent a FIRE from Player 1, then immediately sent a CHAT from Player 2. The chat message popped up on both terminals instantly, but the SHOT_RESULT from the FIRE didn't show up until 5 seconds later. (I took the sleep out before submitting).

---

**5.4 — How did you test disconnect handling?**

What did you close (the tab, the terminal, the socket), when did you close it (before READY, during play, after game over), and what did the surviving client receive?

I started a normal game in two browser tabs. On Player 1's turn, I just closed Player 2's tab completely. Player 1's tab immediately got the OPPONENT_DISCONNECTED payload and updated the UI to show the error on the Game Over screen. I also checked the backend console to confirm "Server shutting down" printed out, so I knew the threads actually shut down.

---

**5.5 — Browser and OS**

List the browser(s) and OS(es) you tested on.

Two Chrome Browsers on MacOS

---

## Section 6 — Reflection (3–5 sentences, answer at least two)

- What was the hardest ordering constraint to get right, and how did you figure it out?
The hardest part to get right was making sure GAME_START only fired exactly once when the second player clicked ready. If I wasn't careful with the locks there, it was super easy to initialize the Board objects twice and wipe out the memory references before the first player even loaded in. 

- What does this lab teach about the difference between correctness and performance in concurrent systems?
This lab really hammered home the difference between writing correct code and fast code; locking down shared memory (like the turn state) usually slows things down. Just like with pipelining in computer architecture, pulling out the independent data paths (like extracting CHAT messages from the main game loop) is the only way to keep things from getting unnecessarily bottlenecked.

---

## Section 7 — Time Log

| Date | Hours | What you worked on |
|------|-------|--------------------|
| 04/09 | 2.0 | Skeleton setup, wscat testing, and HTTP handshake |
| 04/10 | 3.5 | Backend threading: ClientHandler and WriterThread logic |
| 04/10 | 3.0 | GameServer logic, turn enforcement, and JSON parsing without libraries |
| 04/11 | 2.5 | Frontend WebSocket setup, React state design, stale closure fix |
| 04/12 | 3.0 | React UI rendering (Boards, Fleet Status, Chat) |
| 04/13 | 1.0 | Edge cases, disconnect handling, testing, and writeup |

**Total hours:** 15.0

**Approximate split between backend and frontend:** 60% / 40%
