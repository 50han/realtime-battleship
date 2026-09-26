// Protocol conformance: play a real game against the running server and feed
// every message through the actual client reducer. Any field the client reads
// but the server does not send shows up here as a broken state transition.

import { reducer, initialState } from "../frontend/src/hooks/useGameSocket.js";

const API = "http://localhost:8080";
const WS  = "ws://localhost:8080/ws";
const tag = "pc" + Date.now().toString(36);

const fail = (m) => { console.error("FAIL:", m); process.exit(1); };
const ok   = (m) => console.log("  ok:", m);

async function register(username) {
  const r = await fetch(API + "/api/auth/register", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password: "password123" }),
  });
  if (r.status !== 201) fail("register " + username + ": " + r.status);
  return r.json();
}

// A client that maintains its state exactly the way the React hook does.
class Peer {
  constructor(name) {
    this.name = name;
    this.state = initialState;
    this.seen = new Set();
    this.pending = [];
  }
  async open() {
    const auth = await register(this.name);
    this.token = auth.token;
    this.userId = auth.user.id;
    await new Promise((resolve, reject) => {
      this.ws = new WebSocket(`${WS}?token=${encodeURIComponent(this.token)}`);
      this.ws.onopen = resolve;
      this.ws.onerror = () => reject(new Error("ws failed for " + this.name));
      this.ws.onmessage = (e) => {
        const msg = JSON.parse(e.data);
        this.seen.add(msg.type);
        const before = this.state;
        this.state = reducer(this.state, { type: msg.type, msg });
        if (this.state === before && !["PONG", "MATCH_FOUND"].includes(msg.type)) {
          fail(`${this.name}: reducer ignored a ${msg.type} the server sent`);
        }
        this.pending = this.pending.filter((w) =>
          w.check(this.state, msg) ? (w.resolve(msg), false) : true);
      };
    });
  }
  send(o) { this.ws.send(JSON.stringify(o)); }
  until(label, check, ms = 15000) {
    if (check(this.state, null)) return Promise.resolve(null);
    return new Promise((resolve, reject) => {
      this.pending.push({ check, resolve });
      setTimeout(() => reject(new Error(
        `${this.name} timed out waiting for ${label}; saw [${[...this.seen]}]`)), ms);
    });
  }
}

(async () => {
  const a = new Peer("pa_" + tag);
  const b = new Peer("pb_" + tag);
  await a.open(); await b.open();

  await a.until("HELLO", (s) => s.me !== null);
  await b.until("HELLO", (s) => s.me !== null);
  if (a.state.me.username !== a.name) fail("HELLO did not populate me.username");
  if (!a.state.instanceId) fail("HELLO did not populate instanceId");
  if (a.state.phase !== "lobby") fail("expected lobby phase after HELLO");
  ok("HELLO populates me, instanceId, and leaves the client in the lobby");

  a.send({ type: "QUEUE_JOIN" });
  await a.until("queued", (s) => s.phase === "queued");
  if (!(a.state.queue.size >= 1)) fail("queue.size not populated from QUEUE_JOINED");
  if (!a.state.queue.since) fail("queue.since not populated (the lobby timer needs it)");
  ok("QUEUE_JOINED drives the queued phase, size and wait timer");

  b.send({ type: "QUEUE_JOIN" });
  await a.until("playing", (s) => s.phase === "playing" && s.turn !== null);
  await b.until("playing", (s) => s.phase === "playing" && s.turn !== null);

  for (const p of [a, b]) {
    if (!p.state.match?.matchId) fail(p.name + ": match.matchId missing");
    if (![1, 2].includes(p.state.match.playerSlot)) fail(p.name + ": bad playerSlot");
    if (!p.state.match.opponentName) fail(p.name + ": opponentName missing");
    if (p.state.myBoard.flat().filter((c) => c === 1).length !== 17) {
      fail(p.name + ": myBoard should show 17 ship cells");
    }
    if (p.state.opponentBoard.flat().some((c) => c !== 0)) {
      fail(p.name + ": opponentBoard must start fully unknown");
    }
    if (!p.state.turnDeadline || p.state.turnDeadline < Date.now()) {
      fail(p.name + ": turnDeadline missing or already past (turn timer would not render)");
    }
    if (p.state.myFleet.length !== 5 || p.state.opponentFleet.length !== 5) {
      fail(p.name + ": fleets not initialised");
    }
  }
  if (a.state.match.opponentName !== b.name) fail("opponentName mismatch: " + a.state.match.opponentName);
  ok("MATCH_FOUND + GAME_START populate match, boards, fleets and turn deadline");

  const attacker = a.state.match.playerSlot === 1 ? a : b;
  const defender = attacker === a ? b : a;

  // Chat first, to prove it needs no turn.
  attacker.send({ type: "CHAT", text: 'incoming — "brace"' });
  await defender.until("chat", (s) => s.chatLog.length === 1);
  const line = defender.state.chatLog[0];
  if (line.from !== attacker.name) fail("chat from wrong: " + line.from);
  if (line.text !== 'incoming — "brace"') fail("chat text mangled: " + line.text);
  if (!line.at) fail("chat message has no timestamp (Chat.jsx renders it)");
  ok("CHAT reaches the opponent with sender, text and timestamp intact");

  // Find a ship cell on the defender's board and hit it.
  const defenderShips = [];
  defender.state.myBoard.forEach((row, r) =>
    row.forEach((c, ci) => { if (c === 1) defenderShips.push([r, ci]); }));

  const [hr, hc] = defenderShips[0];
  attacker.send({ type: "FIRE", row: hr, col: hc });
  await attacker.until("hit recorded", (s) => s.opponentBoard[hr][hc] === 2);
  await defender.until("hit received", (s) => s.myBoard[hr][hc] === 2);
  if (!attacker.state.lastShot?.byMe) fail("lastShot.byMe wrong for the shooter");
  if (defender.state.lastShot?.byMe) fail("lastShot.byMe wrong for the defender");
  ok("SHOT_RESULT lands on the shooter's opponentBoard and the target's myBoard");

  await attacker.until("turn passed", (s) => s.turn === defender.state.match.playerSlot);
  ok("TURN_CHANGE hands the turn to the other player on both clients");

  // Sink the Destroyer (2 cells) and check the fleet panel updates.
  const shipsBySize = {};
  // Walk the whole board, sinking everything; assert each announced sink is
  // reflected in the fleet state the UI renders.
  let filler = 0;
  const water = [];
  attacker.state.myBoard.forEach((row, r) =>
    row.forEach((c, ci) => { if (c === 0) water.push([r, ci]); }));

  for (const [r, c] of defenderShips) {
    if (attacker.state.opponentBoard[r][c] !== 0) continue;
    // defender's turn first
    const [wr, wc] = water[filler++];
    defender.send({ type: "FIRE", row: wr, col: wc });
    await defender.until("filler shot", (s) => s.opponentBoard[wr][wc] !== 0);
    attacker.send({ type: "FIRE", row: r, col: c });
    await attacker.until("shot resolved",
      (s) => s.opponentBoard[r][c] !== 0 || s.phase === "over");
    if (attacker.state.phase === "over") break;
  }

  await attacker.until("game over", (s) => s.phase === "over");
  await defender.until("game over", (s) => s.phase === "over");

  const sunkCount = attacker.state.opponentFleet.filter((s) => s.sunk).length;
  if (sunkCount !== 5) fail(`fleet panel shows ${sunkCount}/5 sunk after a full clear`);
  ok("every sinking announcement is reflected in the enemy fleet panel");

  if (attacker.state.result.winner !== attacker.state.match.playerSlot) {
    fail("winner slot wrong for the attacker");
  }
  if (attacker.state.result.reason !== "FLEET_DESTROYED") {
    fail("result.reason wrong: " + attacker.state.result.reason);
  }
  if (!(attacker.state.result.totalShots > 0)) fail("result.totalShots missing");
  if (attacker.state.myBoard.flat().every((c) => c === 0)) fail("final boards not revealed");
  if (attacker.state.turnDeadline !== null) fail("turn timer should be cleared at game over");
  ok("GAME_OVER yields a complete result and reveals both final boards");

  // Reconnect: the reducer must restore a playable state from GAME_RESUME.
  const resumer = new Peer(attacker.name);
  resumer.token = attacker.token;
  const c = new Peer("pc_" + tag), d = new Peer("pd_" + tag);
  await c.open(); await d.open();
  await c.until("HELLO", (s) => s.me !== null);
  await d.until("HELLO", (s) => s.me !== null);
  c.send({ type: "QUEUE_JOIN" });
  await c.until("queued", (s) => s.phase === "queued");
  d.send({ type: "QUEUE_JOIN" });
  await c.until("playing", (s) => s.phase === "playing" && s.turn !== null);
  await d.until("playing", (s) => s.phase === "playing" && s.turn !== null);

  const first = c.state.match.playerSlot === 1 ? c : d;
  const other = first === c ? d : c;
  first.send({ type: "CHAT", text: "before the drop" });
  await other.until("chat", (s) => s.chatLog.length === 1);
  const beforeBoard = JSON.stringify(c.state.myBoard);
  const matchId = c.state.match.matchId;
  c.ws.close();

  await other.until("opponent gone", (s) => s.opponentConnected === false);
  if (!(other.state.graceSeconds > 0)) fail("graceSeconds not surfaced to the UI");
  if (other.state.phase !== "playing") fail("the match must stay playable during the grace period");
  ok("OPPONENT_DISCONNECTED shows a grace countdown without ending the match");

  const c2 = new Peer(c.name);
  c2.token = c.token;
  await new Promise((resolve, reject) => {
    c2.ws = new WebSocket(`${WS}?token=${encodeURIComponent(c2.token)}`);
    c2.ws.onopen = resolve;
    c2.ws.onerror = () => reject(new Error("reconnect failed"));
    c2.ws.onmessage = (e) => {
      const msg = JSON.parse(e.data);
      c2.seen.add(msg.type);
      c2.state = reducer(c2.state, { type: msg.type, msg });
      c2.pending = c2.pending.filter((w) =>
        w.check(c2.state, msg) ? (w.resolve(msg), false) : true);
    };
  });
  await c2.until("resumed", (s) => s.phase === "playing" && s.match?.matchId === matchId);
  if (JSON.stringify(c2.state.myBoard) !== beforeBoard) fail("resumed board differs from before the drop");
  if (c2.state.chatLog.length !== 1) fail("chat backlog not replayed on resume");
  if (c2.state.chatLog[0].text !== "before the drop") fail("replayed chat text wrong");
  if (!c2.state.turnDeadline) fail("resume did not supply a turn deadline");
  if (c2.state.notice?.code !== "RESUMED") fail("resume banner not set");
  ok("GAME_RESUME restores boards, chat backlog and turn clock byte-for-byte");

  [a, b, c2, d].forEach((p) => { try { p.ws.close(); } catch {} });
  console.log("\nCLIENT/SERVER PROTOCOL CONFORMANCE PASSED");
  process.exit(0);
})().catch((e) => { console.error("FAIL:", e.message); process.exit(1); });
