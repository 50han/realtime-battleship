// A 10x10 grid.
//
// Props:
//   cells        2D array of cell codes (see config.js CELL)
//   interactive  when true, untouched cells are clickable
//   onFire(r, c) click handler for an untouched cell
//   lastShot     { row, col } to highlight the most recent shot

import React from "react";
import { CELL, COL_LABELS, ROW_LABELS } from "../config";

const CLASS_FOR_CELL = {
  [CELL.UNKNOWN]: "cell-water",
  [CELL.SHIP]:    "cell-ship",
  [CELL.HIT]:     "cell-hit",
  [CELL.MISS]:    "cell-miss",
};

const GLYPH_FOR_CELL = {
  [CELL.HIT]:  "✳",
  [CELL.MISS]: "·",
};

export default function Board({ cells, interactive = false, onFire, lastShot }) {
  if (!cells?.length) return null;

  return (
    <div className="board">
      <div className="board-grid">
        <div className="board-corner" />
        {COL_LABELS.map((label) => (
          <div key={label} className="board-label">{label}</div>
        ))}

        {ROW_LABELS.map((rowLabel, row) => (
          <React.Fragment key={rowLabel}>
            <div className="board-label">{rowLabel}</div>
            {cells[row].map((code, col) => {
              const clickable = interactive && code === CELL.UNKNOWN;
              const isLast = lastShot?.row === row && lastShot?.col === col;
              const coordinate = `${rowLabel}${col + 1}`;
              return (
                <button
                  key={col}
                  type="button"
                  className={[
                    "cell",
                    CLASS_FOR_CELL[code],
                    clickable ? "cell-clickable" : "",
                    isLast ? "cell-last" : "",
                  ].filter(Boolean).join(" ")}
                  disabled={!clickable}
                  aria-label={
                    code === CELL.HIT ? `${coordinate}, hit`
                    : code === CELL.MISS ? `${coordinate}, miss`
                    : code === CELL.SHIP ? `${coordinate}, your ship`
                    : clickable ? `Fire at ${coordinate}`
                    : coordinate
                  }
                  onClick={clickable ? () => onFire(row, col) : undefined}
                >
                  {GLYPH_FOR_CELL[code] ?? ""}
                </button>
              );
            })}
          </React.Fragment>
        ))}
      </div>
    </div>
  );
}
