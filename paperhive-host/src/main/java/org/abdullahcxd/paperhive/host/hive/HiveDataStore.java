package org.abdullahcxd.paperhive.host.hive;

import org.abdullahcxd.paperhive.api.Namespaced;
import org.abdullahcxd.paperhive.api.Registration;
import org.abdullahcxd.paperhive.api.data.HiveData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/** {@link HiveData}: one map in the host; watchers are owned like message subscriptions and told on the event thread. */
final class HiveDataStore implements HiveData {

    private record Watcher(@Nullable Object owner, DataListener listener) {}

    private final Map<Namespaced, String> values = new ConcurrentHashMap<>();
    private final Map<Namespaced, List<Watcher>> watchers = new ConcurrentHashMap<>();
    private final Function<Object, Object> ownerOf;
    private final Consumer<Runnable> deliver;

    /**
     * @param ownerOf who a listener belongs to (a server, a Hive plugin's loader, or null for the host)
     * @param deliver runs a task on the event thread
     */
    HiveDataStore(Function<Object, Object> ownerOf, Consumer<Runnable> deliver) {
        this.ownerOf = ownerOf;
        this.deliver = deliver;
    }

    @Override
    public @Nullable String get(@NotNull Namespaced key) {
        return values.get(key);
    }

    @Override
    public void set(@NotNull Namespaced key, @NotNull String value) {
        Objects.requireNonNull(value, "value; use remove to unset");
        changed(key, values.put(key, value), value);
    }

    @Override
    public boolean remove(@NotNull Namespaced key) {
        String before = values.remove(key);
        changed(key, before, null);
        return before != null;
    }

    @Override
    public boolean compareAndSet(@NotNull Namespaced key, @Nullable String expected, @Nullable String update) {
        boolean[] done = {false};
        values.compute(key, (k, now) -> {
            if (!Objects.equals(now, expected)) return now;
            done[0] = true;
            return update;
        });
        if (done[0]) changed(key, expected, update);
        return done[0];
    }

    @Override
    public long increment(@NotNull Namespaced key, long delta) {
        String[] before = {null};
        String after = values.compute(key, (k, now) -> {
            before[0] = now;
            return Long.toString(Math.addExact(now == null ? 0 : Long.parseLong(now), delta));
        });
        changed(key, before[0], after);
        return Long.parseLong(after);
    }

    @Override
    public @NotNull Map<Namespaced, String> entries(@NotNull String namespace) {
        Map<Namespaced, String> result = new TreeMap<>();
        values.forEach((k, v) -> {
            if (k.namespace().equals(namespace)) result.put(k, v);
        });
        return result;
    }

    @Override
    public @NotNull Registration watch(@NotNull Namespaced key, @NotNull DataListener listener) {
        Watcher watcher = new Watcher(ownerOf.apply(listener), listener);
        watchers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(watcher);
        return () -> watchers.getOrDefault(key, List.of()).remove(watcher);
    }

    /** Drops the watchers {@code owner} made. */
    void release(Object owner) {
        watchers.values().forEach(list -> list.removeIf(w -> w.owner() == owner));
    }

    private void changed(Namespaced key, @Nullable String before, @Nullable String after) {
        if (Objects.equals(before, after)) return;
        for (Watcher w : watchers.getOrDefault(key, List.of())) deliver.accept(() -> w.listener().changed(key, before, after));
    }

}
