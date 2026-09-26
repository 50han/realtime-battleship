package com.battleship.game;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Places a fleet at random with no overlaps.
 *
 * Stateless and side-effect free, so several matches starting at once can
 * generate their layouts in parallel. The seeded overload exists purely for
 * reproducible tests.
 */
public final class ShipPlacementGenerator {

    private ShipPlacementGenerator() {}

    public static ShipLayout random() {
        return generate(ThreadLocalRandom.current());
    }

    public static ShipLayout seeded(long seed) {
        return generate(new Random(seed));
    }

    private static ShipLayout generate(Random random) {
        int size = GameConfiguration.BOARD_SIZE;
        boolean[][] occupied = new boolean[size][size];
        int[][] cells = new int[GameConfiguration.SHIP_SIZES.length][];

        for (int i = 0; i < GameConfiguration.SHIP_SIZES.length; i++) {
            int length = GameConfiguration.SHIP_SIZES[i];
            while (cells[i] == null) {
                boolean horizontal = random.nextBoolean();
                int maxRow = horizontal ? size : size - length;
                int maxCol = horizontal ? size - length : size;
                int row = random.nextInt(maxRow);
                int col = random.nextInt(maxCol);

                boolean clear = true;
                for (int j = 0; j < length && clear; j++) {
                    int r = horizontal ? row : row + j;
                    int c = horizontal ? col + j : col;
                    if (occupied[r][c]) clear = false;
                }
                if (!clear) continue;

                int[] shipCells = new int[length];
                for (int j = 0; j < length; j++) {
                    int r = horizontal ? row : row + j;
                    int c = horizontal ? col + j : col;
                    occupied[r][c] = true;
                    shipCells[j] = r * size + c;
                }
                cells[i] = shipCells;
            }
        }
        return new ShipLayout(cells);
    }
}
