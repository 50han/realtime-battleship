// Post-match summary with both fleets revealed.

import Board from "../components/Board";

const REASON_TEXT = {
  FLEET_DESTROYED: "the enemy fleet was destroyed",
  FORFEIT: "the match was forfeited",
  OPPONENT_ABANDONED: "your opponent never came back",
  TIMEOUT_FORFEIT: "a player ran out of turn time three times",
};

export default function GameOver({ state, actions }) {
  const { result, match } = state;
  const won = result?.winner === match?.playerSlot;

  return (
    <div className="centered">
      <div className="card result-card">
        <h1 className={won ? "win" : "loss"}>{won ? "Victory" : "Defeat"}</h1>
        <p className="muted">
          {won ? "You won" : "You lost"} because {REASON_TEXT[result?.reason] ?? "the match ended"}.
          {" "}
          {result?.totalShots != null && `${result.totalShots} shots were fired in total.`}
        </p>

        <div className="boards">
          <section>
            <h3>Your fleet</h3>
            <Board cells={state.myBoard} />
          </section>
          <section>
            <h3>{match?.opponentName ?? "Opponent"}&rsquo;s fleet</h3>
            <Board cells={state.opponentBoard} />
          </section>
        </div>

        <button className="primary" onClick={actions.backToLobby}>
          Back to lobby
        </button>
      </div>
    </div>
  );
}
