package org.abdullahcxd.paperhive.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that receives Hive events (Bukkit's {@code @EventHandler}, for the {@link EventBus}). The method
 * takes exactly one parameter, a {@link HiveEvent} type, and gets that event and its subtypes. Any visibility.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {

    /** Lower runs first; {@link Priority#MONITOR} last, to observe the outcome only. */
    Priority priority() default Priority.NORMAL;

    /** Skip the event if a subscriber before this one cancelled it. */
    boolean ignoreCancelled() default false;

}
