package org.abdullahcxd.paperhive.host;

import org.jetbrains.annotations.NotNull;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Velocity modern forwarding for one server ({@code Host.VelocitySecret}): before each start, its
 * {@code config/paper-global.yml} gets {@code proxies.velocity} (enabled, online-mode, the proxy's secret) and its
 * {@code server.properties} {@code online-mode=false}, as Velocity's docs ask. Everything else in both files is kept;
 * nothing is written when they already say so.
 */
final class VelocityForwarding {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER).enable(YAMLWriteFeature.MINIMIZE_QUOTES).build();

    private VelocityForwarding() {}

    /**
     * @param secretFile Velocity's {@code forwarding.secret}
     * @throws IOException if the secret can't be read or is empty, or a file can't be written: the server must not
     *                     start without forwarding, since the proxy couldn't log players in
     */
    static void apply(@NotNull Path root, @NotNull Path secretFile) throws IOException {
        String secret;
        try {
            secret = Files.readString(secretFile).strip();
        } catch (IOException e) {
            throw new IOException("Cannot read the Velocity forwarding secret " + secretFile.toAbsolutePath() + " (Host.VelocitySecret)", e);
        }
        if (secret.isEmpty()) throw new IOException("The Velocity forwarding secret " + secretFile.toAbsolutePath() + " is empty");
        paperGlobal(root.resolve("config").resolve("paper-global.yml"), secret);
        serverProperties(root.resolve("server.properties"));
    }

    private static void paperGlobal(Path file, String secret) throws IOException {
        ObjectNode root;
        if (Files.exists(file)) {
            try {
                JsonNode tree = YAML.readTree(Files.readString(file));
                root = tree instanceof ObjectNode o ? o : YAML.createObjectNode();
            } catch (JacksonException e) {
                throw new IOException("Cannot read " + file + ": " + e.getOriginalMessage(), e);
            }
        } else {
            root = YAML.createObjectNode(); // Paper fills in every other key on start
        }
        ObjectNode velocity = object(object(root, "proxies"), "velocity");
        if (velocity.path("enabled").asBoolean(false) && velocity.path("online-mode").asBoolean(false)
                && secret.equals(velocity.path("secret").asString(null))) {
            return;
        }
        velocity.put("enabled", true).put("online-mode", true).put("secret", secret);
        Files.createDirectories(file.getParent());
        Files.writeString(file, YAML.writeValueAsString(root));
    }

    private static ObjectNode object(@NotNull ObjectNode parent, String key) {
        return parent.get(key) instanceof ObjectNode o ? o : parent.putObject(key);
    }

    private static void serverProperties(Path file) throws IOException {
        List<String> lines = Files.exists(file) ? new ArrayList<>(Arrays.asList(Files.readString(file).split("\\R", -1))) : new ArrayList<>();
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).strip().startsWith("online-mode")) continue;
            String key = lines.get(i).strip().split("[=:\\s]", 2)[0];
            if (!key.equals("online-mode")) continue;
            if (lines.get(i).strip().equals("online-mode=false")) return; // already set
            lines.set(i, "online-mode=false");
            found = true;
        }
        if (!found) lines.add("online-mode=false");
        Files.writeString(file, String.join("\n", lines) + "\n");
    }

}
