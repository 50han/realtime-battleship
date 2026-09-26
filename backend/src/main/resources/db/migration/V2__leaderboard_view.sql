-- V2: leaderboard projection. Kept as a view so the ranking rule lives in one
-- place; the API just selects from it with a LIMIT.

CREATE VIEW leaderboard AS
SELECT u.id,
       u.username,
       u.wins,
       u.losses,
       (u.wins + u.losses)                                         AS games_played,
       CASE WHEN (u.wins + u.losses) = 0 THEN 0.0
            ELSE ROUND(u.wins::numeric / (u.wins + u.losses), 4)
       END                                                         AS win_rate
FROM users u
WHERE (u.wins + u.losses) > 0
ORDER BY u.wins DESC, win_rate DESC, u.username ASC;
