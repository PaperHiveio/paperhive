package org.abdullahcxd.paperhive.downloader.index;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;

public class IndexExtractor {

    private static final JsonMapper mapper = JsonMapper.builder().build();

    public static IndexSpec extract(Path specPath) {
        return mapper.readValue(specPath, IndexSpec.class);
    }

}
