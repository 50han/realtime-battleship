package com.battleship.game;

/** Fixed rules of the game. Cell codes are shared verbatim with the client. */
public final class GameConfiguration {

    public static final int BOARD_SIZE = 10;

    public static final String[] SHIP_NAMES = {
            "Carrier", "Battleship", "Cruiser", "Submarine", "Destroyer"
    };
    public static final int[] SHIP_SIZES = {5, 4, 3, 3, 2};

    /** Total occupied cells — the number of hits needed to clear a board. */
    public static final int TOTAL_SHIP_CELLS = 5 + 4 + 3 + 3 + 2;

    // Wire cell codes (see Documents/PROTOCOL.md)
    public static final int CELL_UNKNOWN = 0;
    public static final int CELL_SHIP    = 1;
    public static final int CELL_HIT     = 2;
    public static final int CELL_MISS    = 3;

    private GameConfiguration() {}

    public static boolean inBounds(int row, int col) {
        return row >= 0 && row < BOARD_SIZE && col >= 0 && col < BOARD_SIZE;
    }
}
