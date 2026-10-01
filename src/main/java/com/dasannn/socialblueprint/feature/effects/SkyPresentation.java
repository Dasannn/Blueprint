package com.dasannn.socialblueprint.feature.effects;

/** Values only; renderer restores a dimension only while its override is still owned. */
public record SkyPresentation(long previousTimeOffset, boolean previousTimeRelative, String previousWeather,
                              long ownedTimeOffset, boolean ownedTimeRelative, String ownedWeather,
                              boolean changesTime, boolean changesWeather) {
    public boolean ownsTime(long offset, boolean relative) {
        return changesTime && ownedTimeOffset == offset && ownedTimeRelative == relative;
    }
    public boolean ownsWeather(String weather) {
        return changesWeather && java.util.Objects.equals(ownedWeather, weather);
    }
    public boolean resetTime(boolean sameWorld) {
        return !sameWorld || previousTimeRelative && previousTimeOffset == 0;
    }
    public boolean resetWeather(boolean sameWorld) {
        return !sameWorld || previousWeather == null;
    }
}
