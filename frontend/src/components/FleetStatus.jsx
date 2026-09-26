// Per-ship damage. `hits` is only populated for your own fleet — the server
// does not tell you how damaged an enemy ship is until it sinks.

export default function FleetStatus({ fleet, label, showDamage = false }) {
  if (!fleet) return null;

  const remaining = fleet.filter((ship) => !ship.sunk).length;

  return (
    <div className="fleet">
      <h4>
        {label} <span className="muted small">{remaining}/{fleet.length} afloat</span>
      </h4>
      <ul>
        {fleet.map((ship) => (
          <li key={ship.name} className={ship.sunk ? "sunk" : undefined}>
            <span className="pips">
              {Array.from({ length: ship.size }).map((_, i) => (
                <span
                  key={i}
                  className={
                    ship.sunk ? "pip pip-sunk"
                    : showDamage && i < ship.hits ? "pip pip-hit"
                    : "pip"
                  }
                />
              ))}
            </span>
            <span className="ship-name">{ship.name}</span>
            {ship.sunk && <span className="badge badge-loss">Sunk</span>}
          </li>
        ))}
      </ul>
    </div>
  );
}
