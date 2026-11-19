package org.abdullahcxd.paperhive.downloader.index;

import org.jetbrains.annotations.NotNull;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;

public class IndexExtractor {

    private static final JsonMapper mapper = JsonMapper.builder().build();

    public static @NotNull IndexSpec extract(@NotNull Path specPath) {
        return mapper.readValue(specPath, IndexSpec.class);
    }

}
