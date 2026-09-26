// Register / sign in. The returned JWT is what authenticates both the REST
// calls and the WebSocket upgrade.

import { useState } from "react";
import { api, saveSession } from "../api";

export default function Login({ onAuthenticated }) {
  const [mode, setMode] = useState("login");   // "login" | "register"
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const registering = mode === "register";

  const submit = async (event) => {
    event?.preventDefault();
    if (busy) return;
    setError("");
    setBusy(true);
    try {
      const result = registering
        ? await api.register(username.trim(), password)
        : await api.login(username.trim(), password);
      const session = {
        token: result.token,
        expiresAt: result.expiresAt,
        user: result.user,
      };
      saveSession(session);
      onAuthenticated(session);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="centered">
      <form className="card auth-card" onSubmit={submit}>
        <h1>⚓ Battleship</h1>
        <p className="muted">
          {registering
            ? "Create an account — your record is saved between sessions."
            : "Sign in to play a ranked match."}
        </p>

        <label htmlFor="username">Username</label>
        <input
          id="username"
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="3-20 letters, digits, . - _"
          maxLength={20}
          required
        />

        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          autoComplete={registering ? "new-password" : "current-password"}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder={registering ? "at least 8 characters" : "your password"}
          required
        />

        {error && <p className="error" role="alert">{error}</p>}

        <button type="submit" className="primary" disabled={busy}>
          {busy ? "Working…" : registering ? "Create account" : "Sign in"}
        </button>

        <button
          type="button"
          className="link"
          onClick={() => { setMode(registering ? "login" : "register"); setError(""); }}
        >
          {registering ? "I already have an account" : "I need an account"}
        </button>
      </form>
    </div>
  );
}
