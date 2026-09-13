package com.hhst.youtubelite.cast;

import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import java.util.*;

/** Preserves YouTube's related-video order; does not invent a recommendation ranking. */
public final class TvSuggestions {
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
}
