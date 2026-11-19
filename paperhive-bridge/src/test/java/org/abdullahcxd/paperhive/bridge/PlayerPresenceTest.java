package org.abdullahcxd.paperhive.bridge;

import org.abdullahcxd.paperhive.api.Namespaced;
import org.abdullahcxd.paperhive.api.Registration;
import org.abdullahcxd.paperhive.api.data.HiveData;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPresenceTest {

    private final MapData data = new MapData();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final UUID steve = UUID.randomUUID();

    private PlayerPresence server(String name) {
        running.add(name);
        return new PlayerPresence(data, name, running::contains, now::get);
    }

    @Test
    void aFirstJoinIsNewToTheHive() {
        assertNull(server("lobby").joined(steve));
    }

    @Test
    void quittingThenJoiningAnotherServerIsASwitch() {
        PlayerPresence lobby = server("lobby"), survival = server("survival");
        lobby.joined(steve);
        String mark = lobby.quit(steve);
        assertNotNull(mark);
        now.addAndGet(2_000);
        assertEquals("lobby", survival.joined(steve));
        assertFalse(lobby.leftHive(steve, mark), "they're on survival, not gone");
    }

    @Test
    void theProxyMayConnectToTheNewServerBeforeTheOldSeesTheQuit() {
        PlayerPresence lobby = server("lobby"), survival = server("survival");
        lobby.joined(steve);
        assertEquals("lobby", survival.joined(steve));
        assertNull(lobby.quit(steve), "survival already has them: no leave to check");
    }

    @Test
    void stayingAwayLongerIsLeavingTheHive() {
        PlayerPresence lobby = server("lobby"), survival = server("survival");
        lobby.joined(steve);
        String mark = lobby.quit(steve);
        assertTrue(lobby.leftHive(steve, mark));
        now.addAndGet(PlayerPresence.SWITCH_WINDOW_MILLIS + 1);
        assertNull(survival.joined(steve), "back after leaving: new to the hive again");
    }

    @Test
    void aLateRejoinOfTheSameServerIsNotASwitch() {
        PlayerPresence lobby = server("lobby");
        lobby.joined(steve);
        lobby.quit(steve);
        assertNull(lobby.joined(steve));
    }

    @Test
    void anOnlineMarkFromAServerThatIsntRunningDoesntCount() {
        PlayerPresence crashed = server("crashed"), lobby = server("lobby");
        crashed.joined(steve);
        running.remove("crashed");
        assertNull(lobby.joined(steve));
    }

    /** Just enough HiveData: a map. */
    private static final class MapData implements HiveData {
        private final Map<Namespaced, String> map = new ConcurrentHashMap<>();

        @Override public String get(Namespaced key) { return map.get(key); }
        @Override public void set(Namespaced key, String value) { map.put(key, value); }
        @Override public boolean remove(Namespaced key) { return map.remove(key) != null; }
        @Override public synchronized boolean compareAndSet(Namespaced key, String expected, String update) {
            if (!Objects.equals(map.get(key), expected)) return false;
            if (update == null) map.remove(key);
            else map.put(key, update);
            return true;
        }
        @Override public long increment(Namespaced key, long delta) { throw new UnsupportedOperationException(); }
        @Override public Map<Namespaced, String> entries(String namespace) { throw new UnsupportedOperationException(); }
        @Override public Registration watch(Namespaced key, DataListener listener) { throw new UnsupportedOperationException(); }
    }

}
