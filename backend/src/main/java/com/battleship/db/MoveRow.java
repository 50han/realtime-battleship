package com.battleship.db;

import java.util.UUID;

/** One persisted shot. {@code sunkShip} is null unless the shot sank a ship. */
public record MoveRow(UUID matchId,
                      int moveNo,
                      long shooterId,
                      int row,
                      int col,
                      boolean hit,
                      String sunkShip) {
}
