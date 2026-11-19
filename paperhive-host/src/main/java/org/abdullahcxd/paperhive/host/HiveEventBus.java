package org.abdullahcxd.paperhive.host;

import lombok.extern.slf4j.Slf4j;
import org.abdullahcxd.paperhive.api.Registration;
import org.abdullahcxd.paperhive.api.event.CancellableEvent;
import org.abdullahcxd.paperhive.api.event.EventBus;
import org.abdullahcxd.paperhive.api.event.HiveEvent;
import org.abdullahcxd.paperhive.api.event.Subscribe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@link EventBus}: a listener's {@link Subscribe} methods are found once per class and called through method
 * handles; the handlers for each event type (incl. supertypes) are worked out once and cached until a listener
 * comes or goes, so {@link #post} is a lookup and a loop.
 */
@Slf4j
final class HiveEventBus implements EventBus {

    private static final MethodType HANDLER_TYPE = MethodType.methodType(void.class, HiveEvent.class);

    private record Handler(Object listener, @Nullable Object owner, Class<?> type, Subscribe subscribe, MethodHandle handle, String name) {}

    /** The {@link Subscribe} methods of a class and its superclasses, checked. */
    private static final ClassValue<List<Method>> SUBSCRIBERS = new ClassValue<>() {
        @Override
        protected List<Method> computeValue(@NotNull Class<?> type) {
            List<Method> methods = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.isAnnotationPresent(Subscribe.class)) continue;
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1 || !HiveEvent.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        throw new IllegalArgumentException("@Subscribe " + m + " must be an instance method taking one HiveEvent");
                    }
                    methods.add(m);
                }
            }
            return List.copyOf(methods);
        }
    };

    /** Who made a listener (a server, a Hive plugin), so all of theirs can go at once; null for the host. */
    private final Function<Object, @Nullable Object> ownerOf;
    private final List<Handler> handlers = new CopyOnWriteArrayList<>();
    // replaced (not cleared) on every change, so a post racing a change can't cache a stale list into the new map
    private volatile Map<Class<?>, Handler[]> byEvent = new ConcurrentHashMap<>();

    HiveEventBus(Function<Object, @Nullable Object> ownerOf) {
        this.ownerOf = ownerOf;
    }

    @Override
    public @NotNull Registration register(@NotNull Object listener) {
        List<Handler> added = new ArrayList<>();
        Object owner = ownerOf.apply(listener);
        for (Method m : SUBSCRIBERS.get(listener.getClass())) {
            try {
                m.setAccessible(true);
                MethodHandle handle = MethodHandles.lookup().unreflect(m).bindTo(listener).asType(HANDLER_TYPE);
                added.add(new Handler(listener, owner, m.getParameterTypes()[0], m.getAnnotation(Subscribe.class), handle,
                        m.getDeclaringClass().getName() + "." + m.getName()));
            } catch (IllegalAccessException | RuntimeException e) {
                throw new IllegalArgumentException("Cannot call @Subscribe " + m + ": " + e, e);
            }
        }
        synchronized (this) {
            handlers.addAll(added);
            byEvent = new ConcurrentHashMap<>();
        }
        return () -> unregister(listener);
    }

    @Override
    public void unregister(@NotNull Object listener) {
        unregisterIf(h -> h.listener() == listener);
    }

    /** Every listener {@code owner} registered. */
    void unregisterOwnedBy(Object owner) {
        unregisterIf(h -> h.owner() == owner);
    }

    private synchronized void unregisterIf(Predicate<Handler> which) {
        if (handlers.removeIf(which)) byEvent = new ConcurrentHashMap<>();
    }

    @Override
    public <T extends HiveEvent> @NotNull T post(@NotNull T event) {
        for (Handler h : byEvent.computeIfAbsent(event.getClass(), this::handlersFor)) {
            if (h.subscribe().ignoreCancelled() && event instanceof CancellableEvent c && c.isCancelled()) continue;
            try {
                h.handle().invokeExact((HiveEvent) event);
            } catch (Throwable e) { // one broken subscriber mustn't stop the others
                log.warn("{} failed on {}", h.name(), event.getClass().getSimpleName(), e);
            }
        }
        return event;
    }

    private Handler[] handlersFor(Class<?> eventType) {
        return handlers.stream()
                .filter(h -> h.type().isAssignableFrom(eventType))
                .sorted(Comparator.comparing(h -> h.subscribe().priority())) // stable: registration order within a priority
                .toArray(Handler[]::new);
    }

}
