package com.battleship.game;

import com.battleship.db.MatchRepository;
import com.battleship.db.MoveRow;
import com.battleship.net.WebSocketConnection;
import com.battleship.protocol.ErrorCodes;
import com.battleship.protocol.Json;
import com.battleship.protocol.ServerMessage;
import com.battleship.util.Log;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The authoritative state of one match, and the only place it may be mutated.
 *
 * <h2>Concurrency contract</h2>
 * Messages for a single match arrive on two different reader threads, and
 * timers fire on a third. Three rules keep that safe:
 *
 * <ol>
 *   <li><b>One lock, and it is a leaf.</b> All game state is guarded by
 *       {@link #lock}. A session lock is never taken while holding any other
 *       lock in the system (the registry's map is concurrent and lock-free from
 *       the caller's side), and a session never acquires <i>another</i>
 *       session's lock. With no nesting there is no lock ordering to violate,
 *       so no deadlock is possible.</li>
 *   <li><b>No I/O under the lock.</b> Handlers mutate state, collect outbound
 *       messages into a local list, release the lock, and only then hand the
 *       messages to the per-client {@link com.battleship.net.WriterThread}s.
 *       Database and Redis work goes through {@link MatchLifecycle}, which
 *       queues it for a background executor.</li>
 *   <li><b>Chat bypasses the lock entirely.</b> A slow game handler must never
 *       delay chat, so {@link #handleChat} touches only the chat log's own
 *       leaf lock and the writer queues.</li>
 * </ol>
 */
public final class GameSession {

    private static final Log log = Log.of(GameSession.class);

    private static final int MAX_CHAT_HISTORY = 60;
    /** Consecutive turn timeouts by one player before they forfeit. */
    private static final int MAX_CONSECUTIVE_TIMEOUTS = 3;

    public enum Phase { ACTIVE, FINISHED }

    public static final String REASON_FLEET_DESTROYED   = "FLEET_DESTROYED";
    public static final String REASON_FORFEIT           = "FORFEIT";
    public static final String REASON_OPPONENT_ABANDONED = "OPPONENT_ABANDONED";
    public static final String REASON_TIMEOUT_FORFEIT   = "TIMEOUT_FORFEIT";

    private final UUID matchId;
    private final Player[] players = new Player[2];
    private final PlayerBoard[] boards = new PlayerBoard[2];

    /** Live sockets, indexed by slot-1. Null while a player is disconnected. */
    private final AtomicReferenceArray<WebSocketConnection> connections =
            new AtomicReferenceArray<>(2);

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<ChatEntry> chatLog = new ArrayDeque<>();

    private final ScheduledExecutorService scheduler;
    private final MatchLifecycle lifecycle;
    private final long turnTimeoutSeconds;
    private final long reconnectGraceSeconds;

    // ---- guarded by lock ----
    private Phase phase = Phase.ACTIVE;
    private int turn = 1;
    private int moveNo = 0;
    private long turnDeadlineMillis;
    private int[] consecutiveTimeouts = new int[2];
    private boolean started = false;
    private ScheduledFuture<?> turnTimer;
    private final ScheduledFuture<?>[] graceTimers = new ScheduledFuture<?>[2];
    private boolean[] everConnected = new boolean[2];
    // -------------------------

    public GameSession(UUID matchId,
                       Player player1,
                       Player player2,
                       PlayerBoard board1,
                       PlayerBoard board2,
                       ScheduledExecutorService scheduler,
                       MatchLifecycle lifecycle,
                       long turnTimeoutSeconds,
                       long reconnectGraceSeconds) {
        this.matchId   = matchId;
        this.players[0] = player1;
        this.players[1] = player2;
        this.boards[0]  = board1;
        this.boards[1]  = board2;
        this.scheduler  = scheduler;
        this.lifecycle  = lifecycle;
        this.turnTimeoutSeconds    = turnTimeoutSeconds;
        this.reconnectGraceSeconds = reconnectGraceSeconds;
        this.turnDeadlineMillis    = System.currentTimeMillis() + turnTimeoutSeconds * 1000L;
    }

    public static GameSession create(UUID matchId, Player player1, Player player2,
                                     ScheduledExecutorService scheduler,
                                     MatchLifecycle lifecycle,
                                     long turnTimeoutSeconds,
                                     long reconnectGraceSeconds) {
        return new GameSession(matchId, player1, player2,
                new PlayerBoard(ShipPlacementGenerator.random()),
                new PlayerBoard(ShipPlacementGenerator.random()),
                scheduler, lifecycle, turnTimeoutSeconds, reconnectGraceSeconds);
    }

    public UUID matchId()      { return matchId; }
    public String matchIdText() { return matchId.toString(); }
    public Phase phase()       { return phase; }
    public Player player(int slot) { return players[slot - 1]; }
    public long userId(int slot)   { return players[slot - 1].userId(); }

    public int slotOf(long userId) {
        if (players[0].userId() == userId) return 1;
        if (players[1].userId() == userId) return 2;
        return -1;
    }

    public boolean isFinished() { return phase == Phase.FINISHED; }

    // ================================================================
    // Connection attach / detach
    // ================================================================

    /**
     * Binds a (re)connected socket to a slot and brings the client fully up to
     * date. Called on the reader thread that owns {@code connection}.
     *
     * @return false if this user is not a participant, or the match is over
     */
    public boolean attach(WebSocketConnection connection) {
        int slot = slotOf(connection.userId());
        if (slot < 0) return false;

        List<Envelope> outbound = new ArrayList<>();
        boolean isReconnect;

        lock.lock();
        try {
            if (phase == Phase.FINISHED) return false;

            isReconnect = everConnected[slot - 1];
            everConnected[slot - 1] = true;
            cancel(graceTimers[slot - 1]);
            graceTimers[slot - 1] = null;

            WebSocketConnection previous = connections.getAndSet(slot - 1, connection);
            connection.setMatchId(matchIdText());

            // Before start() has run there is nothing to announce yet: start()
            // sends GAME_START to whoever is attached. Sending it here too
            // would deliver the message twice.
            if (!started) {
                // nothing to send yet
            } else if (isReconnect) {
                outbound.add(Envelope.to(slot, resumePayloadLocked(slot)));
                outbound.add(Envelope.to(other(slot),
                        ServerMessage.opponentReconnected(players[slot - 1].username())));
            } else {
                outbound.add(Envelope.to(slot, startPayloadLocked(slot)));
            }

            if (previous != null && previous != connection) {
                // Same account opened a second tab: the newest socket wins.
                outbound.add(Envelope.raw(previous,
                        ServerMessage.error(ErrorCodes.SESSION_REPLACED,
                                "This match was resumed in another window")));
                outbound.add(Envelope.closeAfter(previous));
            }
        } finally {
            lock.unlock();
        }

        flush(outbound);
        log.info((isReconnect ? "Reattached " : "Attached ") + connection
                + " to match " + matchId + " as slot " + slot);
        return true;
    }

    /**
     * Called when a socket dies. Starts the reconnect grace period rather than
     * ending the match immediately — a dropped WiFi connection should not cost
     * someone the game.
     */
    public void detach(WebSocketConnection connection) {
        int slot = slotOf(connection.userId());
        if (slot < 0) return;

        List<Envelope> outbound = new ArrayList<>();
        lock.lock();
        try {
            // Only clear the slot if this exact socket is still the live one;
            // otherwise a replaced tab would evict its own replacement.
            if (!connections.compareAndSet(slot - 1, connection, null)) return;
            if (phase == Phase.FINISHED) return;

            outbound.add(Envelope.to(other(slot),
                    ServerMessage.opponentDisconnected(reconnectGraceSeconds)));

            cancel(graceTimers[slot - 1]);
            graceTimers[slot - 1] = scheduler.schedule(
                    () -> onGraceExpired(slot),
                    reconnectGraceSeconds, TimeUnit.SECONDS);
        } finally {
            lock.unlock();
        }
        flush(outbound);
        log.info(connection + " detached from match " + matchId
                + "; " + reconnectGraceSeconds + "s to reconnect");
    }

    /** Sends GAME_START and arms the first turn timer. */
    public void start() {
        List<Envelope> outbound = new ArrayList<>();
        lock.lock();
        try {
            if (started) return;
            started = true;
            turnDeadlineMillis = System.currentTimeMillis() + turnTimeoutSeconds * 1000L;
            for (int slot = 1; slot <= 2; slot++) {
                if (connections.get(slot - 1) != null) {
                    everConnected[slot - 1] = true;
                    outbound.add(Envelope.to(slot, startPayloadLocked(slot)));
                }
            }
            armTurnTimerLocked();
        } finally {
            lock.unlock();
        }
        flush(outbound);
        lifecycle.onStateChanged(this);
    }

    // ================================================================
    // Message handling
    // ================================================================

    /**
     * Handles a chat message.
     *
     * Takes no game lock at all — this is the rule inherited from the original
     * lab and it still matters: a chat line must reach both clients even while
     * a shot is being resolved.
     */
    public void handleChat(long senderUserId, String text) {
        int slot = slotOf(senderUserId);
        if (slot < 0 || text == null || text.isEmpty()) return;

        String from = players[slot - 1].username();
        long at = System.currentTimeMillis();
        synchronized (chatLog) {          // leaf lock: nothing is acquired under it
            chatLog.addLast(new ChatEntry(from, text, at));
            while (chatLog.size() > MAX_CHAT_HISTORY) chatLog.removeFirst();
        }
        String payload = ServerMessage.chat(from, text, at);
        sendTo(1, payload);
        sendTo(2, payload);
    }

    /** Resolves a shot. Returns an error code to send back, or null on success. */
    public String handleFire(long shooterUserId, int row, int col) {
        int slot = slotOf(shooterUserId);
        if (slot < 0) return ErrorCodes.NOT_IN_MATCH;

        List<Envelope> outbound = new ArrayList<>();
        MoveRow persistedMove = null;
        FinishPlan finish = null;

        lock.lock();
        try {
            if (phase != Phase.ACTIVE)          return ErrorCodes.GAME_NOT_ACTIVE;
            if (slot != turn)                   return ErrorCodes.NOT_YOUR_TURN;
            if (!GameConfiguration.inBounds(row, col)) return ErrorCodes.OUT_OF_BOUNDS;

            PlayerBoard target = boards[other(slot) - 1];
            if (target.alreadyTargeted(row, col)) return ErrorCodes.ALREADY_TARGETED;

            PlayerBoard.ShotOutcome outcome = target.receiveShot(row, col);
            moveNo++;
            consecutiveTimeouts[slot - 1] = 0;

            persistedMove = new MoveRow(matchId, moveNo, shooterUserId,
                    row, col, outcome.hit(), outcome.sunkShipName());

            outbound.add(Envelope.both(ServerMessage.shotResult(
                    slot, row, col, outcome.hit(), outcome.sunkShipName(), moveNo)));

            if (outcome.fleetDestroyed()) {
                finish = finishLocked(slot, REASON_FLEET_DESTROYED,
                        MatchRepository.STATUS_COMPLETED, outbound);
            } else {
                // A hit keeps the turn with the shooter only if you want that
                // house rule; standard Battleship passes the turn every shot.
                turn = other(slot);
                turnDeadlineMillis = System.currentTimeMillis() + turnTimeoutSeconds * 1000L;
                outbound.add(Envelope.both(
                        ServerMessage.turnChange(turn, turnDeadlineMillis, null)));
                armTurnTimerLocked();
            }
        } finally {
            lock.unlock();
        }

        // Everything below happens with no lock held.
        flush(outbound);
        if (persistedMove != null) lifecycle.onMove(persistedMove);
        if (finish != null) finish.execute(); else lifecycle.onStateChanged(this);
        return null;
    }

    /** Voluntary resignation. */
    public void handleForfeit(long userId) {
        int slot = slotOf(userId);
        if (slot < 0) return;
        List<Envelope> outbound = new ArrayList<>();
        FinishPlan finish;
        lock.lock();
        try {
            if (phase != Phase.ACTIVE) return;
            finish = finishLocked(other(slot), REASON_FORFEIT,
                    MatchRepository.STATUS_FORFEITED, outbound);
        } finally {
            lock.unlock();
        }
        flush(outbound);
        finish.execute();
    }

    // ================================================================
    // Timers
    // ================================================================

    private void onTurnExpired() {
        List<Envelope> outbound = new ArrayList<>();
        FinishPlan finish = null;
        lock.lock();
        try {
            if (phase != Phase.ACTIVE) return;
            // A shot may have landed between the timer firing and this lock
            // being acquired; the deadline check makes the timer idempotent.
            if (System.currentTimeMillis() < turnDeadlineMillis - 250) return;

            int stalled = turn;
            consecutiveTimeouts[stalled - 1]++;
            if (consecutiveTimeouts[stalled - 1] >= MAX_CONSECUTIVE_TIMEOUTS) {
                finish = finishLocked(other(stalled), REASON_TIMEOUT_FORFEIT,
                        MatchRepository.STATUS_FORFEITED, outbound);
            } else {
                turn = other(stalled);
                turnDeadlineMillis = System.currentTimeMillis() + turnTimeoutSeconds * 1000L;
                outbound.add(Envelope.both(
                        ServerMessage.turnChange(turn, turnDeadlineMillis, "TIMEOUT")));
                armTurnTimerLocked();
            }
        } finally {
            lock.unlock();
        }
        flush(outbound);
        if (finish != null) finish.execute();
    }

    private void onGraceExpired(int slot) {
        List<Envelope> outbound = new ArrayList<>();
        FinishPlan finish;
        lock.lock();
        try {
            if (phase != Phase.ACTIVE) return;
            if (connections.get(slot - 1) != null) return;   // they came back
            finish = finishLocked(other(slot), REASON_OPPONENT_ABANDONED,
                    MatchRepository.STATUS_FORFEITED, outbound);
        } finally {
            lock.unlock();
        }
        flush(outbound);
        finish.execute();
    }

    /**
     * Starts (or restarts) the turn clock. {@link #start()} calls this for a
     * new match; {@link #fromSnapshot} calls it for a restored one, which
     * otherwise would have no turn timer at all and could stall forever.
     */
    public void armTurnTimer() {
        lock.lock();
        try {
            if (phase != Phase.ACTIVE) return;
            turnDeadlineMillis = System.currentTimeMillis() + turnTimeoutSeconds * 1000L;
            armTurnTimerLocked();
        } finally {
            lock.unlock();
        }
    }

    /** Must hold {@link #lock}. */
    private void armTurnTimerLocked() {
        cancel(turnTimer);
        long delay = Math.max(500, turnDeadlineMillis - System.currentTimeMillis());
        turnTimer = scheduler.schedule(this::onTurnExpired, delay, TimeUnit.MILLISECONDS);
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }

    // ================================================================
    // Finishing
    // ================================================================

    /**
     * Must hold {@link #lock}. Mutates state and appends the GAME_OVER
     * messages, but does no I/O: the returned {@link FinishPlan} carries the
     * persistence work to be run after the lock is released.
     */
    private FinishPlan finishLocked(int winnerSlot, String reason, String status,
                                    List<Envelope> outbound) {
        phase = Phase.FINISHED;
        cancel(turnTimer);
        turnTimer = null;
        for (int i = 0; i < 2; i++) {
            cancel(graceTimers[i]);
            graceTimers[i] = null;
        }

        int totalShots = boards[0].shotsTaken() + boards[1].shotsTaken();
        for (int slot = 1; slot <= 2; slot++) {
            outbound.add(Envelope.to(slot, ServerMessage.gameOver(
                    matchIdText(), winnerSlot, reason,
                    boards[slot - 1].revealedView(),
                    boards[other(slot) - 1].revealedView(),
                    totalShots)));
        }

        long winnerUserId = players[winnerSlot - 1].userId();
        long loserUserId  = players[other(winnerSlot) - 1].userId();
        log.info("Match " + matchId + " finished: winner slot " + winnerSlot
                + " (" + reason + ") after " + totalShots + " shots");

        return new FinishPlan(winnerUserId, loserUserId, status, totalShots);
    }

    /** Deferred, lock-free persistence for a finished match. */
    private final class FinishPlan {
        private final long winnerUserId;
        private final long loserUserId;
        private final String status;
        private final int totalShots;

        FinishPlan(long winnerUserId, long loserUserId, String status, int totalShots) {
            this.winnerUserId = winnerUserId;
            this.loserUserId  = loserUserId;
            this.status       = status;
            this.totalShots   = totalShots;
        }

        void execute() {
            lifecycle.onMatchFinished(GameSession.this,
                    winnerUserId, loserUserId, status, totalShots);
        }
    }

    /** Ends the match without a winner — used when the server is shutting down. */
    public void abortForShutdown() {
        lock.lock();
        try {
            if (phase == Phase.FINISHED) return;
            phase = Phase.FINISHED;
            cancel(turnTimer);
            for (ScheduledFuture<?> f : graceTimers) cancel(f);
        } finally {
            lock.unlock();
        }
        String payload = ServerMessage.error(ErrorCodes.SERVER_SHUTDOWN,
                "The server is restarting; this match has been suspended");
        sendTo(1, payload);
        sendTo(2, payload);
    }

    // ================================================================
    // Payload construction (must hold lock)
    // ================================================================

    private String startPayloadLocked(int slot) {
        return ServerMessage.gameStart(matchIdText(), slot,
                boards[slot - 1].ownerView(), turn, turnDeadlineMillis,
                players[slot - 1].username(), players[other(slot) - 1].username());
    }

    private String resumePayloadLocked(int slot) {
        ArrayNode chat = Json.array();
        synchronized (chatLog) {
            for (ChatEntry entry : chatLog) {
                ObjectNode node = Json.object();
                node.put("from", entry.from());
                node.put("text", entry.text());
                node.put("at", entry.atMillis());
                chat.add(node);
            }
        }
        return ServerMessage.gameResume(matchIdText(), slot,
                boards[slot - 1].ownerView(),
                boards[other(slot) - 1].attackerView(),
                boards[slot - 1].fleet().toClientJson(),
                boards[other(slot) - 1].fleet().toOpponentJson(),
                turn, turnDeadlineMillis,
                players[slot - 1].username(),
                players[other(slot) - 1].username(),
                connections.get(other(slot) - 1) != null,
                chat);
    }

    // ================================================================
    // Snapshot for Redis-backed reconnect
    // ================================================================

    public String snapshotJson() {
        ObjectNode node = Json.object();
        lock.lock();
        try {
            node.put("matchId", matchIdText());
            node.put("turn", turn);
            node.put("moveNo", moveNo);
            node.put("phase", phase.name());
            node.put("turnDeadline", turnDeadlineMillis);
            ArrayNode playerNodes = Json.array();
            for (Player p : players) {
                ObjectNode pn = Json.object();
                pn.put("userId", p.userId());
                pn.put("username", p.username());
                pn.put("slot", p.slot());
                playerNodes.add(pn);
            }
            node.set("players", playerNodes);
            ArrayNode boardNodes = Json.array();
            for (PlayerBoard board : boards) boardNodes.add(board.toJson());
            node.set("boards", boardNodes);
        } finally {
            lock.unlock();
        }
        return Json.write(node);
    }

    /** Rebuilds a session from a Redis snapshot after an instance restart. */
    public static GameSession fromSnapshot(String json,
                                           ScheduledExecutorService scheduler,
                                           MatchLifecycle lifecycle,
                                           long turnTimeoutSeconds,
                                           long reconnectGraceSeconds) {
        ObjectNode node = Json.parseObject(json);
        if (node == null) throw new IllegalArgumentException("Malformed match snapshot");

        ArrayNode playerNodes = (ArrayNode) node.get("players");
        Player p1 = new Player(playerNodes.get(0).get("userId").asLong(),
                playerNodes.get(0).get("username").asText(), 1);
        Player p2 = new Player(playerNodes.get(1).get("userId").asLong(),
                playerNodes.get(1).get("username").asText(), 2);

        ArrayNode boardNodes = (ArrayNode) node.get("boards");
        PlayerBoard b1 = PlayerBoard.fromJson((ObjectNode) boardNodes.get(0));
        PlayerBoard b2 = PlayerBoard.fromJson((ObjectNode) boardNodes.get(1));

        GameSession session = new GameSession(
                UUID.fromString(node.get("matchId").asText()),
                p1, p2, b1, b2, scheduler, lifecycle,
                turnTimeoutSeconds, reconnectGraceSeconds);
        session.turn   = node.path("turn").asInt(1);
        session.moveNo = node.path("moveNo").asInt(0);
        session.phase  = Phase.valueOf(node.path("phase").asText(Phase.ACTIVE.name()));
        session.turnDeadlineMillis =
                System.currentTimeMillis() + turnTimeoutSeconds * 1000L;
        // Both players are considered previously connected, so the first socket
        // to arrive after a restart gets a GAME_RESUME rather than GAME_START.
        session.everConnected[0] = true;
        session.everConnected[1] = true;
        session.started = true;
        session.armTurnTimer();
        return session;
    }

    // ================================================================
    // Outbound plumbing
    // ================================================================

    private void sendTo(int slot, String payload) {
        WebSocketConnection connection = connections.get(slot - 1);
        if (connection != null) connection.send(payload);
    }

    /** Delivers collected messages. Called only after {@link #lock} is released. */
    private void flush(List<Envelope> outbound) {
        for (Envelope envelope : outbound) envelope.deliver(this);
    }

    private static int other(int slot) { return slot == 1 ? 2 : 1; }

    /** A pending outbound message, resolved to a socket at flush time. */
    private record Envelope(int slot, String payload,
                            WebSocketConnection explicitTarget, boolean close) {

        static Envelope both(String payload)            { return new Envelope(0, payload, null, false); }
        static Envelope to(int slot, String payload)    { return new Envelope(slot, payload, null, false); }
        static Envelope raw(WebSocketConnection c, String payload) {
            return new Envelope(-1, payload, c, false);
        }
        static Envelope closeAfter(WebSocketConnection c) {
            return new Envelope(-1, null, c, true);
        }

        void deliver(GameSession session) {
            if (explicitTarget != null) {
                if (close) explicitTarget.close(1000, "session replaced");
                else explicitTarget.send(payload);
                return;
            }
            if (slot == 0) {
                session.sendTo(1, payload);
                session.sendTo(2, payload);
            } else {
                session.sendTo(slot, payload);
            }
        }
    }

    /** A participant. */
    public record Player(long userId, String username, int slot) {}

    /** One chat line, retained so a reconnecting player sees the backlog. */
    public record ChatEntry(String from, String text, long atMillis) {}
}
