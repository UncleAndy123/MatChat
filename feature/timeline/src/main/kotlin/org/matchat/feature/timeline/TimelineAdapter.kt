package org.matchat.feature.timeline

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.matchat.core.model.EventId

/**
 * Timeline rows: text messages, images, attachments, day/state separators.
 * Focusable rows are the CENTER target; separators are not. DiffUtil keeps scroll
 * cheap. Image bytes are loaded by the Fragment via [onImageBind].
 */
internal class TimelineAdapter(
    private val onMessageFocused: (EventId) -> Unit,
    private val onFixEncryption: (EventId) -> Unit,
    private val onMessageActivated: (EventId) -> Unit,
    private val onImageBind: (EventId, ImageView) -> Unit,
    private val onAttachmentActivated: (TimelineRow.Attachment) -> Unit,
) : ListAdapter<TimelineRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is TimelineRow.Message -> TYPE_MESSAGE
        is TimelineRow.Image -> TYPE_IMAGE
        is TimelineRow.Attachment -> TYPE_ATTACHMENT
        is TimelineRow.DaySeparator -> TYPE_DAY
        is TimelineRow.UnableToDecrypt -> TYPE_UTD
        is TimelineRow.State -> TYPE_STATE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_MESSAGE -> MessageVH(inflater.inflate(R.layout.item_message, parent, false))
            TYPE_IMAGE -> ImageVH(inflater.inflate(R.layout.item_message_image, parent, false))
            TYPE_ATTACHMENT -> AttachmentVH(inflater.inflate(R.layout.item_attachment, parent, false))
            TYPE_DAY -> SimpleVH(inflater.inflate(R.layout.item_day, parent, false))
            TYPE_UTD -> UtdVH(inflater.inflate(R.layout.item_utd, parent, false))
            else -> SimpleVH(inflater.inflate(R.layout.item_state, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is TimelineRow.Message -> (holder as MessageVH).bind(row)
            is TimelineRow.Image -> (holder as ImageVH).bind(row)
            is TimelineRow.Attachment -> (holder as AttachmentVH).bind(row)
            is TimelineRow.DaySeparator -> (holder as SimpleVH).bind(row.label)
            is TimelineRow.UnableToDecrypt -> (holder as UtdVH).bind(row)
            is TimelineRow.State -> (holder as SimpleVH).bind(row.text)
        }
    }

    inner class MessageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val sender: TextView = view.findViewById(R.id.message_sender)
        private val body: TextView = view.findViewById(R.id.message_body)
        private val time: TextView = view.findViewById(R.id.message_time)

        fun bind(row: TimelineRow.Message) {
            sender.isVisible = row.senderName != null
            sender.text = row.senderName.orEmpty()
            body.text = row.body
            time.text = if (row.sendGlyph.isEmpty()) row.time else "${row.time} ${row.sendGlyph}"
            itemView.setOnFocusChangeListener { _, has -> if (has) onMessageFocused(row.eventId) }
            itemView.setOnClickListener { onMessageActivated(row.eventId) }
        }
    }

    inner class ImageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val sender: TextView = view.findViewById(R.id.image_sender)
        private val image: ImageView = view.findViewById(R.id.message_image)
        private val caption: TextView = view.findViewById(R.id.image_caption)
        private val time: TextView = view.findViewById(R.id.image_time)

        fun bind(row: TimelineRow.Image) {
            sender.isVisible = row.senderName != null
            sender.text = row.senderName.orEmpty()
            caption.isVisible = !row.caption.isNullOrEmpty()
            caption.text = row.caption.orEmpty()
            time.text = if (row.sendGlyph.isEmpty()) row.time else "${row.time} ${row.sendGlyph}"
            image.setImageDrawable(null)
            onImageBind(row.eventId, image)
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
            time.text = row.time
            itemView.setOnClickListener { onAttachmentActivated(row) }
        }
    }

    inner class UtdVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(row: TimelineRow.UnableToDecrypt) {
            itemView.setOnClickListener { onFixEncryption(row.eventId) }
        }
    }

    class SimpleVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text = view as TextView
        fun bind(label: String) { text.text = label }
    }

    private companion object {
        const val TYPE_MESSAGE = 0
        const val TYPE_DAY = 1
        const val TYPE_UTD = 2
        const val TYPE_STATE = 3
        const val TYPE_IMAGE = 4
        const val TYPE_ATTACHMENT = 5

        val DIFF = object : DiffUtil.ItemCallback<TimelineRow>() {
            override fun areItemsTheSame(a: TimelineRow, b: TimelineRow) = a.stableId == b.stableId
            override fun areContentsTheSame(a: TimelineRow, b: TimelineRow) = a == b
        }
    }
}
