package com.hhst.youtubelite.cast;

import androidx.annotation.Keep;
import java.util.List;
import java.util.Locale;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

/** A video card shown on the TV. */
@Keep
public record TvVideo(String id, String title, String channel, String thumbnail, long durationSec, String meta, boolean live) {
    public String url() { return "https://www.youtube.com/watch?v=" + id; }
    public static TvVideo of(String id, String title, String channel, long durationSec) {
        return new TvVideo(id, title == null ? "" : title, channel == null ? "" : channel, thumbnail(id), durationSec, "", false);
    }
    /** Returns null for items that are not single playable videos. */
    public static TvVideo from(StreamInfoItem item) {
        String id;
        try { id = CastProtocol.videoId(item.getUrl()); } catch (IllegalArgumentException e) { return null; }
        boolean live = item.getStreamType() == StreamType.LIVE_STREAM;
        StringBuilder meta = new StringBuilder();
        if (live) meta.append("LIVE");
        else if (item.getViewCount() >= 0) meta.append(count(item.getViewCount())).append(" views");
        String date = item.getTextualUploadDate();
        if (!live && date != null && !date.isBlank()) meta.append(meta.length() > 0 ? " • " : "").append(date);
        return new TvVideo(id, item.getName(), item.getUploaderName() == null ? "" : item.getUploaderName(), best(item.getThumbnails(), id), Math.max(0, item.getDuration()), meta.toString(), live);
    }
    static String thumbnail(String id) { return "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg"; }
    private static String best(List<Image> images, String id) {
        String result = null; int height = -1;
        if (images != null) for (Image image : images) {
            int h = image.getHeight();
            if (result == null || (h <= 400 && h > height)) { result = image.getUrl(); height = h; }
        }
        return result == null ? thumbnail(id) : result;
    }
    static String count(long value) {
        if (value < 1000) return Long.toString(value);
        if (value < 1_000_000) return compact(value / 1000.0) + "K";
        if (value < 1_000_000_000) return compact(value / 1_000_000.0) + "M";
        return compact(value / 1_000_000_000.0) + "B";
    }
    private static String compact(double value) { return value >= 10 ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", Math.floor(value * 10) / 10).replace(".0", ""); }
    public static String clock(long seconds) {
        seconds = Math.max(0, seconds);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
