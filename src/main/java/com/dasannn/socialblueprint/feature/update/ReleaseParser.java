package com.dasannn.socialblueprint.feature.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses GitHub releases JSON responses using Gson.
 */
public final class ReleaseParser {

    private ReleaseParser() {}

    public static ReleaseInfo parseRelease(String jsonString) {
        JsonObject obj = JsonParser.parseString(jsonString).getAsJsonObject();
        String tagName = obj.has("tag_name") && !obj.get("tag_name").isJsonNull()
                ? obj.get("tag_name").getAsString() : "";
        String name = obj.has("name") && !obj.get("name").isJsonNull()
                ? obj.get("name").getAsString() : tagName;
        String body = obj.has("body") && !obj.get("body").isJsonNull()
                ? obj.get("body").getAsString() : "";
        boolean prerelease = obj.has("prerelease") && !obj.get("prerelease").isJsonNull()
                && obj.get("prerelease").getAsBoolean();

        List<ReleaseAsset> assets = new ArrayList<>();
        if (obj.has("assets") && obj.get("assets").isJsonArray()) {
            JsonArray assetArray = obj.getAsJsonArray("assets");
            for (JsonElement elem : assetArray) {
                if (elem.isJsonObject()) {
                    JsonObject a = elem.getAsJsonObject();
                    String assetName = a.has("name") && !a.get("name").isJsonNull()
                            ? a.get("name").getAsString() : "";
                    String downloadUrl = a.has("browser_download_url") && !a.get("browser_download_url").isJsonNull()
                            ? a.get("browser_download_url").getAsString() : "";
                    long size = a.has("size") && !a.get("size").isJsonNull()
                            ? a.get("size").getAsLong() : 0L;
                    String contentType = a.has("content_type") && !a.get("content_type").isJsonNull()
                            ? a.get("content_type").getAsString() : "";

                    if (!assetName.isEmpty() && !downloadUrl.isEmpty()) {
                        assets.add(new ReleaseAsset(assetName, downloadUrl, size, contentType));
                    }
                }
            }
        }

        return new ReleaseInfo(tagName, name, body, prerelease, assets);
    }
}
