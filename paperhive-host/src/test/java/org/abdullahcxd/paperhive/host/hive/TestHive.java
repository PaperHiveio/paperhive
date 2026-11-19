package org.abdullahcxd.paperhive.host.hive;

import org.abdullahcxd.paperhive.api.Paperhive;

/** The JVM's one {@link Paperhive#getHive()} for tests (it's set once); each test {@code init}s it with its servers. */
public final class TestHive {

    private static final HostHive HIVE = new HostHive();

    static {
        Paperhive.setHive(HIVE);
    }

    private TestHive() {}

    public static HostHive get() {
        return HIVE;
    }

}
