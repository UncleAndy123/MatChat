package org.matchat.feature.timeline

import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.matchat.core.model.EventId
import org.matchat.core.model.ReactionSummary
import org.matchat.core.model.SeenBy
import org.matchat.core.ui.theme.themeColor
import org.matchat.core.ui.R as UiR

/** S9 bubble alignment: own messages trail (right), others lead (left) — the
 *  bubble's background (accent stripe on the matching edge) and the
 *  timestamp row below it both flip together. A top-level function (not a
 *  private adapter method) so MessageRowScreenshotTest can exercise the same
 *  binding logic the real adapter uses, not a re-typed copy of it. Shared by
 *  MessageVH and ImageVH; item_utd.xml/item_attachment.xml keep their
 *  existing plain row look (out of scope for this pass). */
internal fun bindBubbleSide(bubble: LinearLayout, time: TextView, isOwn: Boolean) {
    bubble.setBackgroundResource(if (isOwn) UiR.drawable.bubble_own else UiR.drawable.bubble_received)
    val gravity = if (isOwn) Gravity.END else Gravity.START
    (bubble.layoutParams as LinearLayout.LayoutParams).gravity = gravity
    (time.layoutParams as LinearLayout.LayoutParams).gravity = gravity
}

/** Pinned messages round: the compact "prefix the time text" idiom sendGlyph
 *  already uses, reused rather than adding a new View — 📌 needs no room of
 *  its own on a 240dp-wide screen. */
internal fun withPinPrefix(isPinned: Boolean, text: String): String = if (isPinned) "📌 $text" else text

/**
 * Timeline rows: text messages, images, attachments, day/state separators.
 * Focusable rows are the CENTER target; separators are not. DiffUtil keeps scroll
 * cheap. Image bytes are loaded by the Fragment via [onImageBind].
 */
internal class TimelineAdapter(
    private val onMessageFocused: (EventId) -> Unit,
    private val onFixEncryption: (EventId) -> Unit,
    private val onMessageActivated: (TimelineRow.Message) -> Unit,
    private val onImageBind: (EventId, ImageView) -> Unit,
    private val onImageActivated: (TimelineRow.Image) -> Unit,
    private val onAttachmentActivated: (TimelineRow.Attachment) -> Unit,
    private val onVoiceBubbleActivated: (TimelineRow.VoiceBubble) -> Unit,
    /** Binds a sender avatar (Avatars round): url, name, user id, target —
     *  the name/id are the no-avatar-fallback's color+initial source
     *  (AvatarFallback round). */
    private val onAvatarBind: (String?, String, String, ImageView) -> Unit,
    /** Populates the "seen by" row with one small avatar per entry. */
    private val onSeenByBind: (List<SeenBy>, LinearLayout) -> Unit,
    /** Populates the reaction-chip row (display-only — see item_message.xml's
     *  header comment on why chips aren't individually tappable). */
    private val onReactionsBind: (List<ReactionSummary>, LinearLayout) -> Unit,
) : ListAdapter<TimelineRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is TimelineRow.Message -> TYPE_MESSAGE
        is TimelineRow.Image -> TYPE_IMAGE
        is TimelineRow.Attachment -> TYPE_ATTACHMENT
        is TimelineRow.VoiceBubble -> TYPE_VOICE
        is TimelineRow.DaySeparator -> TYPE_DAY
        is TimelineRow.UnableToDecrypt -> TYPE_UTD
        is TimelineRow.State -> TYPE_STATE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val holder = when (viewType) {
            TYPE_MESSAGE -> MessageVH(inflater.inflate(R.layout.item_message, parent, false))
            TYPE_IMAGE -> ImageVH(inflater.inflate(R.layout.item_message_image, parent, false))
            TYPE_ATTACHMENT -> AttachmentVH(inflater.inflate(R.layout.item_attachment, parent, false))
            TYPE_VOICE -> VoiceBubbleVH(inflater.inflate(R.layout.item_voice_bubble, parent, false))
            TYPE_DAY -> SimpleVH(inflater.inflate(R.layout.item_day, parent, false))
            TYPE_UTD -> UtdVH(inflater.inflate(R.layout.item_utd, parent, false))
            else -> SimpleVH(inflater.inflate(R.layout.item_state, parent, false))
        }
        // Long-message round: every row type's focusable unit is its whole
        // itemView, and this is the one place all of them pass through, so
        // wire the oversized-row scroll intercept here once rather than per
        // ViewHolder class. See onRowKey's own doc comment.
        holder.itemView.setOnKeyListener(::onRowKey)
        return holder
    }

    /** Lets a message bubble taller than the visible list area scroll by a
     *  small fixed step per DOWN/UP press instead of jumping straight past
     *  its content to the next/previous row (on-device report: a long
     *  message "jumps from the bottom of the message bubble to the top
     *  without letting me read what is in between" — the default
     *  View.requestRectangleOnScreen() behavior on focus change). UP/DOWN
     *  never reach LogicalKeyReceiver (MainActivity.dispatchKeyEvent hands
     *  them straight to the platform's focus search), so the only
     *  interception point is a key listener on the currently focused row
     *  itself — a KeyEvent is offered to the focused View before any
     *  default focus-search behavior runs.
     *
     *  Consumes the key only while there's more of *this* row's content
     *  off-screen in the pressed direction (checked via the row's current
     *  top/bottom relative to the RecyclerView's own bounds — LinearLayoutManager
     *  lays children out with real offsets, not a canvas translate, so no
     *  separate scroll-position bookkeeping is needed); once exhausted,
     *  returns false and lets the platform's normal focus search move to
     *  the next/previous row exactly as before. Short rows (day separators,
     *  state rows, any message that already fits) never trip the overflow
     *  check, so they're completely unaffected. */
    private fun onRowKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val rv = view.parent as? RecyclerView ?: return false
        val stepPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_IN,
            SCROLL_STEP_INCHES,
            view.resources.displayMetrics,
        ).toInt()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val overflow = view.bottom - rv.height
                (overflow > OVERFLOW_SLOP_PX).also { if (it) rv.scrollBy(0, minOf(stepPx, overflow)) }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val overflow = -view.top
                (overflow > OVERFLOW_SLOP_PX).also { if (it) rv.scrollBy(0, -minOf(stepPx, overflow)) }
            }
            else -> false
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is TimelineRow.Message -> (holder as MessageVH).bind(row)
            is TimelineRow.Image -> (holder as ImageVH).bind(row)
            is TimelineRow.Attachment -> (holder as AttachmentVH).bind(row)
            is TimelineRow.VoiceBubble -> (holder as VoiceBubbleVH).bind(row)
            is TimelineRow.DaySeparator -> (holder as SimpleVH).bind(row.label)
            is TimelineRow.UnableToDecrypt -> (holder as UtdVH).bind(row)
            is TimelineRow.State -> (holder as SimpleVH).bind(row.text)
        }
    }

    inner class MessageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val bubble: LinearLayout = view.findViewById(R.id.message_bubble)
        private val senderRow: View = view.findViewById(R.id.message_sender_row)
        private val senderAvatar: ImageView = view.findViewById(R.id.message_sender_avatar)
        private val sender: TextView = view.findViewById(R.id.message_sender)
        private val body: TextView = view.findViewById(R.id.message_body)
        private val time: TextView = view.findViewById(R.id.message_time)
        private val reactions: LinearLayout = view.findViewById(R.id.message_reactions)
        private val seenBy: LinearLayout = view.findViewById(R.id.message_seen_by)

        fun bind(row: TimelineRow.Message) {
            senderRow.isVisible = row.senderName != null
            sender.text = row.senderName.orEmpty()
            sender.setTextColor(org.matchat.core.ui.media.AvatarFallback.colorFor(row.senderId))
            if (row.senderName != null) {
                onAvatarBind(row.senderAvatarUrl, row.senderName, row.senderId, senderAvatar)
            }
            body.text = row.body
            val timeText = if (row.sendGlyph.isEmpty()) row.time else "${row.time} ${row.sendGlyph}"
            time.text = withPinPrefix(row.isPinned, timeText)
            bindBubbleSide(bubble, time, row.isOwn)
            reactions.isVisible = row.reactions.isNotEmpty()
            if (reactions.isVisible) onReactionsBind(row.reactions, reactions)
            seenBy.isVisible = row.seenBy.isNotEmpty()
            if (seenBy.isVisible) onSeenByBind(row.seenBy, seenBy)
            itemView.setOnFocusChangeListener { _, has -> if (has) onMessageFocused(row.eventId) }
            itemView.setOnClickListener { onMessageActivated(row) }
        }
    }

    inner class ImageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val bubble: LinearLayout = view.findViewById(R.id.image_bubble)
        private val senderRow: View = view.findViewById(R.id.image_sender_row)
        private val senderAvatar: ImageView = view.findViewById(R.id.image_sender_avatar)
        private val sender: TextView = view.findViewById(R.id.image_sender)
        private val image: ImageView = view.findViewById(R.id.message_image)
        private val caption: TextView = view.findViewById(R.id.image_caption)
        private val time: TextView = view.findViewById(R.id.image_time)
        private val reactions: LinearLayout = view.findViewById(R.id.image_reactions)
        private val seenBy: LinearLayout = view.findViewById(R.id.image_seen_by)

        fun bind(row: TimelineRow.Image) {
            senderRow.isVisible = row.senderName != null
            sender.text = row.senderName.orEmpty()
            sender.setTextColor(org.matchat.core.ui.media.AvatarFallback.colorFor(row.senderId))
            if (row.senderName != null) {
                onAvatarBind(row.senderAvatarUrl, row.senderName, row.senderId, senderAvatar)
            }
            caption.isVisible = !row.caption.isNullOrEmpty()
            caption.text = row.caption.orEmpty()
            val timeText = if (row.sendGlyph.isEmpty()) row.time else "${row.time} ${row.sendGlyph}"
            time.text = withPinPrefix(row.isPinned, timeText)
            bindBubbleSide(bubble, time, row.isOwn)
            image.setImageDrawable(null)
            onImageBind(row.eventId, image)
            reactions.isVisible = row.reactions.isNotEmpty()
            if (reactions.isVisible) onReactionsBind(row.reactions, reactions)
            seenBy.isVisible = row.seenBy.isNotEmpty()
            if (seenBy.isVisible) onSeenByBind(row.seenBy, seenBy)
            itemView.setOnClickListener { onImageActivated(row) }
        }
    }

    inner class AttachmentVH(view: View) : RecyclerView.ViewHolder(view) {
        private val glyph: TextView = view.findViewById(R.id.attachment_glyph)
        private val sender: TextView = view.findViewById(R.id.attachment_sender)
        private val label: TextView = view.findViewById(R.id.attachment_label)
        private val sub: TextView = view.findViewById(R.id.attachment_sub)
        private val time: TextView = view.findViewById(R.id.attachment_time)

        fun bind(row: TimelineRow.Attachment) {
            glyph.text = row.glyph
            sender.isVisible = row.senderName != null
            sender.text = row.senderName.orEmpty()
            label.text = row.label
            sub.isVisible = !row.sub.isNullOrEmpty()
            sub.text = row.sub.orEmpty()
            time.text = withPinPrefix(row.isPinned, row.time)
            itemView.setOnClickListener { onAttachmentActivated(row) }
        }
    }

    inner class VoiceBubbleVH(view: View) : RecyclerView.ViewHolder(view) {
        private val bubble: LinearLayout = view.findViewById(R.id.voice_bubble)
        private val senderRow: View = view.findViewById(R.id.voice_sender_row)
        private val senderAvatar: ImageView = view.findViewById(R.id.voice_sender_avatar)
        private val sender: TextView = view.findViewById(R.id.voice_sender)
        private val waveform: WaveformView = view.findViewById(R.id.voice_waveform)
        private val duration: TextView = view.findViewById(R.id.voice_duration)
        private val time: TextView = view.findViewById(R.id.voice_time)
        private val reactions: LinearLayout = view.findViewById(R.id.voice_reactions)

        fun bind(row: TimelineRow.VoiceBubble) {
            senderRow.isVisible = row.senderName != null
            sender.text = row.senderName.orEmpty()
            sender.setTextColor(org.matchat.core.ui.media.AvatarFallback.colorFor(row.senderId))
            if (row.senderName != null) {
                onAvatarBind(row.senderAvatarUrl, row.senderName, row.senderId, senderAvatar)
            }
            waveform.setValues(row.waveform)
            waveform.setBarColor(itemView.context.themeColor(UiR.attr.colorTextOnFocus))
            duration.text = row.duration
            val timeText = if (row.sendGlyph.isEmpty()) row.time else "${row.time} ${row.sendGlyph}"
            time.text = withPinPrefix(row.isPinned, timeText)
            bindBubbleSide(bubble, time, row.isOwn)
            reactions.isVisible = row.reactions.isNotEmpty()
            if (reactions.isVisible) onReactionsBind(row.reactions, reactions)
            itemView.setOnClickListener { onVoiceBubbleActivated(row) }
        }
    }

    inner class UtdVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(row: TimelineRow.UnableToDecrypt) {
            itemView.setOnClickListener { onFixEncryption(row.eventId) }
        }
    }

    class SimpleVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text = view as TextView
        fun bind(label: String) {
            text.text = label
        }
    }

    private companion object {
        const val TYPE_MESSAGE = 0
        const val TYPE_DAY = 1
        const val TYPE_UTD = 2
        const val TYPE_STATE = 3
        const val TYPE_IMAGE = 4
        const val TYPE_ATTACHMENT = 5
        const val TYPE_VOICE = 6

        // Long-message round: how far a focused oversized row scrolls per
        // DOWN/UP press, per the user's own "like .75 inches" ask.
        const val SCROLL_STEP_INCHES = 0.75f
        const val OVERFLOW_SLOP_PX = 4 // ignore sub-pixel rounding noise near an edge

        val DIFF = object : DiffUtil.ItemCallback<TimelineRow>() {
            override fun areItemsTheSame(a: TimelineRow, b: TimelineRow) = a.stableId == b.stableId
            override fun areContentsTheSame(a: TimelineRow, b: TimelineRow) = a == b
        }
    }
}
