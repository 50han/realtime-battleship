// Two players on two different server instances must still be paired, and the
// one connected to the non-owning instance must be redirected to the owner.
const A_API = "http://localhost:8080", A_WS = "ws://localhost:8080/ws";
const B_API = "http://localhost:8081", B_WS = "ws://localhost:8081/ws";
const tag = "mi" + Date.now().toString(36);

const fail = (m) => { console.error("FAIL:", m); process.exit(1); };
const ok   = (m) => console.log("  ok:", m);

async function reg(api, username) {
  const r = await fetch(api + "/api/auth/register", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password: "password123" }),
  });
  if (r.status !== 201) fail(`register on ${api}: ${r.status}`);
  return r.json();
}

class C {
  constructor(name, api, ws) { this.name = name; this.api = api; this.wsUrl = ws; this.msgs = []; this.waiters = []; }
  async signup() { const a = await reg(this.api, this.name); this.token = a.token; this.userId = a.user.id; }
  connect(url = this.wsUrl) {
    return new Promise((res, rej) => {
      this.ws = new WebSocket(`${url}?token=${encodeURIComponent(this.token)}`);
      this.ws.onopen = res;
      this.ws.onerror = () => rej(new Error("ws failed: " + url));
      this.ws.onmessage = (e) => {
        const m = JSON.parse(e.data);
        if (m.type === "ERROR") {
          console.log("    [" + this.name + "] ERROR " + m.code + ": " + m.message);
        }
        this.msgs.push(m);
        this.waiters = this.waiters.filter(w => w.type === m.type ? (w.resolve(m), false) : true);
      };
    });
  }
  send(o) { this.ws.send(JSON.stringify(o)); }
  await(type, ms = 15000) {
    const f = this.msgs.find(m => m.type === type);
    if (f) return Promise.resolve(f);
    return new Promise((res, rej) => {
      this.waiters.push({ type, resolve: res });
      setTimeout(() => rej(new Error(`${this.name} timed out on ${type}; saw [${this.msgs.map(m=>m.type)}]`)), ms);
    });
  }
}

(async () => {
  const a = new C("mi_a_" + tag, A_API, A_WS);
  const b = new C("mi_b_" + tag, B_API, B_WS);

  // A JWT minted by instance 1 must be accepted by instance 2: verification is
  // stateless, which is the whole point of using JWT over server-side sessions.
  await a.signup();
  const crossCheck = await fetch(B_API + "/api/me", {
    headers: { Authorization: "Bearer " + a.token },
  });
  if (crossCheck.status !== 200) fail("instance 2 rejected a token minted by instance 1");
  ok("a JWT issued by instance 1 is accepted by instance 2 (stateless auth)");

  await b.signup();
  await a.connect(); await b.connect();
  const helloA = await a.await("HELLO");
  const helloB = await b.await("HELLO");
  if (helloA.instanceId === helloB.instanceId) fail("both clients landed on the same instance");
  ok(`players are on different instances (${helloA.instanceId} / ${helloB.instanceId})`);

  a.send({ type: "QUEUE_JOIN" });
  await a.await("QUEUE_JOINED");
  b.send({ type: "QUEUE_JOIN" });
  await b.await("QUEUE_JOINED");

  const mfA = await a.await("MATCH_FOUND");
  const mfB = await b.await("MATCH_FOUND");
  if (mfA.matchId !== mfB.matchId) fail("cross-instance pairing produced different match ids");
  ok(`paired across instances into match ${mfA.matchId}`);

  const redirected = [mfA, mfB].filter(m => m.reconnectRequired);
  if (redirected.length !== 1) {
    fail(`expected exactly one player to be redirected, got ${redirected.length}`);
  }
  const [local, remote] = mfA.reconnectRequired ? [b, a] : [a, b];
  const remoteMf = mfA.reconnectRequired ? mfA : mfB;
  const localMf  = mfA.reconnectRequired ? mfB : mfA;
  if (localMf.reconnectRequired) fail("the owner's own player should not be redirected");
  if (!remoteMf.serverUrl) fail("the redirected player was not told where to go");
  ok(`the non-owner's player is redirected to ${remoteMf.serverUrl}`);

  // The player on the owning instance gets straight into the game.
  const gsLocal = await local.await("GAME_START");
  if (gsLocal.myBoard.flat().filter(c => c === 1).length !== 17) fail("owner-side board wrong");
  ok("the player on the owning instance receives GAME_START directly");

  // The redirected player reconnects to the owner and joins the same match.
  remote.msgs = [];
  remote.ws.close();
  await new Promise(r => setTimeout(r, 300));
  await remote.connect(remoteMf.serverUrl);
  await remote.await("HELLO");
  const gsRemote = await remote.await("GAME_START");
  if (gsRemote.matchId !== mfA.matchId) fail("redirected player joined the wrong match");
  if (gsRemote.myBoard.flat().filter(c => c === 1).length !== 17) fail("redirected board wrong");
  if (gsRemote.playerSlot === gsLocal.playerSlot) fail("both players got the same slot");
  ok("the redirected player reconnects to the owner and joins the same match");

  // And the game actually plays across that boundary.
  const bySlot = {}; bySlot[gsLocal.playerSlot] = local; bySlot[gsRemote.playerSlot] = remote;
  bySlot[1].send({ type: "CHAT", text: "hello from the other instance" });
  const chat = await bySlot[2].await("CHAT");
  if (!chat.text.includes("other instance")) fail("chat did not cross");
  bySlot[1].send({ type: "FIRE", row: 0, col: 0 });
  const sr = await bySlot[2].await("SHOT_RESULT");
  if (sr.shooter !== 1) fail("shot attributed to the wrong slot");
  await bySlot[2].await("TURN_CHANGE");
  ok("chat and shots flow normally once both players are on the owning instance");

  [a, b].forEach(c => { try { c.ws.close(); } catch {} });
  console.log("\nMULTI-INSTANCE MATCHMAKING PASSED");
  process.exit(0);
})().catch(e => { console.error("FAIL:", e.message); process.exit(1); });
