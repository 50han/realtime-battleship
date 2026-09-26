// Concurrency check: a burst of players all queue at the same instant, spread
// across two instances. Every player must end up in exactly one match, and no
// player may be paired twice — the property the atomic Lua pairing guarantees.
const ENDPOINTS = [
  { api: "http://localhost:8080", ws: "ws://localhost:8080/ws" },
  { api: "http://localhost:8081", ws: "ws://localhost:8081/ws" },
];
const PLAYERS = 40;
const tag = "ld" + Date.now().toString(36);

const fail = (m) => { console.error("FAIL:", m); process.exit(1); };
const ok   = (m) => console.log("  ok:", m);

const readStats = () =>
  Promise.all(ENDPOINTS.map((e) => fetch(e.api + "/api/stats").then((r) => r.json())));

class C {
  constructor(i) {
    this.name = tag + "_" + i;
    this.ep = ENDPOINTS[i % ENDPOINTS.length];
    this.matchFounds = [];
    this.errors = [];
  }
  async signup() {
    const r = await fetch(this.ep.api + "/api/auth/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: this.name, password: "password123" }),
    });
    if (r.status !== 201) fail("register " + this.name + ": " + r.status);
    const a = await r.json();
    this.token = a.token;
    this.userId = a.user.id;
  }
  connect() {
    return new Promise((res, rej) => {
      this.ws = new WebSocket(this.ep.ws + "?token=" + encodeURIComponent(this.token));
      this.ws.onopen = res;
      this.ws.onerror = () => rej(new Error("ws failed " + this.name));
      this.ws.onmessage = (e) => {
        const m = JSON.parse(e.data);
        if (m.type === "HELLO") this.hello = m;
        if (m.type === "MATCH_FOUND") this.matchFounds.push(m);
        if (m.type === "ERROR") this.errors.push(m);
      };
    });
  }
}

(async () => {
  console.log("Registering and connecting " + PLAYERS + " players across "
    + ENDPOINTS.length + " instances...");
  const clients = Array.from({ length: PLAYERS }, (_, i) => new C(i));
  await Promise.all(clients.map((c) => c.signup()));
  await Promise.all(clients.map((c) => c.connect()));
  await new Promise((r) => setTimeout(r, 500));
  if (clients.some((c) => !c.hello)) fail("not every client received HELLO");
  ok(PLAYERS + " players connected");

  // Earlier tests may leave matches inside their reconnect grace window, so
  // the live-match assertion below is on the delta this burst caused.
  const liveBefore = (await readStats()).reduce((n, s) => n + s.liveMatches, 0);

  const started = Date.now();
  // No stagger at all: every QUEUE_JOIN goes out in the same tick.
  clients.forEach((c) => c.ws.send(JSON.stringify({ type: "QUEUE_JOIN" })));

  const deadline = Date.now() + 30000;
  while (Date.now() < deadline &&
         clients.filter((c) => c.matchFounds.length > 0).length < PLAYERS) {
    await new Promise((r) => setTimeout(r, 50));
  }
  const elapsed = Date.now() - started;

  const matched = clients.filter((c) => c.matchFounds.length > 0);
  if (matched.length !== PLAYERS) {
    fail("only " + matched.length + "/" + PLAYERS + " players were matched within 30s");
  }
  ok("all " + PLAYERS + " players matched in " + elapsed + "ms ("
    + (elapsed / (PLAYERS / 2)).toFixed(1) + "ms per pairing)");

  const doubled = clients.filter((c) => new Set(c.matchFounds.map((m) => m.matchId)).size > 1);
  if (doubled.length) fail(doubled.length + " player(s) were paired into more than one match");
  ok("no player was paired into more than one match");

  const byMatch = new Map();
  for (const c of clients) {
    const id = c.matchFounds[0].matchId;
    if (!byMatch.has(id)) byMatch.set(id, []);
    byMatch.get(id).push(c);
  }
  if (byMatch.size !== PLAYERS / 2) {
    fail("expected " + PLAYERS / 2 + " matches, got " + byMatch.size);
  }
  for (const [id, members] of byMatch) {
    if (members.length !== 2) fail("match " + id + " has " + members.length + " players");
    const slots = members.map((m) => m.matchFounds[0].playerSlot).sort();
    if (slots[0] !== 1 || slots[1] !== 2) fail("match " + id + " has slots " + slots);
    const claimed = members.map((m) => m.matchFounds[0].opponent.userId).sort();
    const actual = members.map((m) => m.userId).sort();
    if (JSON.stringify(claimed) !== JSON.stringify(actual)) {
      fail("match " + id + " reports opponents that are not its members");
    }
  }
  ok("exactly " + byMatch.size + " matches, each with two distinct players on opposite slots");

  const unexpected = clients.flatMap((c) => c.errors).filter((e) => e.code !== "RATE_LIMITED");
  if (unexpected.length) {
    fail("unexpected errors: " + JSON.stringify(unexpected.slice(0, 3)));
  }
  ok("no unexpected protocol errors under the burst");

  const stats = await readStats();
  if (stats[0].queueSize !== 0) fail("queue did not drain: " + stats[0].queueSize + " left");
  const liveDelta = stats.reduce((n, s) => n + s.liveMatches, 0) - liveBefore;
  if (liveDelta !== PLAYERS / 2) {
    fail("instances gained " + liveDelta + " live matches, expected " + PLAYERS / 2);
  }
  ok("queue fully drained; "
    + stats.map((s) => s.instance + "=" + s.liveMatches).join(", ") + " live matches");

  clients.forEach((c) => { try { c.ws.close(); } catch {} });
  console.log("\nCONCURRENT MATCHMAKING LOAD TEST PASSED");
  process.exit(0);
})().catch((e) => { console.error("FAIL:", e.message); process.exit(1); });
