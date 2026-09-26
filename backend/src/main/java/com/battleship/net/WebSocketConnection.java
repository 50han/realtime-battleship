package com.battleship.net;

import com.battleship.auth.AuthenticatedUser;

import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One authenticated live client: its socket, its writer, and its identity.
 *
 * Everything mutable here is atomic, so any thread can read the connection's
 * state without taking the game lock. That is what lets CHAT and heartbeats be
 * handled without ever touching the lock that serializes game moves.
 */
public final class WebSocketConnection {

    private static final AtomicLong SEQUENCE = new AtomicLong(1);

    private final long id;
    private final Socket socket;
    private final WriterThread writer;
    private final AuthenticatedUser user;
    private final long connectedAtMillis;
    private final AtomicReference<String> matchId = new AtomicReference<>(null);

    public WebSocketConnection(Socket socket, WriterThread writer, AuthenticatedUser user) {
        this.id                = SEQUENCE.getAndIncrement();
        this.socket            = socket;
        this.writer            = writer;
        this.user              = user;
        this.connectedAtMillis = System.currentTimeMillis();
    }

    public long              id()     { return id; }
    public Socket            socket() { return socket; }
    public WriterThread      writer() { return writer; }
    public AuthenticatedUser user()   { return user; }
    public long              userId() { return user.userId(); }
    public String            username() { return user.username(); }
    public long connectedAtMillis()   { return connectedAtMillis; }

    /** Sends a JSON payload to this client. Returns false if it was dropped. */
    public boolean send(String json) {
        return writer.send(json);
    }

    public void ping() {
        writer.enqueue(OutboundFrame.ping());
    }

    public void close(int code, String reason) {
        writer.shutdown(code, reason);
    }

    public String matchId()                  { return matchId.get(); }
    public void setMatchId(String value)     { matchId.set(value); }
    public boolean clearMatchId(String expected) {
        return matchId.compareAndSet(expected, null);
    }

    public boolean isOpen() { return !writer.isClosed() && !socket.isClosed(); }

    @Override
    public String toString() {
        return "conn#" + id + "(" + user.username() + "/" + user.userId() + ")";
    }
}
