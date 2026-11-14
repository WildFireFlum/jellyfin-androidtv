package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class DualSubtitleLayout(
	override val nameRes: Int,
) : PreferenceEnum {
	/**
	 * Render secondary subtitles directly above the primary subtitles.
	 */
	STACKED(R.string.pref_subtitle_layout_stacked),

	/**
	 * Render secondary subtitles near the top of the video.
	 */
	TOP(R.string.pref_subtitle_layout_top),

	/**
	 * Render secondary subtitles on the right half of the video.
	 */
	SIDE(R.string.pref_subtitle_layout_side),
}
