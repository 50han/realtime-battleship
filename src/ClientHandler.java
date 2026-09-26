/*
 * ClientHandler.java
 *
 * One instance per connected client.  Runs on its own Thread.
 *
 * Responsibilities (in order):
 * 1. Perform the WebSocket handshake on the raw OutputStream.
 * 2. Once the handshake is complete, call server.sendAssign() — it is only
 * safe to write WebSocket frames after the 101 response has been sent.
 * 3. Loop reading WebSocket frames, parse each into a Message, and forward
 * to GameServer.handleMessage().
 * 4. On disconnect or I/O error, notify GameServer.handleDisconnect().
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class ClientHandler extends Thread {

    private final Socket     socket;
    private final int        playerNum;   // 1 or 2
    private final GameServer server;

    public ClientHandler(Socket socket, int playerNum, GameServer server) {
        this.socket    = socket;
        this.playerNum = playerNum;
        this.server    = server;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // TODO: perform the WebSocket handshake
            //       (one call to WebSocketUtil — pass both the InputStream and
            //        the socket's OutputStream)
            WebSocketUtil.handshake(in, out);

            // TODO: after the handshake returns, call server.sendAssign(playerNum)
            //       This is the earliest point at which it is safe to write
            //       WebSocket frames — the HTTP upgrade response is now complete.
            server.sendAssign(playerNum);

            // TODO: log that player playerNum has connected
            System.out.println("Player " + playerNum + " handshake complete.");

            // TODO: read loop
            //   - call WebSocketUtil.readFrame(in)
            //   - if the result is null, break (client disconnected gracefully)
            //   - parse the frame string into a Message
            //   - forward to server.handleMessage(playerNum, msg)
            while (true) {
                String frame = WebSocketUtil.readFrame(in);
                if (frame == null) {
                    break;
                }
                Message msg = Message.parse(frame);
                server.handleMessage(playerNum, msg);
            }

        } catch (IOException e) {
            // TODO: log the I/O error
            System.err.println("Player " + playerNum + " I/O Error: " + e.getMessage());
        } finally {
            // TODO: log that player playerNum has disconnected
            System.out.println("Player " + playerNum + " disconnected.");
            // TODO: call server.handleDisconnect(playerNum)
            server.handleDisconnect(playerNum);
        }
    }
}