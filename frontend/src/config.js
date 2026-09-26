// Endpoints. Overridable at build time so the same bundle can point at a
// deployed server (see .env.example / docker-compose.yml).
//
// import.meta.env is a Vite construct; guarding it lets these modules also be
// imported by plain Node, which is how the protocol conformance test runs.
const env = import.meta.env ?? {};

export const API_URL = env.VITE_API_URL ?? "http://localhost:8080";
export const WS_URL  = env.VITE_WS_URL  ?? "ws://localhost:8080/ws";

export const BOARD_SIZE = 10;

// Cell codes, shared with the server (see Documents/PROTOCOL.md).
export const CELL = {
  UNKNOWN: 0,
  SHIP:    1,
  HIT:     2,
  MISS:    3,
};

export const ROW_LABELS = ["A", "B", "C", "D", "E", "F", "G", "H", "I", "J"];
export const COL_LABELS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"];

export const TOKEN_STORAGE_KEY = "battleship.session";
