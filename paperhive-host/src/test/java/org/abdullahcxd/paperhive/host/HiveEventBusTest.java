package org.abdullahcxd.paperhive.host;

import org.abdullahcxd.paperhive.api.Registration;
import org.abdullahcxd.paperhive.api.event.CancellableEvent;
import org.abdullahcxd.paperhive.api.event.HiveEvent;
import org.abdullahcxd.paperhive.api.event.Priority;
import org.abdullahcxd.paperhive.api.event.Subscribe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HiveEventBusTest {

    static class Ping extends CancellableEvent {}

    static class LoudPing extends Ping {}

    final List<String> got = new ArrayList<>();
    final Object owner = new Object();
    final HiveEventBus bus = new HiveEventBus(l -> l instanceof Owned ? owner : null);

    class Owned {
        @Subscribe
        void any(HiveEvent e) {
            got.add("owned");
        }
    }

    class Listener {
        @Subscribe(priority = Priority.MONITOR)
        void monitor(Ping p) {
            got.add("monitor" + (p.isCancelled() ? "-cancelled" : ""));
        }

        @Subscribe(priority = Priority.LOWEST)
        void first(Ping p) {
            got.add("lowest");
            p.setCancelled(true);
        }

        @Subscribe(ignoreCancelled = true)
        void skipped(Ping p) {
            got.add("never: cancelled before");
        }

        @Subscribe
        private void onlyLoud(LoudPing p) {
            got.add("loud");
        }

        @Subscribe(priority = Priority.HIGH)
        void broken(Ping p) {
            throw new IllegalStateException("broken");
        }
    }

    @Test
    void priorityCancellationSubtypesAndErrors() {
        bus.register(new Listener());
        assertTrue(bus.post(new Ping()).isCancelled());
        assertEquals(List.of("lowest", "monitor-cancelled"), got, "by priority; ignoreCancelled skipped; a throwing one doesn't stop the rest");
        got.clear();
        bus.post(new LoudPing());
        assertEquals(List.of("lowest", "loud", "monitor-cancelled"), got, "a subtype reaches Ping and LoudPing subscribers, private ones too");
    }

    @Test
    void unregisterAndOwners() {
        Listener listener = new Listener();
        Registration r = bus.register(listener);
        bus.register(new Owned());
        bus.post(new Ping());
        assertTrue(got.contains("owned"));

        got.clear();
        bus.unregisterOwnedBy(owner);
        r.unregister();
        bus.post(new Ping());
        assertEquals(List.of(), got);
    }

    @Test
    void badSubscribersAreRefused() {
        Object twoArgs = new Object() {
            @Subscribe
            void bad(Ping a, Ping b) {}
        };
        Object notAnEvent = new Object() {
            @Subscribe
            void bad(String s) {}
        };
        assertThrows(IllegalArgumentException.class, () -> bus.register(twoArgs));
        assertThrows(IllegalArgumentException.class, () -> bus.register(notAnEvent));
    }

}
