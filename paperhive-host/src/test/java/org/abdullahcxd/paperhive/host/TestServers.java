package org.abdullahcxd.paperhive.host;

import org.abdullahcxd.paperhive.host.config.HiveConfig;
import org.abdullahcxd.paperhive.host.instance.ApiGateLoader;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.abdullahcxd.paperhive.host.paperclip.PatchedServer;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** A {@link ServerManager} over a default {@code paperhive.yml} in {@code dir}, with instances that are never started. */
public final class TestServers {

    private TestServers() {}

    public static ServerManager create(Path dir, Consumer<ServerInstance> onCreate) {
        Path file = dir.resolve(HiveConfig.FILE_NAME);
        PatchedServer server = new PatchedServer(dir.resolve("none.jar"), List.of(), "none.Main", () -> {});
        ApiGateLoader gate = new ApiGateLoader(TestServers.class.getClassLoader());
        return new ServerManager(file, HiveConfig.load(file), List.of(), s -> List.of(), s -> {
            ServerInstance instance = new ServerInstance(s.name(), dir.resolve(s.root()), server, gate, s.options());
            onCreate.accept(instance);
            return instance;
        });
    }

}
