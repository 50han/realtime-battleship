package com.battleship.game;

/**
 * An immutable ship placement: for each ship, the cells it occupies.
 *
 * {@code cells[i]} holds {@code row * BOARD_SIZE + col} for every cell of ship
 * {@code i}. Flattening the coordinate makes the layout trivially serializable
 * for the Redis reconnect snapshot.
 */
public final class ShipLayout {

    private final int[][] cells;

    public ShipLayout(int[][] cells) {
        this.cells = cells;
    }

    public int shipCount() { return cells.length; }

    public int[] cellsOf(int shipIndex) { return cells[shipIndex].clone(); }

    public int[][] rawCells() {
        int[][] copy = new int[cells.length][];
        for (int i = 0; i < cells.length; i++) copy[i] = cells[i].clone();
        return copy;
    }

    /** Index of the ship occupying (row, col), or -1 for open water. */
    public int shipAt(int row, int col) {
        int flat = row * GameConfiguration.BOARD_SIZE + col;
        for (int i = 0; i < cells.length; i++) {
            for (int cell : cells[i]) {
                if (cell == flat) return i;
            }
        }
        return -1;
    }

    public boolean occupies(int row, int col) {
        return shipAt(row, col) >= 0;
    }

    public static int row(int flatCell) { return flatCell / GameConfiguration.BOARD_SIZE; }
    public static int col(int flatCell) { return flatCell % GameConfiguration.BOARD_SIZE; }
}
