package com.hhst.youtubelite.player.engine;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;

/**
 * Component that handles app logic.
 */
@OptIn(markerClass = UnstableApi.class)
class PlayerLoadControl {
	private PlayerLoadControl() {
	}

	static DefaultLoadControl create() {
		return new DefaultLoadControl.Builder()
						.setBufferDurationsMs(
										90_000,
										120_000,
										2_500,
										8_000
						)
						// Keep more network reserve without letting HD buffering exhaust the heap.
						.setTargetBufferBytes(64 * 1024 * 1024)
						.setPrioritizeTimeOverSizeThresholds(false)
						.build();
	}
}
