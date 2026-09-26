package com.battleship;

import com.battleship.game.GameConfiguration;
import com.battleship.game.PlayerBoard;
import com.battleship.game.ShipLayout;
import com.battleship.game.ShipPlacementGenerator;
import com.battleship.protocol.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerBoardTest {

    /** Destroyer (size 2) at (0,0)-(0,1); other ships parked out of the way. */
    private static PlayerBoard fixedBoard() {
        int[][] cells = {
                {50, 51, 52, 53, 54},   // Carrier   row 5
                {60, 61, 62, 63},       // Battleship row 6
                {70, 71, 72},           // Cruiser   row 7
                {80, 81, 82},           // Submarine row 8
                {0, 1}                  // Destroyer row 0, cols 0-1
        };
        return new PlayerBoard(new ShipLayout(cells));
    }

    @Test
    void randomLayoutPlacesEveryShipWithoutOverlap() {
        ShipLayout layout = ShipPlacementGenerator.random();
        boolean[] seen = new boolean[GameConfiguration.BOARD_SIZE * GameConfiguration.BOARD_SIZE];
        int total = 0;
        for (int ship = 0; ship < layout.shipCount(); ship++) {
            int[] cells = layout.cellsOf(ship);
            assertEquals(GameConfiguration.SHIP_SIZES[ship], cells.length);
            for (int cell : cells) {
                assertFalse(seen[cell], "cell " + cell + " used twice");
                seen[cell] = true;
                total++;
            }
        }
        assertEquals(GameConfiguration.TOTAL_SHIP_CELLS, total);
    }

    @Test
    void seededLayoutIsReproducible() {
        assertArrayEquals(ShipPlacementGenerator.seeded(99).rawCells()[0],
                          ShipPlacementGenerator.seeded(99).rawCells()[0]);
    }

    @Test
    void reportsHitsMissesAndSinking() {
        PlayerBoard board = fixedBoard();

        PlayerBoard.ShotOutcome miss = board.receiveShot(9, 9);
        assertFalse(miss.hit());
        assertNull(miss.sunkShipName());

        PlayerBoard.ShotOutcome hit = board.receiveShot(0, 0);
        assertTrue(hit.hit());
        assertNull(hit.sunkShipName(), "a 2-cell ship is not sunk by one hit");

        PlayerBoard.ShotOutcome sunk = board.receiveShot(0, 1);
        assertTrue(sunk.hit());
        assertEquals("Destroyer", sunk.sunkShipName());
        assertFalse(sunk.fleetDestroyed());
        assertEquals(4, board.fleet().shipsAfloat());
    }

    @Test
    void detectsFleetDestruction() {
        PlayerBoard board = fixedBoard();
        int hits = 0;
        PlayerBoard.ShotOutcome last = null;
        for (int r = 0; r < GameConfiguration.BOARD_SIZE; r++) {
            for (int c = 0; c < GameConfiguration.BOARD_SIZE; c++) {
                PlayerBoard.ShotOutcome outcome = board.receiveShot(r, c);
                if (outcome.hit()) { hits++; last = outcome; }
            }
        }
        assertEquals(GameConfiguration.TOTAL_SHIP_CELLS, hits);
        assertTrue(last.fleetDestroyed());
        assertEquals(0, board.fleet().shipsAfloat());
    }

    @Test
    void attackerViewNeverRevealsUnhitShips() {
        PlayerBoard board = fixedBoard();
        board.receiveShot(0, 0);   // hit
        board.receiveShot(9, 9);   // miss

        int[][] attacker = board.attackerView();
        assertEquals(GameConfiguration.CELL_HIT, attacker[0][0]);
        assertEquals(GameConfiguration.CELL_MISS, attacker[9][9]);
        assertEquals(GameConfiguration.CELL_UNKNOWN, attacker[0][1],
                "the untouched half of the Destroyer must stay hidden");
        for (int[] row : attacker) {
            for (int cell : row) {
                assertFalse(cell == GameConfiguration.CELL_SHIP,
                        "attacker view must never contain a ship marker");
            }
        }

        int[][] owner = board.ownerView();
        assertEquals(GameConfiguration.CELL_SHIP, owner[0][1],
                "the owner does see their own ship");
    }

    @Test
    void tracksAlreadyTargetedCells() {
        PlayerBoard board = fixedBoard();
        assertFalse(board.alreadyTargeted(4, 4));
        board.receiveShot(4, 4);
        assertTrue(board.alreadyTargeted(4, 4));
        assertEquals(1, board.shotsTaken());
    }

    @Test
    void survivesASnapshotRoundTrip() {
        PlayerBoard board = fixedBoard();
        board.receiveShot(0, 0);
        board.receiveShot(0, 1);   // sinks the Destroyer
        board.receiveShot(3, 3);   // miss

        ObjectNode snapshot = board.toJson();
        PlayerBoard restored = PlayerBoard.fromJson(
                Json.parseObject(Json.write(snapshot)));

        assertArrayEquals(board.ownerView(), restored.ownerView());
        assertEquals(board.shotsTaken(), restored.shotsTaken());
        assertEquals(4, restored.fleet().shipsAfloat());
        assertTrue(restored.alreadyTargeted(3, 3));
        assertFalse(restored.alreadyTargeted(5, 5));
    }
}
