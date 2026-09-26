// The live match: both boards, both fleets, chat, and the turn clock.

import Board from "../components/Board";
import Chat from "../components/Chat";
import FleetStatus from "../components/FleetStatus";
import TurnTimer from "../components/TurnTimer";

export default function Game({ session, state, actions, myTurn }) {
  const { match, lastShot } = state;
  const opponentName = match?.opponentName ?? "Opponent";

  const shotSummary = lastShot
    ? `${lastShot.byMe ? "You" : opponentName} ${lastShot.hit ? "hit" : "missed"}` +
      (lastShot.sunkShip ? ` — ${lastShot.sunkShip} sunk!` : "")
    : null;

  return (
    <div className="game-layout">
      <div className="game-status">
        <TurnTimer deadline={state.turnDeadline} active={myTurn} />
        <p className={myTurn ? "turn mine" : "turn theirs"}>
          {myTurn ? "Your turn — pick a target" : `Waiting for ${opponentName}`}
        </p>
        {shotSummary && <p className="muted small">{shotSummary}</p>}
        {!state.opponentConnected && (
          <p className="warning" role="status">
            {opponentName} disconnected — {state.graceSeconds ?? 60}s to reconnect
            before they forfeit.
          </p>
        )}
      </div>

      <div className="boards">
        <section className="card board-card">
          <h3>Your waters</h3>
          <Board
            cells={state.myBoard}
            interactive={false}
            lastShot={lastShot && !lastShot.byMe ? lastShot : null}
          />
          <FleetStatus fleet={state.myFleet} label="Your fleet" showDamage />
        </section>

        <section className="card board-card">
          <h3>{opponentName}&rsquo;s waters</h3>
          <Board
            cells={state.opponentBoard}
            interactive={myTurn}
            onFire={actions.fire}
            lastShot={lastShot?.byMe ? lastShot : null}
          />
          <FleetStatus fleet={state.opponentFleet} label="Enemy fleet" />
        </section>
      </div>

      <div className="game-side">
        <Chat
          log={state.chatLog}
          onSend={actions.chat}
          myName={session.user.username}
        />
        <button
          className="danger"
          onClick={() => {
            if (window.confirm("Forfeit this match? It counts as a loss.")) {
              actions.forfeit();
            }
          }}
        >
          Forfeit
        </button>
      </div>
    </div>
  );
}
