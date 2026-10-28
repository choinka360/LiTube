package com.hhst.youtubelite.cast;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import java.util.*;

/** Preserves YouTube's related-video order; does not invent a recommendation ranking. */
public final class TvSuggestions {
    private TvSuggestions() {}
    public static List<StreamInfoItem> load(String videoId) throws Exception {
        var extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=" + CastProtocol.videoId(videoId));
        extractor.fetchPage();
        var related = extractor.getRelatedItems();
        List<StreamInfoItem> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        if (related != null) for (var item : related.getItems()) {
            if (item instanceof StreamInfoItem stream && seen.add(stream.getUrl())) {
                result.add(stream); if (result.size() == 20) break;
            }
        }
        return result;
    }
    public static List<TvVideo> related(String videoId) throws Exception { return videos(load(videoId)); }
    /** YouTube search restricted to videos. */
    public static List<TvVideo> search(String query) throws Exception {
        var extractor = ServiceList.YouTube.getSearchExtractor(query, List.of("videos"), "");
        extractor.fetchPage();
        return videos(extractor.getInitialPage().getItems());
    }
    /** The service's default kiosk (trending), when YouTube still offers one. */
    public static List<TvVideo> trending() throws Exception {
        var extractor = ServiceList.YouTube.getKioskList().getDefaultKioskExtractor();
        extractor.fetchPage();
        return videos(extractor.getInitialPage().getItems());
    }
    static List<TvVideo> videos(List<? extends InfoItem> items) {
        List<TvVideo> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (InfoItem item : items) {
            if (!(item instanceof StreamInfoItem stream)) continue;
            TvVideo video = TvVideo.from(stream);
            if (video != null && seen.add(video.id())) result.add(video);
            if (result.size() == 24) break;
        }
        return result;
    }
}
