package org.abdullahcxd.paperhive.api.event;

/** An event whose action doesn't happen if a subscriber cancels it. */
public abstract class CancellableEvent extends HiveEvent {

    private volatile boolean cancelled;

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

}
