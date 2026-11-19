package org.abdullahcxd.paperhive.downloader.index;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record IndexSpec(@Nullable String sha256, @Nullable String file) {
}
