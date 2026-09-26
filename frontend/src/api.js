// REST client. Gameplay goes over the WebSocket; this covers everything that
// has to survive a page reload: accounts, history, standings.

import { API_URL, TOKEN_STORAGE_KEY } from "./config";

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request(path, { method = "GET", body, token } = {}) {
  let response;
  try {
    response = await fetch(API_URL + path, {
      method,
      headers: {
        ...(body ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch (cause) {
    throw new ApiError(0, "NETWORK", "Could not reach the server. Is it running?");
  }

  const text = await response.text();
  const payload = text ? JSON.parse(text) : {};

  if (!response.ok) {
    throw new ApiError(
      response.status,
      payload.error ?? "ERROR",
      payload.message ?? `Request failed (${response.status})`
    );
  }
  return payload;
}

export const api = {
  register: (username, password) =>
    request("/api/auth/register", { method: "POST", body: { username, password } }),

  login: (username, password) =>
    request("/api/auth/login", { method: "POST", body: { username, password } }),

  me: (token) => request("/api/me", { token }),

  matchHistory: (token, limit = 20) =>
    request(`/api/matches?limit=${limit}`, { token }),

  matchDetail: (token, matchId) => request(`/api/matches/${matchId}`, { token }),

  leaderboard: (limit = 10) => request(`/api/leaderboard?limit=${limit}`),

  stats: () => request("/api/stats"),
};

// --- session persistence -------------------------------------------------
// The JWT is kept in localStorage so a refresh mid-match does not log you out;
// the server's reconnect path then puts you straight back into the game.

export function loadSession() {
  try {
    const raw = localStorage.getItem(TOKEN_STORAGE_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw);
    if (!session?.token || (session.expiresAt && session.expiresAt < Date.now())) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export function saveSession(session) {
  localStorage.setItem(TOKEN_STORAGE_KEY, JSON.stringify(session));
}

export function clearSession() {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
}
