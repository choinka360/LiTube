package com.hhst.youtubelite.cast;

import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.gson.*;
import java.util.*;

/** Commands operate on the TV's actual supported tracks, without rebuilding playback. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class TvRemoteControls {
    static void speed(ExoPlayer player, float value) {
        if (!Float.isFinite(value) || value < 0.25f || value > 2f) throw new IllegalArgumentException("Speed must be between 0.25 and 2");
        player.setPlaybackSpeed(value);
    }
    static void select(ExoPlayer player, int type, String id) {
        var builder = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
        if (id.equals("auto") && type == C.TRACK_TYPE_VIDEO) {
            player.setTrackSelectionParameters(builder.setTrackTypeDisabled(type, false).build()); return;
        }
        if (id.equals("off") && type == C.TRACK_TYPE_TEXT) {
            player.setTrackSelectionParameters(builder.setTrackTypeDisabled(type, true).build()); return;
        }
        var groups = player.getCurrentTracks().getGroups();
        for (int g = 0; g < groups.size(); g++) {
            var group = groups.get(g);
            if (group.getType() != type) continue;
            for (int t = 0; t < group.length; t++) if (id.equals(g + ":" + t) && group.isTrackSupported(t)) {
                player.setTrackSelectionParameters(builder.setTrackTypeDisabled(type, false)
                        .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), t)).build()); return;
            }
        }
        throw new IllegalArgumentException("Track is no longer available. Refresh the TV controls.");
    }
    static void status(ExoPlayer player, JsonObject result) {
        result.addProperty("durationMs", Math.max(0, player.getDuration()));
        result.addProperty("seekable", player.isCurrentMediaItemSeekable());
        result.addProperty("speed", player.getPlaybackParameters().speed);
        result.addProperty("qualityAuto", player.getTrackSelectionParameters().overrides.values().stream().noneMatch(o -> o.getType() == C.TRACK_TYPE_VIDEO));
        JsonArray qualities = new JsonArray(), subtitles = new JsonArray();
        var groups = player.getCurrentTracks().getGroups();
        for (int g = 0; g < groups.size(); g++) {
            var group = groups.get(g);
            if (group.getType() != C.TRACK_TYPE_VIDEO && group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int t = 0; t < group.length; t++) {
                if (!group.isTrackSupported(t)) continue;
                Format f = group.getTrackFormat(t);
                String label;
                if (group.getType() == C.TRACK_TYPE_VIDEO) {
                    if (f.height <= 0) continue;
                    label = f.height + "p" + (f.frameRate > 31 ? " " + Math.round(f.frameRate) + " fps" : "");
                } else label = f.label != null ? f.label : f.language != null ? Locale.forLanguageTag(f.language).getDisplayLanguage() : "Subtitles " + (t + 1);
                JsonObject item = CastClient.body("id", g + ":" + t); item.addProperty("label", label);
                item.addProperty("selected", group.isTrackSelected(t));
                if (group.getType() == C.TRACK_TYPE_VIDEO) { item.addProperty("height", f.height); qualities.add(item); }
                else subtitles.add(item);
            }
        }
        result.add("qualities", qualities); result.add("subtitles", subtitles);
        result.addProperty("subtitlesOff", player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
    }
}

