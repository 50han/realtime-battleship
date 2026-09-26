// Lobby: join the Redis matchmaking queue, and read your persisted record
// while you wait.

import { useEffect, useState } from "react";
import Leaderboard from "../components/Leaderboard";
import MatchHistory from "../components/MatchHistory";

function WaitTimer({ since }) {
  const [seconds, setSeconds] = useState(0);

  useEffect(() => {
    if (!since) return undefined;
    const tick = () => setSeconds(Math.max(0, Math.round((Date.now() - since) / 1000)));
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [since]);

  return <span>{seconds}s</span>;
}

export default function Lobby({ session, state, actions, refreshKey }) {
  const queued = state.phase === "queued";
  const connected = state.connection === "open";

  return (
    <div className="lobby-layout">
      <section className="card queue-card">
        <h2>Ready up</h2>
        <p className="muted">
          Matchmaking pairs the two longest-waiting players. The pairing itself
          runs as a single atomic Redis script, so two servers can never hand
          you the same opponent twice.
        </p>

        <dl className="stat-row">
          <div>
            <dt>In queue</dt>
            <dd>{state.queue.size ?? 0}</dd>
          </div>
          {state.queue.online != null && state.queue.online >= 0 && (
            <div>
              <dt>Online</dt>
              <dd>{state.queue.online}</dd>
            </div>
          )}
          <div>
            <dt>Record</dt>
            <dd>
              {session.user.wins}–{session.user.losses}
            </dd>
          </div>
        </dl>

        {!queued ? (
          <button className="primary" onClick={actions.joinQueue} disabled={!connected}>
            {connected ? "Find a match" : "Connecting…"}
          </button>
        ) : (
          <>
            <p className="searching">
              Searching for an opponent… <WaitTimer since={state.queue.since} />
            </p>
            <button className="secondary" onClick={actions.leaveQueue}>
              Leave queue
            </button>
          </>
        )}
      </section>

      <div className="lobby-panels">
        <MatchHistory token={session.token} refreshKey={refreshKey} />
        <Leaderboard meId={session.user.id} refreshKey={refreshKey} />
      </div>
    </div>
  );
}
