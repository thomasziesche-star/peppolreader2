package com.ziesche.peppolreader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.databinding.BottomSheetRemindersBinding
import com.ziesche.peppolreader.notifications.NotificationScheduler
import com.ziesche.peppolreader.notifications.ReminderPrefs

/**
 * BottomSheet for the due-date reminder settings: enable toggle, lead-time chips,
 * "Check now" button. The host activity owns the POST_NOTIFICATIONS permission
 * dialog (Android 13+) — this sheet asks via [Listener.onEnableRequested] before
 * actually flipping the switch on.
 */
class ReminderSettingsBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        /** Called when the user toggles the switch on. Host should ensure the runtime
         *  notification permission is granted, then call [confirmEnabled] back. */
        fun onEnableRequested(sheet: ReminderSettingsBottomSheet)
    }

    private var _binding: BottomSheetRemindersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetRemindersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = ReminderPrefs(requireContext())

        binding.btnHelp.setOnClickListener {
            com.ziesche.peppolreader.util.HelpDialog.show(requireContext(), R.string.help_reminders)
        }

        binding.switchEnabled.isChecked = prefs.enabled
        applyChipFromValue(prefs.daysBefore)
        updateChipGroupEnabled(prefs.enabled)

        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Wait for host to confirm via confirmEnabled() so we can ask for the runtime
                // POST_NOTIFICATIONS permission first.
                binding.switchEnabled.isChecked = false
                (parentFragment as? Listener ?: activity as? Listener)
                    ?.onEnableRequested(this)
            } else {
                prefs.enabled = false
                NotificationScheduler.disable(requireContext())
                updateChipGroupEnabled(false)
            }
        }

        binding.daysBeforeGroup.setOnCheckedStateChangeListener { _, checked ->
            val value = when (checked.firstOrNull()) {
                R.id.chip_days_0 -> 0
                R.id.chip_days_3 -> 3
                R.id.chip_days_7 -> 7
                R.id.chip_days_14 -> 14
                else -> prefs.daysBefore
            }
            prefs.daysBefore = value
            // Re-schedule so the periodic worker picks up the new value
            if (prefs.enabled) NotificationScheduler.enable(requireContext())
        }

        binding.btnCheckNow.setOnClickListener {
            NotificationScheduler.triggerNow(requireContext())
            Snackbar.make(binding.root, R.string.reminders_test, Snackbar.LENGTH_SHORT).show()
        }
        binding.btnClose.setOnClickListener { dismiss() }
    }

    /** Called by the host activity once POST_NOTIFICATIONS is granted (or implied pre-Tiramisu). */
    fun confirmEnabled() {
        val ctx = requireContext()
        val prefs = ReminderPrefs(ctx)
        prefs.enabled = true
        NotificationScheduler.enable(ctx)
        binding.switchEnabled.isChecked = true
        updateChipGroupEnabled(true)
    }

    /** Called by the host when the runtime permission was denied. */
    fun denyEnabled() {
        binding.switchEnabled.isChecked = false
        Snackbar.make(
            binding.root,
            R.string.reminders_permission_required,
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun applyChipFromValue(days: Int) {
        val chipId = when (days) {
            0 -> R.id.chip_days_0
            7 -> R.id.chip_days_7
            14 -> R.id.chip_days_14
            else -> R.id.chip_days_3
        }
        binding.daysBeforeGroup.check(chipId)
    }

    private fun updateChipGroupEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.5f
        binding.daysBeforeLabel.alpha = alpha
        binding.daysBeforeGroup.alpha = alpha
        for (i in 0 until binding.daysBeforeGroup.childCount) {
            binding.daysBeforeGroup.getChildAt(i).isEnabled = enabled
        }
        binding.btnCheckNow.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ReminderSettings"
    }
}
