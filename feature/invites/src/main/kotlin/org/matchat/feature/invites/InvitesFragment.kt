package org.matchat.feature.invites

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.invites.databinding.FragmentInvitesBinding
import org.matchat.feature.invites.databinding.ItemInviteBinding

/** S18 Invitations. LEFT is blank here; the list rows are the whole screen. */
@AndroidEntryPoint
class InvitesFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_invites
    override val leftLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(org.matchat.core.ui.R.string.softkey_open)

    private val viewModel: InvitesViewModel by viewModels()
    private var binding: FragmentInvitesBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator
    private val adapter = InviteAdapter { navigator.toInvite(it.roomId) }

    override fun onContentViewCreated(content: View) {
        val b = FragmentInvitesBinding.bind(content)
        binding = b
        setTitle(getString(R.string.invites_title))
        b.invitesList.layoutManager = LinearLayoutManager(requireContext())
        b.invitesList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: InvitesState) {
        val b = binding ?: return
        adapter.submitList(state.invites)
        if (b.root.findFocus() == null && state.invites.isNotEmpty()) {
            FocusEngine.requestInitialFocus(b.invitesList)
        }
    }

    override fun onDestroyView() {
        binding?.invitesList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private class InviteAdapter(private val onOpen: (InviteRow) -> Unit) :
        ListAdapter<InviteRow, InviteAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemInviteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b, onOpen)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        class VH(private val b: ItemInviteBinding, private val onOpen: (InviteRow) -> Unit) :
            RecyclerView.ViewHolder(b.root) {
            fun bind(row: InviteRow) {
                b.inviteName.text = row.name
                b.inviteFrom.text = row.from
                b.inviteBlocked.isVisible = row.blocked
                b.root.setOnClickListener { onOpen(row) }
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<InviteRow>() {
                override fun areItemsTheSame(a: InviteRow, b: InviteRow) = a.roomId == b.roomId
                override fun areContentsTheSame(a: InviteRow, b: InviteRow) = a == b
            }
        }
    }
}
