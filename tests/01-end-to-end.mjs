// End-to-end check: register, login, matchmake, play a full game, read history.
const API = "http://localhost:8080";
const WS  = "ws://localhost:8080/ws";
const tag = Date.now().toString(36);

const fail = (m) => { console.error("FAIL:", m); process.exit(1); };
const ok   = (m) => console.log("  ok:", m);

async function post(path, body) {
  const r = await fetch(API + path, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return { status: r.status, body: await r.json() };
}
async function get(path, token) {
  const r = await fetch(API + path, {
    headers: token ? { Authorization: "Bearer " + token } : {},
  });
  return { status: r.status, body: await r.json() };
}

class Client {
  constructor(name) { this.name = name; this.msgs = []; this.waiters = []; }
  async signup() {
    const r = await post("/api/auth/register", { username: this.name, password: "password123" });
    if (r.status !== 201) fail(`register ${this.name}: ${r.status} ${JSON.stringify(r.body)}`);
    this.token = r.body.token; this.userId = r.body.user.id;
  }
  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(`${WS}?token=${encodeURIComponent(this.token)}`);
      this.ws.onmessage = (e) => {
        const m = JSON.parse(e.data);
        this.msgs.push(m);
        this.waiters = this.waiters.filter(w => { if (w.type === m.type) { w.resolve(m); return false; } return true; });
      };
      this.ws.onopen = () => resolve();
      this.ws.onerror = (e) => reject(new Error("ws error " + this.name));
    });
  }
  send(o) { this.ws.send(JSON.stringify(o)); }
  await(type, ms = 15000) {
    const found = this.msgs.find(m => m.type === type);
    if (found) return Promise.resolve(found);
    return new Promise((resolve, reject) => {
      const w = { type, resolve };
      this.waiters.push(w);
      setTimeout(() => reject(new Error(`${this.name} timed out waiting for ${type}; saw ${[...new Set(this.msgs.map(m=>m.type))].join(",")}`)), ms);
    });
  }
  clear(type) { this.msgs = this.msgs.filter(m => m.type !== type); }
}

(async () => {
  console.log("1. REST auth");
  const a = new Client("alice_" + tag);
  const b = new Client("bob_" + tag);
  await a.signup(); await b.signup();
  ok(`registered ${a.name} (#${a.userId}) and ${b.name} (#${b.userId})`);

  const dup = await post("/api/auth/register", { username: a.name, password: "password123" });
  if (dup.status !== 409) fail("duplicate username should be 409, got " + dup.status);
  ok("duplicate username rejected with 409");

  const weak = await post("/api/auth/register", { username: "u_" + tag, password: "short" });
  if (weak.status !== 400) fail("weak password should be 400, got " + weak.status);
  ok("weak password rejected with 400");

  const bad = await post("/api/auth/login", { username: a.name, password: "wrongpassword" });
  if (bad.status !== 401) fail("bad login should be 401, got " + bad.status);
  ok("wrong password rejected with 401");

  const relogin = await post("/api/auth/login", { username: a.name, password: "password123" });
  if (relogin.status !== 200 || !relogin.body.token) fail("login failed");
  ok("login returns a fresh JWT");

  const noAuth = await get("/api/me");
  if (noAuth.status !== 401) fail("/api/me without token should be 401, got " + noAuth.status);
  const me = await get("/api/me", a.token);
  if (me.status !== 200 || me.body.username !== a.name) fail("/api/me wrong: " + JSON.stringify(me.body));
  ok("/api/me is JWT-protected and returns the right account");

  console.log("2. WebSocket auth");
  const badWs = new WebSocket(`${WS}?token=garbage`);
  await new Promise(r => { badWs.onerror = () => r(); badWs.onclose = () => r(); setTimeout(r, 3000); });
  ok("WebSocket with an invalid token is refused");

  await a.connect(); await b.connect();
  const helloA = await a.await("HELLO");
  await b.await("HELLO");
  if (helloA.userId !== a.userId) fail("HELLO carried the wrong user");
  ok("both clients authenticated over WebSocket; HELLO received");

  console.log("3. Redis matchmaking");
  a.send({ type: "QUEUE_JOIN" });
  const joined = await a.await("QUEUE_JOINED");
  if (joined.queueSize < 1) fail("queueSize should be >= 1");
  ok(`alice queued (queueSize=${joined.queueSize})`);

  a.send({ type: "QUEUE_JOIN" });
  const dupQueue = await a.await("ERROR");
  if (dupQueue.code !== "ALREADY_QUEUED") fail("second QUEUE_JOIN should be ALREADY_QUEUED, got " + dupQueue.code);
  ok("double-queueing rejected atomically by the Lua script");
  a.clear("ERROR");

  b.send({ type: "QUEUE_JOIN" });
  const mfA = await a.await("MATCH_FOUND");
  const mfB = await b.await("MATCH_FOUND");
  if (mfA.matchId !== mfB.matchId) fail("players got different match ids");
  if (mfA.playerSlot === mfB.playerSlot) fail("both players got the same slot");
  ok(`paired into match ${mfA.matchId} (slots ${mfA.playerSlot}/${mfB.playerSlot})`);

  const gsA = await a.await("GAME_START");
  const gsB = await b.await("GAME_START");
  const shipCells = (g) => g.myBoard.flat().filter(c => c === 1).length;
  if (shipCells(gsA) !== 17 || shipCells(gsB) !== 17) fail("expected 17 ship cells per board");
  if (gsA.turn !== 1 || gsB.turn !== 1) fail("player 1 should move first");
  ok("GAME_START delivered with 17 ship cells each; player 1 to move");

  const bySlot = {}; bySlot[mfA.playerSlot] = a; bySlot[mfB.playerSlot] = b;
  const boards = {}; boards[mfA.playerSlot] = gsA.myBoard; boards[mfB.playerSlot] = gsB.myBoard;

  console.log("4. Turn enforcement and hidden information");
  bySlot[2].send({ type: "FIRE", row: 0, col: 0 });
  const oot = await bySlot[2].await("ERROR");
  if (oot.code !== "NOT_YOUR_TURN") fail("out-of-turn fire should be NOT_YOUR_TURN, got " + oot.code);
  ok("firing out of turn rejected");
  bySlot[2].clear("ERROR");

  bySlot[1].send({ type: "FIRE", row: 99, col: 0 });
  const oob = await bySlot[1].await("ERROR");
  if (oob.code !== "OUT_OF_BOUNDS") fail("expected OUT_OF_BOUNDS, got " + oob.code);
  ok("off-board coordinates rejected");
  bySlot[1].clear("ERROR");

  console.log("5. Chat");
  bySlot[1].send({ type: "CHAT", text: 'quotes " and \\ backslash and newline\nsurvive' });
  const chat = await bySlot[2].await("CHAT");
  if (!chat.text.includes('quotes " and')) fail("chat text mangled: " + JSON.stringify(chat.text));
  ok("chat with JSON metacharacters round-tripped intact");

  console.log("6. Full game to a winner");
  // Slot 1 fires only at slot 2's real ship cells; slot 2 always misses.
  const targets = [];
  boards[2].forEach((row, r) => row.forEach((c, ci) => { if (c === 1) targets.push([r, ci]); }));
  const waterForTwo = [];
  boards[1].forEach((row, r) => row.forEach((c, ci) => { if (c === 0) waterForTwo.push([r, ci]); }));

  let sunkAnnouncements = 0, hits = 0, filler = 0, over = null;
  for (const [r, c] of targets) {
    const before = bySlot[1].msgs.length;
    bySlot[1].send({ type: "FIRE", row: r, col: c });
    const sr = await new Promise((res, rej) => {
      const t = setInterval(() => {
        const m = bySlot[1].msgs.slice(before).find(m => m.type === "SHOT_RESULT");
        if (m) { clearInterval(t); res(m); }
      }, 10);
      setTimeout(() => { clearInterval(t); rej(new Error("no SHOT_RESULT")); }, 10000);
    });
    if (!sr.hit) fail(`shot at (${r},${c}) should have hit a known ship cell`);
    hits++;
    if (sr.sunkShip) sunkAnnouncements++;
    if (bySlot[1].msgs.some(m => m.type === "GAME_OVER")) { over = true; break; }
    const [wr, wc] = waterForTwo[filler++];
    bySlot[2].send({ type: "FIRE", row: wr, col: wc });
    await new Promise(res => setTimeout(res, 40));
  }
  if (hits !== 17) fail("expected 17 hits, got " + hits);
  if (sunkAnnouncements !== 5) fail("expected 5 sink announcements, got " + sunkAnnouncements);
  ok(`17 hits, all 5 ships announced sunk`);

  const goWinner = await bySlot[1].await("GAME_OVER");
  const goLoser  = await bySlot[2].await("GAME_OVER");
  if (goWinner.winner !== 1) fail("slot 1 should have won");
  if (goWinner.reason !== "FLEET_DESTROYED") fail("reason should be FLEET_DESTROYED, got " + goWinner.reason);
  if (goLoser.matchId !== goWinner.matchId) fail("GAME_OVER match id mismatch");
  ok(`GAME_OVER: winner slot 1, reason ${goWinner.reason}, ${goWinner.totalShots} total shots`);

  console.log("7. PostgreSQL persistence");
  await new Promise(res => setTimeout(res, 1500)); // let the async writers drain
  const winner = bySlot[1], loser = bySlot[2];
  const hist = await get("/api/matches", winner.token);
  if (hist.status !== 200 || hist.body.matches.length !== 1) fail("history: " + JSON.stringify(hist.body));
  const rec = hist.body.matches[0];
  if (rec.result !== "WIN" || rec.status !== "COMPLETED") fail("history row wrong: " + JSON.stringify(rec));
  ok(`match history persisted: ${rec.result}, ${rec.status}, vs ${rec.opponentName}`);

  const loserHist = await get("/api/matches", loser.token);
  if (loserHist.body.matches[0].result !== "LOSS") fail("loser should see a LOSS");
  ok("the same match reads as a LOSS for the other player");

  const detail = await get("/api/matches/" + rec.matchId, winner.token);
  if (detail.status !== 200) fail("match detail: " + detail.status);
  if (detail.body.moves.length !== goWinner.totalShots) {
    fail(`move log has ${detail.body.moves.length} rows, expected ${goWinner.totalShots}`);
  }
  const nos = detail.body.moves.map(m => m.moveNo);
  if (new Set(nos).size !== nos.length) fail("duplicate move numbers persisted");
  if (nos.some((n, i) => n !== i + 1)) fail("move numbers not contiguous: " + nos.join(","));
  ok(`every one of the ${nos.length} moves batch-persisted, in order, no duplicates`);

  const foreign = await get("/api/matches/" + rec.matchId, (await (async () => {
    const c = new Client("carol_" + tag); await c.signup(); return c;
  })()).token);
  if (foreign.status !== 403) fail("a third party should get 403, got " + foreign.status);
  ok("match detail is restricted to participants (403)");

  // limit=100: with accumulated test accounts a fresh 0-1 player will not be
  // in the top 10, and this assertion is about the counters, not the ranking.
  const lb = await get("/api/leaderboard?limit=100");
  const winRow = lb.body.leaderboard.find(r => r.userId === winner.userId);
  const loseRow = lb.body.leaderboard.find(r => r.userId === loser.userId);
  if (!winRow || winRow.wins !== 1 || winRow.losses !== 0) fail("winner row wrong: " + JSON.stringify(winRow));
  if (!loseRow || loseRow.wins !== 0 || loseRow.losses !== 1) fail("loser row wrong: " + JSON.stringify(loseRow));
  ok(`leaderboard updated: winner 1-0, loser 0-1`);

  console.log("8. Mid-match reconnect");
  const c = new Client("dave_" + tag), d = new Client("erin_" + tag);
  await c.signup(); await d.signup();
  await c.connect(); await d.connect();
  await c.await("HELLO"); await d.await("HELLO");
  c.send({ type: "QUEUE_JOIN" }); await c.await("QUEUE_JOINED");
  d.send({ type: "QUEUE_JOIN" });
  const mfC = await c.await("MATCH_FOUND");
  const gsC = await c.await("GAME_START");
  await d.await("GAME_START");
  const first = mfC.playerSlot === 1 ? c : d;
  const second = first === c ? d : c;
  first.send({ type: "FIRE", row: 0, col: 0 });
  await second.await("SHOT_RESULT");
  ok("second match under way");

  c.ws.close();
  const oppGone = await d.await("OPPONENT_DISCONNECTED");
  if (!(oppGone.graceSeconds > 0)) fail("expected a positive reconnect grace period");
  ok(`opponent disconnect signalled with a ${oppGone.graceSeconds}s grace period, match not ended`);

  const c2 = new Client(c.name); c2.token = c.token; c2.userId = c.userId;
  await c2.connect();
  const hello2 = await c2.await("HELLO");
  if (!hello2.hasActiveMatch) fail("HELLO should report an active match after reconnect");
  const resume = await c2.await("GAME_RESUME");
  if (resume.matchId !== mfC.matchId) fail("resumed the wrong match");
  if (resume.myBoard.flat().filter(x => x === 1 || x === 2).length !== 17) {
    fail("resumed board lost ship cells");
  }
  if (!resume.chatLog) fail("resume should carry the chat backlog");
  await d.await("OPPONENT_RECONNECTED");
  ok(`reconnected from Redis snapshot into match ${resume.matchId}, opponent notified`);

  // c's original socket was deliberately closed above; forfeit from d, whose
  // connection is still the live one, and check the reconnected client sees it.
  d.send({ type: "FORFEIT" });
  const ff = await c2.await("GAME_OVER");
  if (ff.reason !== "FORFEIT") fail("expected FORFEIT, got " + ff.reason);
  const dWinnerSlot = mfC.playerSlot === 1 ? 1 : 2;
  if (ff.winner !== dWinnerSlot) fail(`forfeit should hand the win to slot ${dWinnerSlot}, got ${ff.winner}`);
  ok("forfeit ends the match and awards the win to the opponent");

  console.log("9. Server stats");
  const stats = await get("/api/stats");
  if (typeof stats.body.liveConnections !== "number") fail("stats missing liveConnections");
  ok(`stats: ${stats.body.liveConnections} connections, ${stats.body.liveMatches} live matches, queue ${stats.body.queueSize}`);

  [a, b, c2, d].forEach(x => { try { x.ws.close(); } catch {} });
  console.log("\nALL END-TO-END CHECKS PASSED");
  process.exit(0);
})().catch(e => { console.error("FAIL:", e.message); process.exit(1); });
