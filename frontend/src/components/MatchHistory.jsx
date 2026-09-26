// Persisted match history for the signed-in player.

import { useEffect, useState } from "react";
import { api } from "../api";

const formatWhen = (millis) =>
  new Date(millis).toLocaleString([], {
    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
  });

const formatDuration = (seconds) => {
  if (seconds == null || seconds < 0) return "—";
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
};

const RESULT_LABEL = {
  WIN: "Win",
  LOSS: "Loss",
  UNFINISHED: "Unfinished",
};

export default function MatchHistory({ token, refreshKey }) {
  const [matches, setMatches] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    api
      .matchHistory(token, 20)
      .then((data) => { if (!cancelled) setMatches(data.matches); })
      .catch((e) => { if (!cancelled) setError(e.message); });
    return () => { cancelled = true; };
  }, [token, refreshKey]);

  return (
    <section className="card">
      <h3>📜 Match history</h3>
      {error && <p className="error">{error}</p>}
      {!matches && !error && <p className="muted">Loading…</p>}
      {matches?.length === 0 && (
        <p className="muted">No matches yet — join the queue to play your first.</p>
      )}
      {matches?.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Result</th>
              <th>Opponent</th>
              <th>Shots</th>
              <th>Length</th>
              <th>When</th>
            </tr>
          </thead>
          <tbody>
            {matches.map((match) => (
              <tr key={match.matchId}>
                <td>
                  <span className={`badge badge-${match.result.toLowerCase()}`}>
                    {RESULT_LABEL[match.result] ?? match.result}
                  </span>
                  {match.status === "FORFEITED" && (
                    <span className="muted small"> forfeit</span>
                  )}
                </td>
                <td>{match.opponentName}</td>
                <td>{match.totalShots}</td>
                <td>{formatDuration(match.durationSeconds)}</td>
                <td className="muted small">{formatWhen(match.startedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
