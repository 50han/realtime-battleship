// Counts down the server-supplied turn deadline.
//
// The server sends an absolute timestamp rather than a duration, so the bar
// stays honest even if this tab was backgrounded and stopped getting frames.

import { useEffect, useState } from "react";

export default function TurnTimer({ deadline, active, totalSeconds = 45 }) {
  const [remaining, setRemaining] = useState(0);

  useEffect(() => {
    if (!deadline) {
      setRemaining(0);
      return undefined;
    }
    const tick = () => setRemaining(Math.max(0, deadline - Date.now()));
    tick();
    const id = setInterval(tick, 250);
    return () => clearInterval(id);
  }, [deadline]);

  if (!deadline) return null;

  const seconds = Math.ceil(remaining / 1000);
  const fraction = Math.min(1, remaining / (totalSeconds * 1000));
  const urgent = seconds <= 10;

  return (
    <div className={`turn-timer ${urgent ? "urgent" : ""}`}>
      <div className="turn-timer-bar" style={{ width: `${fraction * 100}%` }} />
      <span className="turn-timer-label">
        {active ? "Your turn" : "Opponent"} · {seconds}s
      </span>
    </div>
  );
}
