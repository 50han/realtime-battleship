// Root component: owns the session, and picks the screen for the current phase.
//
// Everything about the live game — the socket, reconnection, and all game
// state — lives in useGameSocket. This file only decides what to render.

import { useCallback, useEffect, useState } from "react";
import { api, clearSession, loadSession } from "./api";
import { useGameSocket } from "./hooks/useGameSocket";
import Login from "./screens/Login";
import Lobby from "./screens/Lobby";
import Game from "./screens/Game";
import GameOver from "./screens/GameOver";

const CONNECTION_LABEL = {
  idle: "offline",
  connecting: "connecting…",
  reconnecting: "reconnecting…",
  open: "live",
  closed: "disconnected",
};

export default function App() {
  const [session, setSession] = useState(() => loadSession());
  // Bumped whenever a match ends, to re-fetch history and standings.
  const [refreshKey, setRefreshKey] = useState(0);

  const { state, actions, myTurn } = useGameSocket(session?.token);

  // Refresh the cached account record (wins/losses) after every finished match.
  useEffect(() => {
    if (state.phase !== "over" || !session) return;
    setRefreshKey((key) => key + 1);
    api
      .me(session.token)
      .then((user) => setSession((current) => (current ? { ...current, user } : current)))
      .catch(() => {
        // A stale record in the header is not worth surfacing an error for.
      });
  }, [state.phase, session?.token]);

  // Transient server notices (rate limits, rejected shots) auto-dismiss.
  useEffect(() => {
    if (!state.notice) return undefined;
    const id = setTimeout(actions.dismissNotice, 4000);
    return () => clearTimeout(id);
  }, [state.notice, actions.dismissNotice]);

  const signOut = useCallback(() => {
    clearSession();
    setSession(null);
  }, []);

  if (!session) {
    return <Login onAuthenticated={setSession} />;
  }

  const screen =
    state.phase === "playing" ? (
      <Game session={session} state={state} actions={actions} myTurn={myTurn} />
    ) : state.phase === "over" ? (
      <GameOver state={state} actions={actions} />
    ) : (
      <Lobby session={session} state={state} actions={actions} refreshKey={refreshKey} />
    );

  return (
    <div className="app">
      <header className="app-header">
        <span className="brand">⚓ Battleship</span>
        <span className={`conn conn-${state.connection}`}>
          {CONNECTION_LABEL[state.connection] ?? state.connection}
          {state.instanceId && state.connection === "open" && (
            <span className="muted small"> · {state.instanceId}</span>
          )}
        </span>
        <span className="spacer" />
        <span className="who">
          {session.user.username}
          <span className="muted small">
            {" "}
            {session.user.wins}–{session.user.losses}
          </span>
        </span>
        <button className="link" onClick={signOut}>Sign out</button>
      </header>

      {state.notice && (
        <div className="notice" role="status" onClick={actions.dismissNotice}>
          {state.notice.message}
        </div>
      )}

      {state.connection === "reconnecting" && (
        <div className="notice notice-warn" role="status">
          Connection lost — reconnecting. Your match is held for you.
        </div>
      )}

      <main>{screen}</main>
    </div>
  );
}
