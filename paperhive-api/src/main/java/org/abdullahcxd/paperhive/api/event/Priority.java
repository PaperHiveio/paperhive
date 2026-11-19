package org.abdullahcxd.paperhive.api.event;

/** The order {@link Subscribe} methods run in: {@code LOWEST} first, {@code MONITOR} last (observe only, don't change). */
public enum Priority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }
