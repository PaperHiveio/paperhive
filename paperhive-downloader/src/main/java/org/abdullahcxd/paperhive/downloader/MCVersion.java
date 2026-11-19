package org.abdullahcxd.paperhive.downloader;

import lombok.Getter;

@Getter
public enum MCVersion {

    MC1_21_11("1.21.11"),
    VELOCITY_4_3_1_45("4.3.1-45");

    private final String version;

    MCVersion(String version) {
        this.version = version;
    }

}
