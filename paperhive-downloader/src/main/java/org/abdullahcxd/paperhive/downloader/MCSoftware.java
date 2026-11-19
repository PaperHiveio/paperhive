package org.abdullahcxd.paperhive.downloader;

import lombok.Getter;

@Getter
public enum MCSoftware {

    PAPERMC("papermc"),
    VELOCITYMC("velocitymc");

    private final String name;

    MCSoftware(String name) {
        this.name = name;
    }

}
