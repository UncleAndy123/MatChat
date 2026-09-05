package org.matchat.feature.verification

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.matchat.core.model.SasEmoji
import org.matchat.core.ui.focus.FocusEngine
import org.matchat.core.ui.nav.Navigator
import org.matchat.core.ui.softkey.SoftkeyFragment
import org.matchat.feature.verification.databinding.FragmentVerificationBinding
import org.matchat.core.ui.R as UiR

/** S5/S6/S7 — emoji (SAS) verification with recovery-key fallback. */
@AndroidEntryPoint
class VerificationFragment : SoftkeyFragment() {

    override val contentLayoutId: Int = R.layout.fragment_verification
    override val leftLabel: CharSequence get() = getString(UiR.string.softkey_blank)
    override val centerLabel: CharSequence get() = getString(UiR.string.softkey_select)
    override val rightLabel: CharSequence get() = getString(UiR.string.softkey_cancel)

    private val viewModel: VerificationViewModel by viewModels()
    private var binding: FragmentVerificationBinding? = null
    private val navigator: Navigator get() = requireActivity() as Navigator

    override fun onContentViewCreated(content: View) {
        val b = FragmentVerificationBinding.bind(content)
        binding = b
        setTitle(getString(R.string.verify_title))

        b.chooseDevice.setOnClickListener { viewModel.onAction(VerificationAction.StartSas) }
        b.chooseRecovery.setOnClickListener { viewModel.onAction(VerificationAction.ChooseRecovery) }
        b.sasMatch.setOnClickListener { viewModel.onAction(VerificationAction.ApproveSas) }
        b.sasNoMatch.setOnClickListener { viewModel.onAction(VerificationAction.DeclineSas) }
        b.recoverySubmit.setOnClickListener {
            viewModel.onAction(VerificationAction.SubmitRecovery(b.recoveryKey.text.toString()))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.navEvents.collect {
                        Toast.makeText(requireContext(), R.string.verify_done, Toast.LENGTH_SHORT).show()
                        navigator.back()
                    }
                }
            }
        }
    }

    private fun render(state: VerificationState) {
        val b = binding ?: return
        b.groupChoose.isVisible = state.phase == Phase.CHOOSE
        b.groupWaiting.isVisible = state.phase == Phase.WAITING_FOR_DEVICE
        b.groupCompare.isVisible = state.phase == Phase.COMPARING
        b.groupRecovery.isVisible = state.phase == Phase.RECOVERY_KEY
        b.verifyError.isVisible = state.error != null

        b.verifySubtext.text = getString(
            when (state.phase) {
                Phase.COMPARING -> R.string.verify_compare_explanation
                Phase.RECOVERY_KEY -> R.string.verify_recovery_explanation
                else -> R.string.verify_choose_explanation
            },
        )

        if (state.phase == Phase.COMPARING) renderEmojis(state.emojis)

        when (state.phase) {
            Phase.CHOOSE -> FocusEngine.requestInitialFocus(b.chooseDevice)
            Phase.COMPARING -> FocusEngine.requestInitialFocus(b.sasMatch)
            Phase.RECOVERY_KEY -> FocusEngine.requestInitialFocus(b.recoveryKey)
            else -> Unit
        }
    }

    /** S6 emoji grid: 7 cells (symbol + word), laid out in rows of four. */
    private fun renderEmojis(emojis: List<SasEmoji>) {
        val container = binding?.emojiContainer ?: return
        container.removeAllViews()
        emojis.chunked(EMOJIS_PER_ROW).forEach { row ->
            val rowView = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            row.forEach { rowView.addView(emojiCell(it)) }
            container.addView(rowView)
        }
    }

    private fun emojiCell(emoji: SasEmoji): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val pad = resources.getDimensionPixelSize(UiR.dimen.content_pad)
            setPadding(pad, pad, pad, pad)
            addView(
                TextView(requireContext()).apply {
                    text = emoji.symbol
                    textSize = EMOJI_SP
                    gravity = Gravity.CENTER
                },
            )
            addView(
                TextView(requireContext()).apply {
                    text = emoji.name
                    textSize = LABEL_SP
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(requireContext(), UiR.color.text_secondary))
                },
            )
        }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private companion object {
        const val EMOJIS_PER_ROW = 4
        const val EMOJI_SP = 28f
        const val LABEL_SP = 11f
    }
}
