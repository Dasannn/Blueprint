package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.PresentationConfig;

/** A bounded ring, rather than Bukkit's Gaussian (unbounded) particle spread. */
public record ParticlePoint(double x, double y, double z) {
    public static ParticlePoint at(PresentationConfig.Particles config, int index) {
        double angle = 2 * Math.PI * index / config.count();
        return new ParticlePoint(Math.cos(angle) * config.radius(),
                config.placement().equals("beneath") ? 0.05 : 1.0, Math.sin(angle) * config.radius());
    }
}
