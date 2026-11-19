package org.abdullahcxd.paperhive.api;

/** A listener or subscription; {@link #unregister()} removes it (again is a no-op). */
@FunctionalInterface
public interface Registration {
    void unregister();
}
