package org.example.isolated;

/** Packed alone into a jar by {@code InstanceClassLoaderTest}; outside {@code org.abdullahcxd.paperhive.*}, so instances load their own copy. */
public final class IsolatedCounter {

    public static int count;

    public static int increment() {
        return ++count;
    }

}
