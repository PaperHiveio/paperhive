package org.abdullahcxd.paperhive.api;

/**
 * {@code CREATED -> STARTING -> RUNNING -> STOPPING -> STOPPED | CRASHED}, and from {@code STOPPED}/{@code CRASHED}
 * back to {@code STARTING}. {@code RUNNING} means booted (after its first tick).
 */
public enum HiveServerState { CREATED, STARTING, RUNNING, STOPPING, STOPPED, CRASHED }
