/*
 * Fleet.java
 *
 * Tracks the fleet of ships and their damage status.
 * This class is identical to your Lab 4 Fleet implementation.
 * Copy it in directly — no changes are required for Lab 5.
 *
 * If you did not finish Fleet in Lab 4, implement it here:
 *   - registerHit(int row, int col) — find which ship occupies that cell,
 *     decrement its hit counter, return its name if it just sank, else null
 *   - allSunk() — return true when every ship's hit counter has reached 0
 */
public class Fleet {
    private String[] names;
    private int[] sizes;
    private int[] hits;
    private boolean[] isSunk;
    private int shipsAfloat;

    private int[][] shipRows;
    private int[][] shipCols;

    /**
     * A data transfer object to hold the formatted results of a single shot.
     * Ensures separation between console output messages and strict history log formatting.
     */
    public static class ShotResult {
        /** True if the shot intersected a ship. */
        public boolean isHit;
        /** The message to immediately print to the console (e.g., "Hit! You sunk the Carrier!"). */
        public String consoleMessage;
        /** The strictly formatted message to store in the HISTORY command (e.g., "Hit (sunk Carrier)"). */
        public String historyMessage;
    }

    /**
     * Constructs a new Fleet based on the generated ship placements.
     * Initializes health and status tracking for each ship.
     *
     * @param placements The generated ship placements containing coordinate data for each ship.
     */
    public Fleet(ShipPlacementGenerator.ShipPlacements placements) {
        this.names = GameConfiguration.SHIP_NAMES;
        this.sizes = GameConfiguration.SHIP_SIZES;
        this.shipRows = placements.shipRows;
        this.shipCols = placements.shipCols;

        int numShips = names.length;
        this.hits = new int[numShips];
        this.isSunk = new boolean[numShips];
        this.shipsAfloat = numShips;
    }

    /**
     * Gets the number of ships currently still afloat.
     *
     * @return The integer count of unsunk ships.
     */
    public int getShipsAfloat() {
        return shipsAfloat;
    }

    /**
     * Processes a fired shot at the specified coordinates.
     * Checks if the shot intersects any ship, updates hit counts, and determines if a ship sank.
     *
     * @param r The row index targeted.
     * @param c The column index targeted.
     * @return A ShotResult object containing the hit status and correctly formatted strings for output and history.
     */
    public ShotResult fireShot(int r, int c) {
        ShotResult res = new ShotResult();
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j < sizes[i]; j++) {
                if (shipRows[i][j] == r && shipCols[i][j] == c) {
                    hits[i]++;
                    res.isHit = true;
                    if (hits[i] == sizes[i]) {
                        isSunk[i] = true;
                        shipsAfloat--;
                        res.consoleMessage = "Hit! You sunk the " + names[i] + "!";
                        res.historyMessage = "Hit (sunk " + names[i] + ")";
                        return res;
                    }
                    res.consoleMessage = "Hit!";
                    res.historyMessage = "Hit";
                    return res;
                }
            }
        }
        res.isHit = false;
        res.consoleMessage = "Miss";
        res.historyMessage = "Miss";
        return res;
    }

    /**
     * Prints the current status of the fleet, listing each ship's name, size, 
     * and whether it is "afloat" or "SUNK".
     */
    public void printFleetStatus() {
        for (int i = 0; i < names.length; i++) {
            String status = isSunk[i] ? "SUNK" : "afloat";
            System.out.println("  " + names[i] + " (" + sizes[i] + "): " + status);
        }
    }
}

