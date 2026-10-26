package org.jellyfin.androidtv.ui.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

/**
 * A subtitle selection made by the user. Can be used to find a matching subtitle stream in other
 * episodes of the same series.
 *
 * A selection with [disabled] set to true is used to restore the explicit choice of no subtitles.
 */
@Serializable
data class SubtitleSelection(
	/**
	 * ISO code of the subtitle language.
	 */
	val language: String? = null,
	/**
	 * Title of the subtitle stream. Preferably set to prefer the exact same stream when matching.
	 */
	val title: String? = null,
	val disabled: Boolean = false,
) {
	/**
	 * Find the subtitle stream in this list that best matches the selection. Matches on both [title]
	 * and [language] if possible, falls back to only matching [language].
	 */
	fun findMatchingStream(streams: List<MediaStream>?): MediaStream? {
		if (disabled) return null

		val subtitles = streams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }

		return subtitles.firstOrNull {
			it.language == language && (it.title == title || it.displayTitle == title)
		} ?: subtitles.firstOrNull { it.language == language }
	}

	/**
	 * Same as [findMatchingStream] but returning the stream index instead of the stream itself.
	 *
	 * Use null when no stream is found and [disabled] is set, which equals the explicit choice of
	 * no subtitles.
	 */
	fun findMatchingStreamIndex(streams: List<MediaStream>?) = when {
		disabled -> null
		else -> findMatchingStream(streams)?.index
	}
}

/**
 * Precedence of the returned selection is the most recently updated one. Selections for previously
 * watched episodes in a series are persisted, any other values are session scoped and lost when
 * the app is stopped.
 */
private val json = Json { ignoreUnknownKeys = true }
private val selectionsSerializer = MapSerializer(String.serializer(), SubtitleSelection.serializer())

private fun selectionKey(seriesId: UUID) = seriesId.toString()

fun UserPreferences.getSeriesSubtitleSelection(seriesId: UUID): SubtitleSelection? {
	val selections = runCatching {
		json.decodeFromString(selectionsSerializer, this@getSeriesSubtitleSelection[UserPreferences.subtitlesSeriesSelections])
	}.getOrNull()

	return selections?.get(selectionKey(seriesId))
}

fun UserPreferences.setSeriesSubtitleSelection(seriesId: UUID, selection: SubtitleSelection?) {
	val selections = runCatching {
		json.decodeFromString(selectionsSerializer, this@setSeriesSubtitleSelection[UserPreferences.subtitlesSeriesSelections]).toMutableMap()
	}.getOrDefault(mutableMapOf())

	selection?.let { selections[selectionKey(seriesId)] = it }
		?: run { selections.remove(selectionKey(seriesId)) }

	// Prevent the preference from growing indefinitely, drop oldest series first
	while (selections.size > MAX_SERIES_SUBTITLE_SELECTIONS) {
		selections.remove(selections.keys.firstOrNull())
	}

	this@setSeriesSubtitleSelection[UserPreferences.subtitlesSeriesSelections] = json.encodeToString(selectionsSerializer, selections)
}

private const val MAX_SERIES_SUBTITLE_SELECTIONS = 30
