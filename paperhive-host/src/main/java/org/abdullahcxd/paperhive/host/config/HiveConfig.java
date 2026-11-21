package org.abdullahcxd.paperhive.host.config;

import org.abdullahcxd.paperhive.host.software.paper.PaperclipCache;
import org.abdullahcxd.paperhive.constants.DirectoryConstants;
import org.abdullahcxd.paperhive.constants.DownloaderConstants;
import org.abdullahcxd.paperhive.downloader.MCSoftware;
import org.abdullahcxd.paperhive.downloader.MCVersion;
import org.abdullahcxd.paperhive.host.software.ServerSoftware;
import org.abdullahcxd.paperhive.host.software.ServerSoftwares;
import org.abdullahcxd.paperhive.host.instance.ServerInstance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code paperhive.yml}: what this host runs. Keys are PascalCase; a key in another case or spelling is an error that
 * names the right one. Written with one {@code main} server if missing. Files from an older
 * {@code ConfigurationVersion} are migrated step by step (the original is kept as {@code paperhive.yml.v<N>.bak}).
 */
public record HiveConfig(@NotNull MCSoftware software, @NotNull MCVersion version, @NotNull Host host, @NotNull List<Server> servers) {

    public static final String FILE_NAME = "paperhive.yml";
    public static final int CONFIGURATION_VERSION = 1;

    /** {@code MIGRATIONS.get(n)} turns a version {@code n} file into version {@code n + 1}. */
    private static final List<UnaryOperator<ObjectNode>> MIGRATIONS = List.of(HiveConfig::migrate0To1);

    private static final Set<String> TOP_KEYS = Set.of("ConfigurationVersion", "Software", "Version", "Host", "Servers");
    private static final Set<String> HOST_KEYS = Set.of("Plugins", "Cache", "Mirror", "StopTimeout", "MemoryWarning",
            "RestartDelay", "MaxCrashes", "CrashWindow", "VelocitySecret", "EULA");
    private static final Set<String> SERVER_KEYS = Set.of("Name", "Software", "Version", "Port", "IP", "Root", "Plugins", "Args", "Autostart", "Restart", "DisabledHivePlugins", "SharedPlugins", "EULA");
    // also a folder name and the console prefix
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final YAMLMapper YAML = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER).enable(YAMLWriteFeature.MINIMIZE_QUOTES).build();
    private static final Logger LOG = LoggerFactory.getLogger(HiveConfig.class);

    private static final String DEFAULT = """
            # Paperhive host configuration
            ConfigurationVersion: 1
            # The software and version every server runs, unless it says otherwise (papermc or velocitymc)
            Software: papermc
            Version: 1.21.11
            # Optional host settings, shown with their defaults (they apply when Paperhive starts):
            # Host:
            #   Plugins: plugins            the Hive plugins folder
            #   Cache: .paperhive-cache     downloaded and patched server jars
            #   Mirror: https://cdn.abdullahcxd.org/files/paperhive/versions/
            #   StopTimeout: 60             seconds a stopping server gets before it's given up on
            #   MemoryWarning: 90           warn when the shared heap is this % full after a GC
            #   RestartDelay: 3             seconds before a Restart: on-crash server starts again
            #   MaxCrashes: 3               ...and no more restarts after this many crashes
            #   CrashWindow: 600            ...within this many seconds
            #   VelocitySecret: ../velocity/forwarding.secret
            #                               set up Velocity modern forwarding on every server at each start
            #   EULA: Automatic             Automatic: agree to the Minecraft EULA (eula.txt) for every Paper server;
            #                               Manual: only for servers with EULA: true, the rest you agree to yourself
            Servers:
              - Name: main
                Port: 25565
                # Optional, shown with their defaults:
                # Software: papermc           this server's software (velocitymc: a Velocity proxy in the hive)
                # Version: 1.21.11            its version (default: the one above, or that software's newest)
                # IP: 0.0.0.0                 the address to listen on (all of them)
                # Root: instances/main        the server's folder
                # Plugins: <Root>/plugins
                # Args: []                    extra arguments for the server
                # Autostart: true             start it with the host
                # Restart: never              or on-crash: start it again after it crashes
                # DisabledHivePlugins: []     Paperhive plugins not to load on this server
                # SharedPlugins: []           more folders of plugin jars, e.g. [shared/plugins]; each plugin
                #                             still keeps its data in this server's own plugins folder
                # EULA: true                  agree to the Minecraft EULA for this server (default: from Host.EULA)
            """;

    /**
     * @param root    the server's folder (relative to the host's working directory unless absolute)
     * @param plugins       its plugins folder, or null for {@code <root>/plugins}
     * @param sharedPlugins more folders of plugin jars, which other servers may load too (Paper's
     *                      {@code --add-plugin-dir}: the jars are shared, each plugin's data stays in this server's folder)
     */
    public record Server(@NotNull String name, @NotNull MCSoftware software, @NotNull MCVersion version, int port,
                         @Nullable String ip, @NotNull Path root, @Nullable Path plugins, @NotNull List<String> args,
                         boolean autostart, ServerInstance.@NotNull RestartPolicy restart, @NotNull List<String> disabledHivePlugins,
                         @NotNull List<Path> sharedPlugins, @Nullable Boolean eula) {

        /** Whether Paperhive agrees to the Minecraft EULA for it: its own {@code EULA}, else what {@code mode} says. */
        public boolean agreesToEula(@NotNull EulaMode mode) {
            return eula != null ? eula : mode == EulaMode.AUTOMATIC;
        }

        /** What runs it. */
        public @NotNull ServerSoftware serverSoftware() {
            return ServerSoftwares.of(software);
        }

        public ServerInstance.@NotNull Options options() {
            return options(Host.DEFAULT);
        }

        /** How its instance runs: the software turns settings like {@code IP} into its own flags, then {@code Args}. */
        public ServerInstance.@NotNull Options options(@NotNull Host host) {
            List<String> all = new ArrayList<>(serverSoftware().configArgs(this));
            all.addAll(args);
            return new ServerInstance.Options(port, plugins, all, restart, host.restartDelay(), host.maxCrashes(), host.crashWindow());
        }
    }

    /**
     * The {@code Host:} section: settings of Paperhive itself.
     *
     * @param plugins       the Hive plugins folder
     * @param cache         where downloaded and patched server jars are kept
     * @param mirror        the Paperhive mirror's base URL (https, ends in {@code /})
     * @param memoryWarning  warn when the shared heap is this percent full after a GC
     * @param velocitySecret Velocity's {@code forwarding.secret}: when set, every server is set up for modern forwarding
     * @param eula           for the servers without their own {@code EULA}: whether Paperhive agrees to the Minecraft EULA
     */
    public record Host(Path plugins, Path cache, String mirror, Duration stopTimeout, int memoryWarning,
                       Duration restartDelay, int maxCrashes, Duration crashWindow, @Nullable Path velocitySecret, @NotNull EulaMode eula) {
        public static final Host DEFAULT = new Host(Path.of("plugins"), DirectoryConstants.CACHE_DIRECTORY_PATH,
                DownloaderConstants.CDN_URI, Duration.ofSeconds(60), 90, Duration.ofSeconds(3), 3, Duration.ofMinutes(10), null, EulaMode.AUTOMATIC);
    }

    /** {@code Host.EULA}: who agrees to the Minecraft EULA ({@code eula.txt}) of the servers that don't set {@code EULA}. */
    public enum EulaMode {
        /** Paperhive does, at each start. */
        AUTOMATIC,
        /** You do (or the server's {@code EULA: true}); Paperhive leaves {@code eula.txt} alone. */
        MANUAL
    }

    public static @NotNull HiveConfig load(@NotNull Path file) {
        try {
            if (Files.notExists(file)) Files.writeString(file, DEFAULT);
            JsonNode tree = YAML.readTree(Files.readString(file));
            if (!(tree instanceof ObjectNode node)) throw new HiveConfigException(file, List.of("expected keys like Servers: at the top"));
            return parse(migrate(node, file), file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        } catch (JacksonException e) {
            throw new HiveConfigException(file, List.of("not valid YAML: " + e.getOriginalMessage()));
        }
    }

    /**
     * Adds a server with just {@code Name} and {@code Port} to the file and returns the new config. Like every edit
     * here: nothing is written if the result would be invalid, and the file's comments are kept when the change can be
     * made in its text; otherwise the file is rewritten without them (the old one kept as {@code .bak}).
     */
    public static @NotNull HiveConfig addServer(@NotNull Path file, @NotNull String name, int port) {
        return edit(file, (node, text) -> {
            ((ArrayNode) node.get("Servers")).addObject().put("Name", name).put("Port", port);
            Matcher items = SERVERS_ITEMS.matcher(text);
            if (!items.find()) return null;
            String indent = items.group(1);
            return text + (text.endsWith("\n") ? "" : "\n") + indent + "- Name: " + name + "\n" + indent + "  Port: " + port + "\n";
        });
    }

    /** Removes a server from the file; its folder is left alone. */
    public static @NotNull HiveConfig removeServer(@NotNull Path file, @NotNull String name) {
        return edit(file, (node, text) -> {
            ArrayNode list = (ArrayNode) node.get("Servers");
            list.remove(indexOf(list, name, file));
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
            int[] block = block(lines, name);
            if (block == null) return null;
            lines.subList(block[0], block[1]).clear();
            return String.join("\n", lines);
        });
    }

    /**
     * Sets one key of a server to {@code value}, read as YAML ({@code 25570}, {@code on-crash}, {@code [a, b]}), or
     * removes the key (back to its default) when {@code value} is null.
     */
    public static @NotNull HiveConfig setServerKey(@NotNull Path file, @NotNull String name, @NotNull String key, @Nullable String value) {
        if (key.equals("Name")) throw new HiveConfigException(file, List.of("Name can't be changed; delete the server and create it again"));
        return edit(file, (node, text) -> {
            ArrayNode list = (ArrayNode) node.get("Servers");
            ObjectNode server = (ObjectNode) list.get(indexOf(list, name, file));
            if (value == null) server.remove(key);
            else server.set(key, YAML.readTree(value));

            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
            int[] block = block(lines, name);
            if (block == null) return null;
            String keyIndent = " ".repeat(lines.get(block[0]).indexOf("Name"));
            String newLine = keyIndent + key + ": " + value;
            for (int i = block[0] + 1; i < block[1]; i++) {
                if (lines.get(i).startsWith(keyIndent + key + ":")) {
                    if (value == null) lines.remove(i);
                    else lines.set(i, newLine);
                    return String.join("\n", lines);
                }
            }
            if (value != null) lines.add(block[0] + 1, newLine);
            return String.join("\n", lines);
        });
    }

    /** A change to the tree, and the same change made in the text (null if it can't be). */
    private interface Edit {
        @Nullable String apply(ObjectNode node, String text);
    }

    private static HiveConfig edit(Path file, Edit edit) {
        load(file); // current version, and valid as it is now
        try {
            String text = Files.readString(file);
            ObjectNode node = (ObjectNode) YAML.readTree(text);
            String edited = edit.apply(node, text);
            HiveConfig result = parse(node, file);
            if (edited != null && result.equals(tryParse(edited, file))) {
                Files.writeString(file, edited);
                return result;
            }
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, YAML.writeValueAsString(node));
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        } catch (JacksonException e) {
            throw new HiveConfigException(file, List.of("value: not valid YAML: " + e.getOriginalMessage()));
        }
    }

    private static int indexOf(ArrayNode servers, String name, Path file) {
        for (int i = 0; i < servers.size(); i++) {
            JsonNode n = servers.get(i).get("Name");
            if (n != null && n.isString() && n.stringValue().equals(name)) return i;
        }
        throw new HiveConfigException(file, List.of("no server named " + name));
    }

    /** The lines {@code [from, to)} of the list item {@code - Name: <name>}, or null if it isn't written that way. */
    private static int @Nullable [] block(List<String> lines, String name) {
        Pattern item = Pattern.compile("^( *)- +Name: *" + Pattern.quote(name) + "\\s*(#.*)?$");
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = item.matcher(lines.get(i));
            if (!m.matches()) continue;
            int indent = m.group(1).length();
            int end = i + 1;
            // the item goes on while lines are blank or indented deeper than its `-`
            while (end < lines.size() && (lines.get(end).isBlank() || lines.get(end).length() - lines.get(end).stripLeading().length() > indent)) end++;
            while (end > i + 1 && lines.get(end - 1).isBlank()) end--; // trailing blank lines aren't part of it
            return new int[]{i, end};
        }
        return null;
    }

    // the indentation of the first item under a top-level `Servers:`
    private static final Pattern SERVERS_ITEMS = Pattern.compile("(?m)^Servers:[ \t]*\r?\n(?:[ \t]*(?:#.*)?\r?\n)*([ \t]*)- ");

    private static @Nullable HiveConfig tryParse(String text, Path file) {
        try {
            return YAML.readTree(text) instanceof ObjectNode node ? parse(node, file) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ObjectNode migrate(ObjectNode node, Path file) throws IOException {
        JsonNode versionNode = node.get("ConfigurationVersion");
        int from = versionNode == null ? 0 : versionNode.isInt() ? versionNode.intValue() : -1;
        if (from < 0) throw new HiveConfigException(file, List.of("ConfigurationVersion: expected a whole number"));
        if (from > CONFIGURATION_VERSION) {
            throw new HiveConfigException(file, List.of("ConfigurationVersion " + from + " is from a newer Paperhive; this one reads up to "
                    + CONFIGURATION_VERSION));
        }
        if (from == CONFIGURATION_VERSION) return node;

        ObjectNode migrated = node;
        for (int v = from; v < CONFIGURATION_VERSION; v++) migrated = MIGRATIONS.get(v).apply(migrated);
        Path backup = file.resolveSibling(file.getFileName() + ".v" + from + ".bak");
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(file, YAML.writeValueAsString(migrated));
        LOG.info("Migrated {} from ConfigurationVersion {} to {}; the original is in {}", file, from, CONFIGURATION_VERSION, backup);
        return migrated;
    }

    /** Version 0 (M6): lowercase {@code instances} with {@code name}, {@code port}, {@code root}; no version key. */
    private static ObjectNode migrate0To1(ObjectNode old) {
        ObjectNode node = YAML.createObjectNode();
        node.put("ConfigurationVersion", 1);
        for (Map.Entry<String, JsonNode> e : old.properties()) {
            if (e.getKey().equals("ConfigurationVersion")) continue;
            if (e.getKey().equals("instances") && e.getValue().isArray()) {
                var servers = node.putArray("Servers");
                for (JsonNode server : e.getValue()) {
                    if (!(server instanceof ObjectNode s)) {
                        servers.add(server);
                        continue;
                    }
                    ObjectNode renamed = servers.addObject();
                    for (Map.Entry<String, JsonNode> f : s.properties()) {
                        renamed.set(switch (f.getKey()) {
                            case "name" -> "Name";
                            case "port" -> "Port";
                            case "root" -> "Root";
                            default -> f.getKey();
                        }, f.getValue());
                    }
                }
            } else {
                node.set(e.getKey(), e.getValue());
            }
        }
        return node;
    }

    private static HiveConfig parse(ObjectNode node, Path file) {
        List<String> problems = new ArrayList<>();
        checkKeys(node, TOP_KEYS, "", problems);

        MCSoftware named = software(node, "", MCSoftware.PAPERMC, problems);
        // an unknown Software is reported; carry on as Paper so every other problem is found in the same go
        MCSoftware software = named == null ? MCSoftware.PAPERMC : named;
        MCVersion version = version(node, "", software, software.defaultVersion(), problems);

        Host host = host(node.get("Host"), problems);

        List<Server> servers = new ArrayList<>();
        JsonNode list = node.get("Servers");
        if (list == null || !list.isArray() || list.isEmpty()) {
            problems.add("Servers: expected a list with at least one server (- Name: ..., Port: ...)");
        } else {
            for (int i = 0; i < list.size(); i++) {
                Server server = server(list.get(i), "Servers[" + i + "]", software, version, problems);
                if (server != null) servers.add(server);
            }
            checkUnique(servers, problems);
        }

        if (!problems.isEmpty()) throw new HiveConfigException(file, problems);
        return new HiveConfig(software, version, host, List.copyOf(servers));
    }

    private static Host host(@Nullable JsonNode node, List<String> problems) {
        Host d = Host.DEFAULT;
        if (node == null) return d;
        if (!(node instanceof ObjectNode h)) {
            problems.add("Host: expected keys like Plugins: and Mirror:");
            return d;
        }
        checkKeys(h, HOST_KEYS, "Host.", problems);
        String plugins = string(h, "Plugins", "Host.", d.plugins().toString(), problems);
        String cache = string(h, "Cache", "Host.", d.cache().toString(), problems);
        String mirror = string(h, "Mirror", "Host.", d.mirror(), problems);
        if (mirror != null && !mirror.regionMatches(true, 0, "https://", 0, 8)) {
            problems.add("Host.Mirror: expected an https:// URL, got '" + mirror + "'");
        }
        int stop = number(h, "StopTimeout", 1, 86_400, (int) d.stopTimeout().toSeconds(), problems);
        int memory = number(h, "MemoryWarning", 1, 100, d.memoryWarning(), problems);
        int delay = number(h, "RestartDelay", 0, 86_400, (int) d.restartDelay().toSeconds(), problems);
        int crashes = number(h, "MaxCrashes", 1, 1_000, d.maxCrashes(), problems);
        int window = number(h, "CrashWindow", 1, 86_400 * 7, (int) d.crashWindow().toSeconds(), problems);
        String secret = string(h, "VelocitySecret", "Host.", null, problems);
        String eulaName = string(h, "EULA", "Host.", "Automatic", problems);
        EulaMode eula = d.eula();
        if (eulaName != null) {
            switch (eulaName.toLowerCase(Locale.ROOT)) {
                case "automatic" -> eula = EulaMode.AUTOMATIC;
                case "manual" -> eula = EulaMode.MANUAL;
                default -> problems.add("Host.EULA: expected Automatic or Manual, got '" + eulaName + "'");
            }
        }
        if (plugins == null || cache == null || mirror == null) return d; // reported above
        return new Host(Path.of(plugins), Path.of(cache), mirror.endsWith("/") ? mirror : mirror + "/", Duration.ofSeconds(stop),
                memory, Duration.ofSeconds(delay), crashes, Duration.ofSeconds(window), secret == null ? null : Path.of(secret), eula);
    }

    private static int number(ObjectNode node, String key, int min, int max, int fallback, List<String> problems) {
        JsonNode value = node.get(key);
        if (value == null) return fallback;
        if (value.isInt() && value.intValue() >= min && value.intValue() <= max) return value.intValue();
        problems.add("Host." + key + ": expected a whole number " + min + "-" + max + ", got " + value);
        return fallback;
    }

    /** The {@code Software} of {@code node} ({@code fallback} if unset), or null (reported) if unknown. */
    private static @Nullable MCSoftware software(@NotNull ObjectNode node, @NotNull String prefix, @NotNull MCSoftware fallback, @NotNull List<String> problems) {
        String name = string(node, "Software", prefix, fallback.getName(), problems);
        if (name == null) return null;
        MCSoftware software = MCSoftware.byName(name);
        if (software == null) {
            problems.add(prefix + "Software: unknown '" + name + "', supported: " + Arrays.stream(MCSoftware.values()).map(MCSoftware::getName).toList());
        }
        return software;
    }

    /** The {@code Version} of {@code node} for {@code software} ({@code fallback} if unset), or null (reported) if the mirror lacks it. */
    private static @Nullable MCVersion version(@NotNull ObjectNode node, @NotNull String prefix, @NotNull MCSoftware software, @NotNull MCVersion fallback,
                                               @NotNull List<String> problems) {
        JsonNode value = node.get("Version");
        if (value == null) return fallback;
        // any scalar: an unquoted 1.21 is a YAML number
        String name = value.isValueNode() ? value.asString() : null;
        MCVersion version = name == null ? null : MCVersion.of(software, name);
        if (version == null) {
            problems.add(prefix + "Version: unknown " + software.getName() + " version '" + (name != null ? name : value) + "', supported: "
                    + software.versions().stream().map(MCVersion::getVersion).toList());
        }
        return version;
    }

    private static @Nullable Server server(@NotNull JsonNode node, @NotNull String at, @Nullable MCSoftware hiveSoftware, @Nullable MCVersion hiveVersion,
                                           @NotNull List<String> problems) {
        if (!(node instanceof ObjectNode s)) {
            problems.add(at + ": expected keys like Name: and Port:");
            return null;
        }
        int before = problems.size();
        String name = string(s, "Name", at + ".", null, problems);
        if (!s.has("Name")) problems.add(at + ".Name: missing");
        if (name != null && !NAME.matcher(name).matches()) {
            problems.add(at + ".Name: '" + name + "' may only use letters, digits, - and _ (up to 32)");
        }
        String label = name == null ? at : at + " (" + name + ")";
        checkKeys(s, SERVER_KEYS, label + ".", problems);

        MCSoftware software = hiveSoftware == null ? null : software(s, label + ".", hiveSoftware, problems);
        MCVersion version = null;
        if (software != null) {
            MCVersion fallback = software == hiveSoftware && hiveVersion != null ? hiveVersion : software.defaultVersion();
            version = version(s, label + ".", software, fallback, problems);
            ServerSoftware runs = ServerSoftwares.of(software);
            for (String key : runs.unsupportedKeys()) {
                if (s.has(key)) problems.add(label + "." + key + ": not for " + runs.displayName() + " servers");
            }
        }

        JsonNode portNode = s.get("Port");
        int port = 0;
        if (portNode == null) problems.add(label + ".Port: missing");
        else if (!portNode.isInt() || portNode.intValue() < 1 || portNode.intValue() > 65535) problems.add(label + ".Port: expected 1-65535, got " + portNode);
        else port = portNode.intValue();

        String ip = string(s, "IP", label + ".", null, problems);
        String root = string(s, "Root", label + ".", null, problems);
        String plugins = string(s, "Plugins", label + ".", null, problems);
        List<String> args = strings(s, "Args", label + ".", problems);
        List<String> disabled = strings(s, "DisabledHivePlugins", label + ".", problems);
        List<Path> shared = strings(s, "SharedPlugins", label + ".", problems).stream().map(Path::of).toList();

        boolean autostart = true;
        JsonNode autostartNode = s.get("Autostart");
        if (autostartNode != null) {
            if (autostartNode.isBoolean()) autostart = autostartNode.booleanValue();
            else problems.add(label + ".Autostart: expected true or false, got " + autostartNode);
        }

        Boolean eula = null;
        JsonNode eulaNode = s.get("EULA");
        if (eulaNode != null) {
            if (eulaNode.isBoolean()) eula = eulaNode.booleanValue();
            else problems.add(label + ".EULA: expected true or false, got " + eulaNode);
        }

        ServerInstance.RestartPolicy restart = ServerInstance.RestartPolicy.NEVER;
        String restartName = string(s, "Restart", label + ".", "never", problems);
        if (restartName != null) {
            switch (restartName) {
                case "never" -> {}
                case "on-crash" -> restart = ServerInstance.RestartPolicy.ON_CRASH;
                default -> problems.add(label + ".Restart: expected never or on-crash, got '" + restartName + "'");
            }
        }

        if (problems.size() > before || name == null || software == null || version == null) return null;
        return new Server(name, software, version, port, ip, root == null ? Path.of("instances", name) : Path.of(root),
                plugins == null ? null : Path.of(plugins), args, autostart, restart, disabled, shared, eula);
    }

    private static void checkUnique(List<Server> servers, List<String> problems) {
        Map<String, String> names = new HashMap<>();
        Map<Integer, String> ports = new HashMap<>();
        Map<Path, String> roots = new HashMap<>();
        for (Server s : servers) {
            // case-insensitive: the name is also a folder name, and Windows folders ignore case
            String other = names.putIfAbsent(s.name().toLowerCase(Locale.ROOT), s.name());
            if (other != null) problems.add("Servers: the name " + s.name() + " is used twice" + (other.equals(s.name()) ? "" : " (as " + other + ")"));
            other = ports.putIfAbsent(s.port(), s.name());
            if (other != null) problems.add("Servers: " + other + " and " + s.name() + " both use Port " + s.port());
            other = roots.putIfAbsent(s.root().toAbsolutePath().normalize(), s.name());
            if (other != null) problems.add("Servers: " + other + " and " + s.name() + " share the folder " + s.root());
        }
    }

    /** Unknown keys are errors; one that only differs in case, '-' or '_' gets the right spelling. */
    private static void checkKeys(ObjectNode node, Set<String> known, String prefix, List<String> problems) {
        for (String key : node.propertyNames()) {
            if (known.contains(key)) continue;
            String meant = known.stream().filter(k -> normalize(k).equals(normalize(key))).findFirst().orElse(null);
            problems.add(prefix + key + ": unknown key" + (meant != null ? ", did you mean " + meant + "?" : "; known: " + known.stream().sorted().toList()));
        }
    }

    private static String normalize(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static @Nullable String string(ObjectNode node, String key, String prefix, @Nullable String fallback, List<String> problems) {
        JsonNode value = node.get(key);
        if (value == null) return fallback;
        if (value.isString() && !value.stringValue().isBlank()) return value.stringValue();
        problems.add(prefix + key + ": expected text, got " + value);
        return null;
    }

    private static List<String> strings(ObjectNode node, String key, String prefix, List<String> problems) {
        JsonNode value = node.get(key);
        if (value == null) return List.of();
        List<String> result = new ArrayList<>();
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (item.isString()) result.add(item.stringValue());
                else problems.add(prefix + key + ": expected a list of text, got " + item);
            }
        } else {
            problems.add(prefix + key + ": expected a list, e.g. [a, b], got " + value);
        }
        return List.copyOf(result);
    }

    /** Every problem in the file at once, so they can all be fixed in one go. */
    public static final class HiveConfigException extends RuntimeException {
        HiveConfigException(Path file, List<String> problems) {
            super(file + " has " + problems.size() + " problem(s):\n  - " + String.join("\n  - ", problems));
        }
    }

}
