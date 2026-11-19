package org.abdullahcxd.paperhive.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class TestPaperhiveDownloader {


    @TempDir
    Path sharedDirectory;

    @Test
    public void downloadAndCheck() {
        PaperhiveDownloader.download(MCSoftware.PAPERMC, MCVersion.MC1_21_11, sharedDirectory);

        assertTrue(Files.exists(sharedDirectory.resolve("server.jar")), "Server doesn't exist!");
    }

    @Test
    public void fetchVelocity() {
        assertTrue(Files.isRegularFile(PaperhiveDownloader.fetch(MCSoftware.VELOCITYMC, MCVersion.VELOCITY_4_3_1_45)));
    }

}
