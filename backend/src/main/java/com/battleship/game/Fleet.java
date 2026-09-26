package com.battleship.game;

import com.battleship.protocol.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Per-ship damage tracking for one player.
 *
 * Mutated only under the owning {@link GameSession}'s lock — see
 * {@link PlayerBoard} for why the locking lives there rather than here.
 */
public final class Fleet {

    private final int[] hits    = new int[GameConfiguration.SHIP_SIZES.length];
    private final boolean[] sunk = new boolean[GameConfiguration.SHIP_SIZES.length];
    private int afloat = GameConfiguration.SHIP_SIZES.length;

    /**
     * Records a hit on the given ship.
     *
     * @return the ship's name if this hit sank it, otherwise null
     */
    public String registerHit(int shipIndex) {
        if (shipIndex < 0 || shipIndex >= hits.length) return null;
        if (sunk[shipIndex]) return null;
        hits[shipIndex]++;
        if (hits[shipIndex] >= GameConfiguration.SHIP_SIZES[shipIndex]) {
            sunk[shipIndex] = true;
            afloat--;
            return GameConfiguration.SHIP_NAMES[shipIndex];
        }
        return null;
    }

    public boolean allSunk()  { return afloat == 0; }
    public int shipsAfloat()  { return afloat; }
    public boolean isSunk(int shipIndex) { return sunk[shipIndex]; }

    /** Client-facing fleet status: name, size, hits taken, sunk flag. */
    public ArrayNode toClientJson() {
        ArrayNode ships = Json.array();
        for (int i = 0; i < hits.length; i++) {
            ObjectNode ship = Json.object();
            ship.put("name", GameConfiguration.SHIP_NAMES[i]);
            ship.put("size", GameConfiguration.SHIP_SIZES[i]);
            ship.put("hits", hits[i]);
            ship.put("sunk", sunk[i]);
            ships.add(ship);
        }
        return ships;
    }

    /**
     * The opponent's fleet as the attacker may see it: sunk ships are public
     * knowledge (they were announced), damage on surviving ships is not.
     */
    public ArrayNode toOpponentJson() {
        ArrayNode ships = Json.array();
        for (int i = 0; i < hits.length; i++) {
            ObjectNode ship = Json.object();
            ship.put("name", GameConfiguration.SHIP_NAMES[i]);
            ship.put("size", GameConfiguration.SHIP_SIZES[i]);
            ship.put("hits", sunk[i] ? GameConfiguration.SHIP_SIZES[i] : 0);
            ship.put("sunk", sunk[i]);
            ships.add(ship);
        }
        return ships;
    }

    ObjectNode toJson() {
        ObjectNode node = Json.object();
        ArrayNode hitsNode = Json.array();
        for (int h : hits) hitsNode.add(h);
        node.set("hits", hitsNode);
        node.put("afloat", afloat);
        return node;
    }

    void restoreFrom(ObjectNode node) {
        ArrayNode hitsNode = (ArrayNode) node.get("hits");
        int recomputedAfloat = 0;
        for (int i = 0; i < hits.length; i++) {
            hits[i] = hitsNode.get(i).asInt();
            sunk[i] = hits[i] >= GameConfiguration.SHIP_SIZES[i];
            if (!sunk[i]) recomputedAfloat++;
        }
        // Derive afloat from the hit counts rather than trusting the stored
        // value, so a truncated snapshot cannot invent a ship back into play.
        this.afloat = recomputedAfloat;
    }
}
