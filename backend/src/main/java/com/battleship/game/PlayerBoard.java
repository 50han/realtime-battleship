package com.battleship.game;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.battleship.protocol.Json;

/**
 * One player's board: where their ships are, and which cells the opponent has
 * shot at.
 *
 * Not thread-safe on purpose. Every mutation happens under the owning
 * {@link GameSession}'s lock, which keeps the synchronization in one place
 * instead of scattering per-object locks that could be acquired out of order.
 *
 * The three view methods exist so hidden information cannot leak by accident:
 * {@link #ownerView()} reveals ship positions, {@link #attackerView()} never
 * does, and {@link #revealedView()} is only used once the match is over.
 */
public final class PlayerBoard {

    private static final int SIZE = GameConfiguration.BOARD_SIZE;

    private final ShipLayout layout;
    private final boolean[][] hasShip = new boolean[SIZE][SIZE];
    /** 0 = not shot, 1 = hit, 2 = miss. */
    private final byte[][] shots = new byte[SIZE][SIZE];

    private final int[] shipIndexAt = new int[SIZE * SIZE];
    private final Fleet fleet;
    private int shotsTaken = 0;

    public PlayerBoard(ShipLayout layout) {
        this.layout = layout;
        this.fleet  = new Fleet();
        java.util.Arrays.fill(shipIndexAt, -1);
        for (int ship = 0; ship < layout.shipCount(); ship++) {
            for (int flat : layout.cellsOf(ship)) {
                hasShip[ShipLayout.row(flat)][ShipLayout.col(flat)] = true;
                shipIndexAt[flat] = ship;
            }
        }
    }

    public ShipLayout layout() { return layout; }
    public Fleet fleet()       { return fleet; }
    public int shotsTaken()    { return shotsTaken; }

    public boolean alreadyTargeted(int row, int col) {
        return shots[row][col] != 0;
    }

    /**
     * Applies an incoming shot.
     *
     * @return the outcome, including the ship name if this shot sank one
     */
    public ShotOutcome receiveShot(int row, int col) {
        boolean hit = hasShip[row][col];
        shots[row][col] = (byte) (hit ? 1 : 2);
        shotsTaken++;
        String sunk = null;
        if (hit) {
            sunk = fleet.registerHit(shipIndexAt[row * SIZE + col]);
        }
        return new ShotOutcome(hit, sunk, fleet.allSunk());
    }

    /** The owner's own board: ships visible, plus incoming hits and misses. */
    public int[][] ownerView() {
        int[][] view = new int[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                view[r][c] = switch (shots[r][c]) {
                    case 1 -> GameConfiguration.CELL_HIT;
                    case 2 -> GameConfiguration.CELL_MISS;
                    default -> hasShip[r][c]
                            ? GameConfiguration.CELL_SHIP
                            : GameConfiguration.CELL_UNKNOWN;
                };
            }
        }
        return view;
    }

    /** What the attacker is allowed to see: only their own shot results. */
    public int[][] attackerView() {
        int[][] view = new int[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                view[r][c] = switch (shots[r][c]) {
                    case 1 -> GameConfiguration.CELL_HIT;
                    case 2 -> GameConfiguration.CELL_MISS;
                    default -> GameConfiguration.CELL_UNKNOWN;
                };
            }
        }
        return view;
    }

    /** Everything, for the post-game reveal. Identical to {@link #ownerView()}. */
    public int[][] revealedView() {
        return ownerView();
    }

    // -- snapshot / restore (Redis reconnect state) ---------------------

    public ObjectNode toJson() {
        ObjectNode node = Json.object();
        ArrayNode layoutNode = Json.array();
        for (int[] shipCells : layout.rawCells()) {
            ArrayNode ship = Json.array();
            for (int cell : shipCells) ship.add(cell);
            layoutNode.add(ship);
        }
        node.set("layout", layoutNode);

        ArrayNode shotsNode = Json.array();
        for (int r = 0; r < SIZE; r++) {
            ArrayNode row = Json.array();
            for (int c = 0; c < SIZE; c++) row.add(shots[r][c]);
            shotsNode.add(row);
        }
        node.set("shots", shotsNode);
        node.put("shotsTaken", shotsTaken);
        node.set("fleet", fleet.toJson());
        return node;
    }

    public static PlayerBoard fromJson(ObjectNode node) {
        ArrayNode layoutNode = (ArrayNode) node.get("layout");
        int[][] cells = new int[layoutNode.size()][];
        for (int i = 0; i < layoutNode.size(); i++) {
            ArrayNode ship = (ArrayNode) layoutNode.get(i);
            cells[i] = new int[ship.size()];
            for (int j = 0; j < ship.size(); j++) cells[i][j] = ship.get(j).asInt();
        }
        PlayerBoard board = new PlayerBoard(new ShipLayout(cells));

        ArrayNode shotsNode = (ArrayNode) node.get("shots");
        for (int r = 0; r < SIZE; r++) {
            ArrayNode row = (ArrayNode) shotsNode.get(r);
            for (int c = 0; c < SIZE; c++) {
                board.shots[r][c] = (byte) row.get(c).asInt();
            }
        }
        board.shotsTaken = node.path("shotsTaken").asInt(0);
        board.fleet.restoreFrom((ObjectNode) node.get("fleet"));
        return board;
    }

    /** Outcome of a single shot against this board. */
    public record ShotOutcome(boolean hit, String sunkShipName, boolean fleetDestroyed) {}
}
