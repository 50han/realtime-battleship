// Owns the WebSocket and every piece of live game state.
//
// Why useReducer rather than a pile of useState setters:
//   ws.onmessage is installed once per connection. If it closed over state
//   values it would keep reading whatever they were when the handler was
//   created — the classic stale-closure bug. A reducer sidesteps that
//   entirely: dispatch only ever describes what happened, and the reducer is
//   always handed the current state by React.
//
// The socket is also self-healing. If it drops mid-match it reconnects with
// exponential backoff; the server recognises the JWT, finds the match in
// Redis, and replays it with GAME_RESUME.

import { useCallback, useEffect, useReducer, useRef } from "react";
import { BOARD_SIZE, CELL, WS_URL } from "../config";

const emptyBoard = () =>
  Array.from({ length: BOARD_SIZE }, () => Array(BOARD_SIZE).fill(CELL.UNKNOWN));

const SHIPS = [
  { name: "Carrier", size: 5 },
  { name: "Battleship", size: 4 },
  { name: "Cruiser", size: 3 },
  { name: "Submarine", size: 3 },
  { name: "Destroyer", size: 2 },
];

const freshFleet = () => SHIPS.map((s) => ({ ...s, hits: 0, sunk: false }));

const RECONNECT_DELAYS_MS = [500, 1000, 2000, 4000, 8000, 15000];

// Exported for the protocol conformance test, which replays a real game's
// messages through this reducer to check the client agrees with the server.
export const initialState = {
  connection: "idle",       // idle | connecting | open | reconnecting | closed
  phase: "lobby",           // lobby | queued | playing | over
  me: null,
  instanceId: null,
  queue: { size: 0, since: null },
  match: null,              // { matchId, playerSlot, opponentName }
  myBoard: emptyBoard(),
  opponentBoard: emptyBoard(),
  myFleet: freshFleet(),
  opponentFleet: freshFleet(),
  turn: null,
  turnDeadline: null,
  opponentConnected: true,
  graceSeconds: null,
  chatLog: [],
  lastShot: null,
  result: null,             // { winner, reason, totalShots, myFinalBoard, opponentFinalBoard }
  notice: null,             // { code, message, at }
};

function markSunk(fleet, shipName) {
  return fleet.map((ship) =>
    ship.name === shipName ? { ...ship, sunk: true, hits: ship.size } : ship
  );
}

function bumpHits(fleet, shipName) {
  if (shipName) return markSunk(fleet, shipName);
  return fleet;
}

function withCell(board, row, col, value) {
  return board.map((r, ri) =>
    ri === row ? r.map((c, ci) => (ci === col ? value : c)) : r
  );
}

export function reducer(state, action) {
  switch (action.type) {
    case "CONNECTION":
      return { ...state, connection: action.status };

    case "HELLO":
      return {
        ...state,
        connection: "open",
        me: { userId: action.msg.userId, username: action.msg.username },
        instanceId: action.msg.instanceId,
        // Only fall back to the lobby if we are not already mid-match; a
        // GAME_RESUME may still be in flight right behind this HELLO.
        phase: action.msg.hasActiveMatch ? state.phase : "lobby",
      };

    case "QUEUE_JOINED":
      return {
        ...state,
        phase: "queued",
        queue: { size: action.msg.queueSize, since: action.msg.waitingSince },
      };

    case "QUEUE_STATUS":
      return {
        ...state,
        queue: { ...state.queue, size: action.msg.queueSize, online: action.msg.onlinePlayers },
      };

    case "QUEUE_LEFT":
      return { ...state, phase: "lobby", queue: { size: 0, since: null } };

    case "MATCH_FOUND":
      // Idempotent: a duplicate or late MATCH_FOUND for a match already under
      // way must not reset the boards we have been filling in.
      if (state.match?.matchId === action.msg.matchId) {
        return state;
      }
      return {
        ...state,
        phase: "playing",
        result: null,
        match: {
          matchId: action.msg.matchId,
          playerSlot: action.msg.playerSlot,
          opponentName: action.msg.opponent?.username ?? "Opponent",
        },
        myBoard: emptyBoard(),
        opponentBoard: emptyBoard(),
        myFleet: freshFleet(),
        opponentFleet: freshFleet(),
        chatLog: [],
        opponentConnected: true,
        graceSeconds: null,
      };

    case "GAME_START":
      return {
        ...state,
        phase: "playing",
        result: null,
        match: {
          matchId: action.msg.matchId,
          playerSlot: action.msg.playerSlot,
          opponentName: action.msg.opponentName,
        },
        myBoard: action.msg.myBoard,
        opponentBoard: emptyBoard(),
        myFleet: freshFleet(),
        opponentFleet: freshFleet(),
        turn: action.msg.turn,
        turnDeadline: action.msg.turnDeadline,
        opponentConnected: true,
        graceSeconds: null,
      };

    case "GAME_RESUME":
      return {
        ...state,
        phase: "playing",
        result: null,
        match: {
          matchId: action.msg.matchId,
          playerSlot: action.msg.playerSlot,
          opponentName: action.msg.opponentName,
        },
        myBoard: action.msg.myBoard,
        opponentBoard: action.msg.opponentBoard,
        myFleet: action.msg.myFleet ?? freshFleet(),
        opponentFleet: action.msg.opponentFleet ?? freshFleet(),
        turn: action.msg.turn,
        turnDeadline: action.msg.turnDeadline,
        opponentConnected: action.msg.opponentConnected,
        graceSeconds: null,
        chatLog: (action.msg.chatLog ?? []).map((c) => ({
          from: c.from,
          text: c.text,
          at: c.at,
        })),
        notice: { code: "RESUMED", message: "Reconnected — match resumed", at: Date.now() },
      };

    case "SHOT_RESULT": {
      const { shooter, row, col, hit, sunkShip } = action.msg;
      const value = hit ? CELL.HIT : CELL.MISS;
      const iFired = shooter === state.match?.playerSlot;
      return {
        ...state,
        lastShot: { row, col, hit, sunkShip, byMe: iFired },
        opponentBoard: iFired
          ? withCell(state.opponentBoard, row, col, value)
          : state.opponentBoard,
        myBoard: iFired ? state.myBoard : withCell(state.myBoard, row, col, value),
        opponentFleet: iFired ? bumpHits(state.opponentFleet, sunkShip) : state.opponentFleet,
        myFleet: iFired ? state.myFleet : bumpHits(state.myFleet, sunkShip),
      };
    }

    case "TURN_CHANGE":
      return {
        ...state,
        turn: action.msg.turn,
        turnDeadline: action.msg.turnDeadline,
        notice:
          action.msg.reason === "TIMEOUT"
            ? { code: "TIMEOUT", message: "Turn timed out", at: Date.now() }
            : state.notice,
      };

    case "CHAT":
      return {
        ...state,
        chatLog: [
          ...state.chatLog,
          { from: action.msg.from, text: action.msg.text, at: action.msg.at },
        ],
      };

    case "GAME_OVER":
      return {
        ...state,
        phase: "over",
        turn: null,
        turnDeadline: null,
        graceSeconds: null,
        myBoard: action.msg.myFinalBoard ?? state.myBoard,
        opponentBoard: action.msg.opponentFinalBoard ?? state.opponentBoard,
        result: {
          winner: action.msg.winner,
          reason: action.msg.reason,
          totalShots: action.msg.totalShots,
        },
      };

    case "OPPONENT_DISCONNECTED":
      return {
        ...state,
        opponentConnected: false,
        graceSeconds: action.msg.graceSeconds,
      };

    case "OPPONENT_RECONNECTED":
      return { ...state, opponentConnected: true, graceSeconds: null };

    case "ERROR":
      return {
        ...state,
        notice: { code: action.msg.code, message: action.msg.message, at: Date.now() },
      };

    case "DISMISS_NOTICE":
      return { ...state, notice: null };

    case "BACK_TO_LOBBY":
      return {
        ...initialState,
        connection: state.connection,
        me: state.me,
        instanceId: state.instanceId,
      };

    default:
      return state;
  }
}

export function useGameSocket(token) {
  const [state, dispatch] = useReducer(reducer, initialState);

  const wsRef = useRef(null);
  const urlRef = useRef(WS_URL);
  const attemptRef = useRef(0);
  const retryTimerRef = useRef(null);
  const closedByUsRef = useRef(false);

  const send = useCallback((payload) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
      return true;
    }
    return false;
  }, []);

  useEffect(() => {
    if (!token) return undefined;

    closedByUsRef.current = false;

    const open = () => {
      dispatch({
        type: "CONNECTION",
        status: attemptRef.current === 0 ? "connecting" : "reconnecting",
      });

      const ws = new WebSocket(`${urlRef.current}?token=${encodeURIComponent(token)}`);
      wsRef.current = ws;

      ws.onopen = () => {
        attemptRef.current = 0;
        dispatch({ type: "CONNECTION", status: "open" });
      };

      ws.onmessage = (event) => {
        let msg;
        try {
          msg = JSON.parse(event.data);
        } catch {
          return;
        }

        // The match lives on a different server instance: point the socket at
        // it and let the reconnect path do the rest.
        if (msg.type === "MATCH_FOUND" && msg.reconnectRequired && msg.serverUrl) {
          urlRef.current = msg.serverUrl;
          closedByUsRef.current = false;
          ws.close();
          return;
        }
        dispatch({ type: msg.type, msg });
      };

      ws.onclose = () => {
        wsRef.current = null;
        if (closedByUsRef.current) {
          dispatch({ type: "CONNECTION", status: "closed" });
          return;
        }
        const delay =
          RECONNECT_DELAYS_MS[
            Math.min(attemptRef.current, RECONNECT_DELAYS_MS.length - 1)
          ];
        attemptRef.current += 1;
        dispatch({ type: "CONNECTION", status: "reconnecting" });
        retryTimerRef.current = setTimeout(open, delay);
      };

      ws.onerror = () => {
        // onclose always follows, and that is where reconnection is handled.
      };
    };

    open();

    return () => {
      closedByUsRef.current = true;
      clearTimeout(retryTimerRef.current);
      if (wsRef.current) wsRef.current.close();
      wsRef.current = null;
    };
  }, [token]);

  const actions = {
    joinQueue:  useCallback(() => send({ type: "QUEUE_JOIN" }), [send]),
    leaveQueue: useCallback(() => send({ type: "QUEUE_LEAVE" }), [send]),
    fire:       useCallback((row, col) => send({ type: "FIRE", row, col }), [send]),
    chat:       useCallback((text) => send({ type: "CHAT", text }), [send]),
    forfeit:    useCallback(() => send({ type: "FORFEIT" }), [send]),
    backToLobby: useCallback(() => dispatch({ type: "BACK_TO_LOBBY" }), []),
    dismissNotice: useCallback(() => dispatch({ type: "DISMISS_NOTICE" }), []),
  };

  const myTurn = state.phase === "playing" && state.turn === state.match?.playerSlot;

  return { state, actions, myTurn };
}
