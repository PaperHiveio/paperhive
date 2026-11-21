package org.abdullahcxd.paperhive.host.eula;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Agrees to the Minecraft EULA for a server by writing {@code eula=true} into its {@code eula.txt}. */
public final class EULA {

    private EULA() {}

    /** @return whether {@code eula.txt} in {@code root} had to be written (created, or changed to true) */
    public static boolean agree(@NotNull Path root) throws IOException {
        Path eula = root.resolve("eula.txt");
        List<String> lines = Files.exists(eula) ? new ArrayList<>(Files.readAllLines(eula)) : new ArrayList<>();
        boolean changed = false;
        boolean hasKey = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.toLowerCase().startsWith("eula=")) {
                hasKey = true;
                if (!line.equalsIgnoreCase("eula=true")) {
                    lines.set(i, "eula=true");
                    changed = true;
                }
            }
        }

        if (!hasKey) {
            lines.add("eula=true");
            changed = true;
        }

        if (changed) {
            Files.createDirectories(root);
            Files.write(eula, lines);
        }
        return changed;
    }
}
