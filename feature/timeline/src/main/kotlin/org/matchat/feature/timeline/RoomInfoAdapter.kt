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

/**
 * Room Info rows (S12): editable fields, read-only info, section headers, members,
 * and actions. Focusable rows (fields, members, actions) are the CENTER targets.
 */
internal class RoomInfoAdapter(
    private val onFieldActivated: (RoomInfoRow.Field) -> Unit,
    private val onMemberActivated: (RoomInfoRow.Member) -> Unit,
    private val onActionActivated: (RoomInfoRow.Action) -> Unit,
    /** Binds a member avatar (Avatars round): url, name, user id, target —
     *  the name/id are the no-avatar-fallback's color+initial source
     *  (AvatarFallback round). Never called for Field/Info rows, so screens
     *  that reuse this adapter but never show a Member row (MessageInfoFragment,
     *  ProfileFragment) can just take the default. */
    private val onAvatarBind: (String?, String, String, ImageView) -> Unit = { _, _, _, _ -> },
) : ListAdapter<RoomInfoRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is RoomInfoRow.Field -> TYPE_FIELD
        is RoomInfoRow.Info -> TYPE_INFO
        is RoomInfoRow.Section -> TYPE_SECTION
        is RoomInfoRow.Member -> TYPE_MEMBER
        is RoomInfoRow.Action -> TYPE_ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            TYPE_SECTION -> R.layout.item_roominfo_section
            TYPE_ACTION -> R.layout.item_roominfo_action
            else -> R.layout.item_roominfo_field // field, info, member share the two-line row
        }
        return RowVH(inflater.inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as RowVH).bind(getItem(position))
    }

    inner class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(row: RoomInfoRow) {
            when (row) {
                // Primary line is the prominent value; secondary is the caption.
                is RoomInfoRow.Field -> two(row.value.ifBlank { "—" }, row.label) {
                    itemView.setOnClickListener { onFieldActivated(row) }
                }
                is RoomInfoRow.Info -> two(row.value, row.label) {
                    itemView.setOnClickListener(null)
                }
                is RoomInfoRow.Member -> two(
                    row.name,
                    row.sub,
                    showAvatar = true,
                    avatarUrl = row.avatarUrl,
                    avatarName = row.name,
                    avatarUserId = row.userId.value,
                ) {
                    itemView.setOnClickListener { onMemberActivated(row) }
                }
                is RoomInfoRow.Section -> single(row.text)
                is RoomInfoRow.Action -> single(row.label) {
                    itemView.setOnClickListener { onActionActivated(row) }
                }
            }
        }

        private fun two(
            primary: String,
            secondary: String,
            showAvatar: Boolean = false,
            avatarUrl: String? = null,
            avatarName: String = "",
            avatarUserId: String = "",
            wire: () -> Unit,
        ) {
            val primaryView = itemView.findViewById<TextView>(R.id.roominfo_primary)
            val secondaryView = itemView.findViewById<TextView>(R.id.roominfo_secondary)
            primaryView?.text = primary
            secondaryView?.isVisible = secondary.isNotBlank()
            secondaryView?.text = secondary
            // showAvatar=false (Field/Info rows) hides the ImageView entirely,
            // rather than binding a "no avatar" placeholder these row types
            // have no data for; a Member row shows it even when avatarUrl is
            // null (a real member with no avatar set — the fallback initial
            // is correct there, AvatarFallback round).
            itemView.findViewById<ImageView>(R.id.roominfo_avatar)?.let { avatar ->
                avatar.isVisible = showAvatar
                if (showAvatar) onAvatarBind(avatarUrl, avatarName, avatarUserId, avatar)
            }
            wire()
        }

        private fun single(text: String, wire: (() -> Unit)? = null) {
            itemView.findViewById<TextView>(R.id.roominfo_label)?.text = text
            wire?.invoke()
        }
    }

    private companion object {
        const val TYPE_FIELD = 0
        const val TYPE_INFO = 1
        const val TYPE_SECTION = 2
        const val TYPE_MEMBER = 3
        const val TYPE_ACTION = 4

        val DIFF = object : DiffUtil.ItemCallback<RoomInfoRow>() {
            override fun areItemsTheSame(a: RoomInfoRow, b: RoomInfoRow) = a.stableId == b.stableId
            override fun areContentsTheSame(a: RoomInfoRow, b: RoomInfoRow) = a == b
        }
    }
}
