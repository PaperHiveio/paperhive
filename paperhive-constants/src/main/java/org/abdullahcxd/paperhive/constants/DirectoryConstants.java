package org.abdullahcxd.paperhive.constants;

import java.nio.file.Path;

public final class DirectoryConstants {

    private DirectoryConstants() {}

    public static final Path CACHE_DIRECTORY_PATH = Path.of(".paperhive-cache");

    // own subdirectory: PaperclipCache prunes every sibling entry in its root
    public static final Path PAPERCLIP_CACHE_DIRECTORY_PATH = CACHE_DIRECTORY_PATH.resolve("paperclip");

}
