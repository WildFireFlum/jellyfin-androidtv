package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.androidtv.ui.playback.getCurrentSubtitleSelection
import org.jellyfin.androidtv.ui.playback.setSecondarySubtitleIndex
import org.jellyfin.androidtv.ui.playback.setSubtitleIndex
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

class ClosedCaptionsAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private var popup: PopupMenu? = null

	init {
		initializeWithIcon(R.drawable.ic_select_subtitle)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		if (playbackController.currentStreamInfo == null) {
			Timber.w("StreamInfo null trying to obtain subtitles")
			Toast.makeText(context, "Unable to obtain subtitle info", Toast.LENGTH_LONG).show()
			return
		}

		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		showPopup(playbackController, videoPlayerAdapter, context, view, secondary = false)
	}

	private fun showPopup(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
		secondary: Boolean,
	) {
		// Detach the dismiss listener so cleaning up the previous popup does not toggle the fade
		popup?.setOnDismissListener(null)
		removePopup()
		popup = PopupMenu(context, view, Gravity.END).apply {
			with(menu) {
				val selection = playbackController.getCurrentSubtitleSelection()
				val secondaryIndex = selection?.secondary?.findMatchingStream(playbackController.currentMediaSource.mediaStreams)?.index

				var order = 0
				add(0, -1, order++, context.getString(R.string.lbl_none)).apply {
					isChecked = if (secondary) secondaryIndex == null else playbackController.subtitleStreamIndex == -1
				}

				for (sub in playbackController.currentMediaSource.mediaStreams.orEmpty()) {
					if (sub.type != MediaStreamType.SUBTITLE) continue

					add(0, sub.index, order++, sub.displayTitle).apply {
						isChecked = if (secondary) secondaryIndex == sub.index else sub.index == playbackController.subtitleStreamIndex
					}
				}

				if (!secondary) {
					add(0, SECONDARY_SUBTITLE_MENU_ITEM, order++, context.getString(R.string.lbl_secondary_subtitle_track)).apply {
						val secondaryLabel = selection?.secondary?.title ?: selection?.secondary?.language
						if (secondaryLabel != null) title = String.format("%s (%s)", title, secondaryLabel)
					}
				}

				setGroupCheckable(0, true, false)
			}
			setOnDismissListener {
				videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
				popup = null
			}
			setOnMenuItemClickListener { item ->
				when (item.itemId) {
					SECONDARY_SUBTITLE_MENU_ITEM -> showPopup(playbackController, videoPlayerAdapter, context, view, secondary = true)
					-1 -> if (secondary) playbackController.setSecondarySubtitleIndex(-1) else playbackController.setSubtitleIndex(-1)
					else -> if (secondary) playbackController.setSecondarySubtitleIndex(item.itemId) else playbackController.setSubtitleIndex(item.itemId)
				}
				true
			}
		}
		popup?.show()
	}

	companion object {
		private const val SECONDARY_SUBTITLE_MENU_ITEM = -2
	}

	fun removePopup() {
		popup?.dismiss()
	}
}
