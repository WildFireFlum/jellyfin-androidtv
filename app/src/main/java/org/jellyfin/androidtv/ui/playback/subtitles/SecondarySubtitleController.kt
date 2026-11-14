package org.jellyfin.androidtv.ui.playback.subtitles

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.IOException
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.DualSubtitleLayout
import org.jellyfin.androidtv.ui.playback.getSubtitleMediaStreamCodec
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import timber.log.Timber
import java.util.UUID

/**
 * Renders a secondary subtitle track on top of the video. The track is selected manually by the
 * user or remembered between episodes by persisting the selection.
 *
 * Cues are parsed from the subtitle file of the media stream and synced to the playback position
 * using [updatePosition]. Only external subtitles are supported, media streams that are not
 * served as separate files can not be rendered by this.
 */
@OptIn(UnstableApi::class)
class SecondarySubtitleController(
	private val context: Context,
	private val api: ApiClient,
	private val subtitleView: SubtitleView,
	private val userPreferences: UserPreferences,
) {
	private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
	private val client = OkHttpClient()
	private val parserFactory = DefaultSubtitleParserFactory()

	private var cues: List<CuesWithTiming> = emptyList()
	private var streamGeneration = 0
	private var hasSecondarySubtitle = false
	private var activeCueStartTimeUs: Long? = null
	private var activeCues: List<Cue> = emptyList()
	private var primaryTopZone = 0f
	private var primaryBottomHeight = 0f

	init {
		applyStyle()
		applyLayout()
	}

	private fun applyStyle() {
		val strokeColor = userPreferences[UserPreferences.subtitleTextStrokeColor].toInt()

		subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, userPreferences[UserPreferences.subtitlesSecondaryTextSize])
		subtitleView.setStyle(CaptionStyleCompat(
			userPreferences[UserPreferences.subtitlesTextColor].toInt(),
			// Keep the background and window color transparent so the video is visible behind the secondary subtitles
			0,
			0,
			CaptionStyleCompat.EDGE_TYPE_OUTLINE,
			strokeColor,
			Typeface.DEFAULT,
		))
	}

	private fun applyLayout() {
		val metrics = context.resources.displayMetrics
		val params = FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT,
			FrameLayout.LayoutParams.MATCH_PARENT,
		)

		if (userPreferences[UserPreferences.subtitlesSecondaryLayout] == DualSubtitleLayout.SIDE) {
			params.width = metrics.widthPixels / 2
			params.gravity = Gravity.END or Gravity.BOTTOM
		}

		// All horizontal cues are explicitly positioned in [renderActiveCues].
		subtitleView.setBottomPaddingFraction(0f)
		subtitleView.layoutParams = params
	}

	/**
	 * Update the rendered cues to the ones at the current playback position, in milliseconds.
	 * Call whenever the position changes, e.g. from a progress loop.
	 */
	fun updatePosition(positionMs: Long) {
		val positionUs = positionMs * 1000

		val activeCue = cues.lastOrNull {
			it.startTimeUs <= positionUs && (it.endTimeUs <= 0 || it.endTimeUs == C.TIME_UNSET || it.endTimeUs > positionUs)
		}

		if (activeCue?.startTimeUs != activeCueStartTimeUs) {
			SecondarySubtitleDiagnostics.record(
				if (activeCue == null) "Cue cleared at positionMs=$positionMs"
				else "Cue activated at positionMs=$positionMs, startUs=${activeCue.startTimeUs}, endUs=${activeCue.endTimeUs}, cueCount=${activeCue.cues.size}"
			)
			activeCueStartTimeUs = activeCue?.startTimeUs
			activeCues = activeCue?.cues.orEmpty()
			renderActiveCues()
		}
	}

	/**
	 * Keep secondary subtitles out of the upper area occupied by explicitly positioned primary
	 * cues, and reserve the normal bottom subtitle height for the stacked layout.
	 */
	fun onPrimaryCues(primaryCues: List<Cue>) {
		val cueZones = primaryCues.map { it to topZoneOf(it) }
		val topZone = cueZones.mapNotNull { it.second }.filter { it <= 0.5f }.maxOrNull() ?: 0f
		val bottomHeight = cueZones
			.filter { (_, zone) -> zone == null || zone > 0.5f }
			.sumOf { (cue, _) -> estimateTextHeight(cue).toDouble() }
			.toFloat()
			.coerceAtMost(0.3f)

		if (topZone != primaryTopZone || bottomHeight != primaryBottomHeight) {
			primaryTopZone = topZone
			primaryBottomHeight = bottomHeight
			renderActiveCues()
		}
	}

	private fun renderActiveCues() {
		val layout = userPreferences[UserPreferences.subtitlesSecondaryLayout]
		val offset = userPreferences[UserPreferences.subtitlesOffsetPosition]
		val secondaryLineHeight = viewportLineHeight(userPreferences[UserPreferences.subtitlesSecondaryTextSize])

		subtitleView.setCues(activeCues.mapIndexed { index, cue ->
			if (cue.verticalType != Cue.TYPE_UNSET) return@mapIndexed cue

			cue.buildUpon().apply {
				setPosition(0.5f)
				setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
				when (layout) {
					DualSubtitleLayout.STACKED -> {
						setLine(
							(1f - offset - primaryBottomHeight - 0.02f - index * secondaryLineHeight).coerceIn(0.05f, 0.95f),
							Cue.LINE_TYPE_FRACTION,
						)
						setLineAnchor(Cue.ANCHOR_TYPE_END)
						setSize(0.9f)
					}
					DualSubtitleLayout.TOP -> {
						val top = if (primaryTopZone > 0.04f) primaryTopZone + 0.02f else 0.06f
						setLine((top + index * secondaryLineHeight).coerceAtMost(0.9f), Cue.LINE_TYPE_FRACTION)
						setLineAnchor(Cue.ANCHOR_TYPE_START)
						setSize(0.9f)
					}
					DualSubtitleLayout.SIDE -> {
						if (primaryTopZone > 0.04f) {
							setLine((primaryTopZone + 0.02f + index * secondaryLineHeight).coerceAtMost(0.9f), Cue.LINE_TYPE_FRACTION)
							setLineAnchor(Cue.ANCHOR_TYPE_START)
						} else {
							setLine((1f - offset - index * secondaryLineHeight).coerceIn(0.05f, 0.95f), Cue.LINE_TYPE_FRACTION)
							setLineAnchor(Cue.ANCHOR_TYPE_END)
						}
						setSize(0.94f)
					}
				}
			}.build()
		})
	}

	private fun topZoneOf(cue: Cue): Float? {
		val height = if (cue.bitmap != null && cue.bitmapHeight != Cue.DIMEN_UNSET) {
			cue.bitmapHeight.coerceIn(0.02f, 1f)
		} else if (cue.text != null) {
			estimateTextHeight(cue)
		} else {
			return null
		}

		return when {
			cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION ->
				(cue.line.coerceAtLeast(0f) + height).coerceIn(0f, 1f)
			cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_NUMBER && cue.line >= 0f ->
				(cue.line * viewportLineHeight(userPreferences[UserPreferences.subtitlesTextSize]) + height).coerceIn(0f, 1f)
			else -> null
		}
	}

	private fun estimateTextHeight(cue: Cue): Float {
		val lineCount = cue.text?.toString()?.count { it == '\n' }?.plus(1) ?: 1
		return (viewportLineHeight(userPreferences[UserPreferences.subtitlesTextSize]) * lineCount).coerceIn(0.01f, 0.35f)
	}

	private fun viewportLineHeight(textSize: Float): Float {
		val metrics = context.resources.displayMetrics
		return (textSize * metrics.density * 1.4f / metrics.heightPixels).coerceIn(0.01f, 0.35f)
	}

	fun hasSecondarySubtitle() = hasSecondarySubtitle

	/**
	 * Set the media stream to render, or null to stop rendering secondary subtitles. Embedded text
	 * streams are converted to WebVTT through Jellyfin's subtitle endpoint.
	 */
	fun setStream(stream: MediaStream?, itemId: UUID?, mediaSourceId: String?) {
		val generation = ++streamGeneration
		cues = emptyList()
		hasSecondarySubtitle = false
		activeCueStartTimeUs = null
		activeCues = emptyList()
		subtitleView.setCues(emptyList())
		applyStyle()
		applyLayout()
		SecondarySubtitleDiagnostics.record("Renderer stream set: generation=$generation, ${SecondarySubtitleDiagnostics.describeStream(stream)}")

		val selectedStream = stream ?: run {
			SecondarySubtitleDiagnostics.record("Renderer stopped: no secondary subtitle stream selected")
			return
		}
		val delivery = when {
			selectedStream.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL && selectedStream.deliveryUrl != null -> SubtitleDelivery(
				path = requireNotNull(selectedStream.deliveryUrl),
				mimeType = getSubtitleMediaStreamCodec(selectedStream),
			)
			selectedStream.deliveryMethod == SubtitleDeliveryMethod.EMBED && itemId != null && mediaSourceId != null -> SubtitleDelivery(
				path = "Videos/$itemId/$mediaSourceId/Subtitles/${selectedStream.index}/Stream.vtt",
				mimeType = "text/vtt",
			)
			else -> null
		}
		if (delivery == null) {
			Timber.w("Secondary subtitle stream %s has no independently renderable delivery path", selectedStream.index)
			SecondarySubtitleDiagnostics.record("Renderer stopped: stream has no external or convertible embedded delivery path")
			return
		}

		val url = api.createUrl(delivery.path, emptyMap(), emptyMap(), true)
		val mimeType = delivery.mimeType
		val format = Format.Builder()
			.setSampleMimeType(mimeType)
			.build()
		if (!parserFactory.supportsFormat(format)) {
			Timber.w("Secondary subtitle stream %s uses an unsupported format", selectedStream.index)
			SecondarySubtitleDiagnostics.record("Renderer stopped: unsupported subtitle MIME type=$mimeType")
			return
		}

		Timber.i("Loading secondary subtitle track %s", selectedStream.index)
		SecondarySubtitleDiagnostics.record("Download started: stream=${selectedStream.index}, path=${Uri.parse(url).encodedPath ?: "none"}, mimeType=$mimeType")
		hasSecondarySubtitle = true

		scope.launch {
			val parsed = withContext(Dispatchers.IO) {
				runCatching {
					client.newCall(Request.Builder().url(url).build()).execute().use { response ->
						SecondarySubtitleDiagnostics.record(
							"Download response: stream=${selectedStream.index}, status=${response.code}, contentType=${response.body?.contentType() ?: "none"}, contentLength=${response.body?.contentLength() ?: -1}"
						)
						if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
						response.body?.byteStream()?.readBytes()?.also {
							SecondarySubtitleDiagnostics.record("Download completed: stream=${selectedStream.index}, bytes=${it.size}")
						} ?: throw EOFException()
					}
				}.onFailure {
					Timber.e(it, "Failed to download secondary subtitle track %s", selectedStream.index)
					SecondarySubtitleDiagnostics.record("Download failed: stream=${selectedStream.index}, error=${it.javaClass.simpleName}")
				}
					.getOrNull()
					// Ignored the return value of parse, the consumer collects the output
					?.let { bytes ->
						runCatching {
							val parsedCues = mutableListOf<CuesWithTiming>()
							val parser = parserFactory.create(format)
							SecondarySubtitleDiagnostics.record("Parsing started: stream=${selectedStream.index}, parser=${parser.javaClass.simpleName}")
							parser.parse(bytes, SubtitleParser.OutputOptions.allCues(), parsedCues::add)
							parsedCues.toList()
						}.onFailure {
							Timber.e(it, "Failed to parse secondary subtitle track %s", selectedStream.index)
							SecondarySubtitleDiagnostics.record("Parsing failed: stream=${selectedStream.index}, error=${it.javaClass.simpleName}")
						}.getOrNull()
					}
			}

			if (generation != streamGeneration) {
				SecondarySubtitleDiagnostics.record("Discarded stale result: stream=${selectedStream.index}, generation=$generation")
				return@launch
			}

			if (parsed != null && parsed.isNotEmpty()) {
				Timber.i("Loaded %s secondary subtitle cues", parsed.size)
				cues = parsed
				SecondarySubtitleDiagnostics.record(
					"Parsing completed: stream=${selectedStream.index}, cues=${parsed.size}, firstStartUs=${parsed.first().startTimeUs}, lastEndUs=${parsed.last().endTimeUs}"
				)
			} else if (parsed != null) {
				Timber.w("Secondary subtitle track %s is empty", selectedStream.index)
				hasSecondarySubtitle = false
				SecondarySubtitleDiagnostics.record("Renderer stopped: parsed subtitle track is empty")
			} else {
				hasSecondarySubtitle = false
				SecondarySubtitleDiagnostics.record("Renderer stopped: subtitle download or parsing failed")
			}
		}
	}

	fun release() {
		streamGeneration++
		hasSecondarySubtitle = false
		SecondarySubtitleDiagnostics.record("Renderer released")
		scope.cancel()
		client.dispatcher.executorService.shutdown()
	}

	private data class SubtitleDelivery(
		val path: String,
		val mimeType: String,
	)
}
