package com.dasannn.socialblueprint.feature.update;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Representation of a published GitHub release per SB-070.
 */
public record ReleaseInfo(
        String tagName,
        String name,
        String body,
        boolean prerelease,
        List<ReleaseAsset> assets
) {
    public ReleaseInfo {
        Objects.requireNonNull(tagName, "tagName must not be null");
        assets = assets != null ? Collections.unmodifiableList(assets) : List.of();
    }

    /**
     * Resolves the plugin jar asset attached to this release.
     */
    public Optional<ReleaseAsset> findJarAsset() {
        for (ReleaseAsset asset : assets) {
            if (asset.name().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return Optional.of(asset);
            }
        }
        return Optional.empty();
    }
}
