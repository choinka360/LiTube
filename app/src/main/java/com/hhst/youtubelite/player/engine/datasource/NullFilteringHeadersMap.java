package com.hhst.youtubelite.player.engine.datasource;

import androidx.annotation.NonNull;

import com.google.common.collect.ForwardingMap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP response headers without HttpURLConnection's null-key status line. */
public final class NullFilteringHeadersMap extends ForwardingMap<String, List<String>> {
    private final Map<String, List<String>> headers;

    public NullFilteringHeadersMap(final Map<String, List<String>> headers) {
        // Filter once so every map view has consistent size and contents. Custom
        // toArray implementations using new ArrayList<>(this) recurse indefinitely.
        Map<String, List<String>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        this.headers = Collections.unmodifiableMap(filtered);
    }

    @NonNull
    @Override
    protected Map<String, List<String>> delegate() {
        return headers;
    }
}
