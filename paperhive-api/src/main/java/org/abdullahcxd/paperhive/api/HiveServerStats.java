package org.abdullahcxd.paperhive.api;

import org.jetbrains.annotations.NotNull;

/**
 * A running server's load, read when asked.
 *
 * @param tps1m       ticks per second over the last minute (20 is full speed; Paper may report a hair above 20)
 * @param msptAverage average milliseconds per tick (50 is the budget at 20 TPS)
 */
public record HiveServerStats(double tps1m, double tps5m, double tps15m, double msptAverage, int players, int maxPlayers) {}
