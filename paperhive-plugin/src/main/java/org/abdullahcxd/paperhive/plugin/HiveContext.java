package org.abdullahcxd.paperhive.plugin;

import lombok.Getter;

@Getter
public class HiveContext<T> {

    private final T context;

    public HiveContext(T context) {
        this.context = context;
    }

}
