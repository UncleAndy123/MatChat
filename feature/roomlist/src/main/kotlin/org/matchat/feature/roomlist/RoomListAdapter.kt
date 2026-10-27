package org.matchat.feature.roomlist

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.matchat.feature.roomlist.databinding.ItemRoomBinding

/**
 * Focusable room rows with DiffUtil (PLAN.md §4 — view recycling matters at
 * 2 GB). The row is the click target; CENTER activates it via performClick.
 */
internal class RoomListAdapter(
    private val onOpen: (RoomRow) -> Unit,
    private val onFocused: (Int) -> Unit,
    /** Binds a room avatar (Avatars round): url, room name, room id, target —
     *  the name/id are the no-avatar-fallback's color+initial source
     *  (AvatarFallback round; a room uses its own id/name the same way a
     *  message sender uses theirs). */
    private val onAvatarBind: (String?, String, String, ImageView) -> Unit,
) : ListAdapter<RoomRow, RoomListAdapter.RoomViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        val binding = ItemRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RoomViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RoomViewHolder(private val binding: ItemRoomBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: RoomRow) {
            binding.roomName.text = row.name
            val draft = row.draft
            val res = binding.root.context.resources
            binding.roomPreview.text = when {
                row.isSpace -> res.getQuantityString(R.plurals.roomlist_space_preview, row.groupCount, row.groupCount)
                draft == null || draft.isEmpty -> row.preview
                draft.text.isNotBlank() -> binding.root.context.getString(R.string.roomlist_draft_format, draft.text)
                else -> binding.root.context.getString(R.string.roomlist_draft_attachment)
            }
            // A text marker ("Draft: …") already conveys the state (AGENTS.md
            // — never color/style alone); italicizing on top is just a cheap,
            // additional visual cue, not the only signal.
            val previewStyle = if (row.isDraft) Typeface.ITALIC else Typeface.NORMAL
            binding.roomPreview.setTypeface(binding.roomPreview.typeface, previewStyle)
            binding.roomTime.text = row.time
            binding.roomUnread.text = if (row.unreadCount > 0) row.unreadCount.toString() else ""
            binding.roomUnread.visibility =
                if (row.isUnread) android.view.View.VISIBLE else android.view.View.GONE
            onAvatarBind(row.avatarUrl, row.name, row.id.value, binding.roomAvatar)
            binding.root.setOnClickListener { onOpen(row) }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused(bindingAdapterPosition)
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RoomRow>() {
            override fun areItemsTheSame(a: RoomRow, b: RoomRow) = a.id == b.id
            override fun areContentsTheSame(a: RoomRow, b: RoomRow) = a == b
        }
    }
}
