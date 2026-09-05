package org.matchat.feature.roomlist

import android.view.LayoutInflater
import android.view.ViewGroup
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
            binding.roomPreview.text = row.preview
            binding.roomTime.text = row.time
            binding.roomUnread.text = if (row.unreadCount > 0) row.unreadCount.toString() else ""
            binding.roomUnread.visibility =
                if (row.isUnread) android.view.View.VISIBLE else android.view.View.GONE
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
