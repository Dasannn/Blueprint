package com.dasannn.socialblueprint.feature.update;

import java.util.Objects;

/**
 * An asset attached to a GitHub release (e.g. the plugin jar or its sha256 checksum file).
 */
public record ReleaseAsset(
        String name,
        String downloadUrl,
        long size,
        String contentType
) {
    public ReleaseAsset {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(downloadUrl, "downloadUrl must not be null");
    }
}
