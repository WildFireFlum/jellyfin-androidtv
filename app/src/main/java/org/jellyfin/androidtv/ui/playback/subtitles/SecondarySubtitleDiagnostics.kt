package org.jellyfin.androidtv.ui.playback.subtitles

import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.sdk.model.api.MediaStream
import java.util.ArrayDeque

/**
 * Bounded trace for the independently rendered subtitle path. The trace is kept in memory so it
 * can be uploaded through Jellyfin's authenticated client-log endpoint after reproducing an issue.
 */
object SecondarySubtitleDiagnostics {
	private const val MAX_EVENTS = 100
	private val events = ArrayDeque<String>(MAX_EVENTS)
	private var eventNumber = 0

	@Synchronized
	fun record(message: String) {
		if (events.size == MAX_EVENTS) events.removeFirst()
		events.addLast("${++eventNumber}: $message")
	}

	@Synchronized
	fun createReport() = buildString {
		appendLine("---")
		appendLine("client: Jellyfin for Android TV")
		appendLine("client_version: ${BuildConfig.VERSION_NAME}")
		appendLine("type: secondary_subtitle_diagnostics")
		appendLine("format: markdown")
		appendLine("---")
		appendLine()
		appendLine("Recent secondary subtitle trace (up to $MAX_EVENTS events since app launch).")
		appendLine("This report excludes subtitle contents, complete URLs, and access tokens.")
		appendLine()
		appendLine("```text")
		if (events.isEmpty()) appendLine("No secondary subtitle events have been recorded.")
		else events.forEach(::appendLine)
		appendLine("```")
	}

	fun describeStream(stream: MediaStream?) = when (stream) {
		null -> "none"
		else -> "index=${stream.index}, language=${stream.language ?: "none"}, " +
			"title=${stream.title ?: stream.displayTitle ?: "none"}, delivery=${stream.deliveryMethod ?: "none"}, " +
			"deliveryUrl=${if (stream.deliveryUrl == null) "missing" else "present"}"
	}
}
