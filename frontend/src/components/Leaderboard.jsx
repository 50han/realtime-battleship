// Top players, straight from the PostgreSQL `leaderboard` view.

import { useEffect, useState } from "react";
import { api } from "../api";

export default function Leaderboard({ meId, refreshKey }) {
  const [rows, setRows] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    api
      .leaderboard(10)
      .then((data) => { if (!cancelled) setRows(data.leaderboard); })
      .catch((e) => { if (!cancelled) setError(e.message); });
    return () => { cancelled = true; };
  }, [refreshKey]);

  return (
    <section className="card">
      <h3>🏆 Leaderboard</h3>
      {error && <p className="error">{error}</p>}
      {!rows && !error && <p className="muted">Loading…</p>}
      {rows?.length === 0 && <p className="muted">No games played yet.</p>}
      {rows?.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>#</th>
              <th>Player</th>
              <th>W</th>
              <th>L</th>
              <th>Rate</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.userId} className={row.userId === meId ? "is-me" : undefined}>
                <td>{row.rank}</td>
                <td>{row.username}</td>
                <td>{row.wins}</td>
                <td>{row.losses}</td>
                <td>{Math.round(row.winRate * 100)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
