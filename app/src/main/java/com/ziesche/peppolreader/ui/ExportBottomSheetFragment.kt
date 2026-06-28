package com.ziesche.peppolreader.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.databinding.BottomSheetExportBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet that lets the user pick a date range and decide whether the original
 * XML/PDF files should be bundled into a ZIP. On confirm it delegates to [Listener]
 * with ISO-formatted from/to dates (YYYY-MM-DD) — the host activity drives the
 * actual file creation via Storage Access Framework.
 */
class ExportBottomSheetFragment : BottomSheetDialogFragment() {

    interface Listener {
        fun onExportRequested(fromIso: String, toIso: String, includeXmlBundle: Boolean)
    }

    private var _binding: BottomSheetExportBinding? = null
    private val binding get() = _binding!!

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayFormat: SimpleDateFormat by lazy {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    }

    /** Calendar for "from" (inclusive). Defaults to start of current year. */
    private val fromCal = Calendar.getInstance()

    /** Calendar for "to" (inclusive). Defaults to today. */
    private val toCal = Calendar.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Sensible defaults: current year
        fromCal.apply {
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            zeroTime()
        }
        toCal.apply { endOfDay() }
        refreshInputs()

        binding.btnHelp.setOnClickListener {
            com.ziesche.peppolreader.util.HelpDialog.show(requireContext(), R.string.help_export)
        }

        binding.fromInput.setOnClickListener { showDatePicker(fromCal) { refreshInputs() } }
        binding.toInput.setOnClickListener { showDatePicker(toCal) { refreshInputs() } }

        binding.quickRangeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chip_range_all -> {
                    fromCal.set(2000, Calendar.JANUARY, 1)
                    fromCal.zeroTime()
                    toCal.timeInMillis = System.currentTimeMillis()
                    toCal.endOfDay()
                }
                R.id.chip_range_year -> {
                    fromCal.timeInMillis = System.currentTimeMillis()
                    fromCal.set(Calendar.MONTH, Calendar.JANUARY)
                    fromCal.set(Calendar.DAY_OF_MONTH, 1)
                    fromCal.zeroTime()
                    toCal.timeInMillis = System.currentTimeMillis()
                    toCal.endOfDay()
                }
                R.id.chip_range_quarter -> {
                    val now = Calendar.getInstance()
                    val quarterStartMonth = (now.get(Calendar.MONTH) / 3) * 3
                    fromCal.timeInMillis = System.currentTimeMillis()
                    fromCal.set(Calendar.MONTH, quarterStartMonth)
                    fromCal.set(Calendar.DAY_OF_MONTH, 1)
                    fromCal.zeroTime()
                    toCal.timeInMillis = System.currentTimeMillis()
                    toCal.endOfDay()
                }
                R.id.chip_range_month -> {
                    val now = Calendar.getInstance()
                    now.add(Calendar.MONTH, -1)
                    fromCal.timeInMillis = now.timeInMillis
                    fromCal.set(Calendar.DAY_OF_MONTH, 1)
                    fromCal.zeroTime()
                    toCal.timeInMillis = now.timeInMillis
                    toCal.set(Calendar.DAY_OF_MONTH, toCal.getActualMaximum(Calendar.DAY_OF_MONTH))
                    toCal.endOfDay()
                }
            }
            refreshInputs()
        }

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnConfirm.setOnClickListener {
            val listener = (parentFragment as? Listener) ?: (activity as? Listener)
            listener?.onExportRequested(
                fromIso = isoFormat.format(fromCal.time),
                toIso = isoFormat.format(toCal.time),
                includeXmlBundle = binding.includeXmlSwitch.isChecked
            )
            dismiss()
        }
    }

    private fun showDatePicker(cal: Calendar, onPicked: () -> Unit) {
        val ctx = requireContext()
        val picker = DatePickerDialog(
            ctx,
            { _, y, m, d ->
                cal.set(Calendar.YEAR, y)
                cal.set(Calendar.MONTH, m)
                cal.set(Calendar.DAY_OF_MONTH, d)
                if (cal === toCal) cal.endOfDay() else cal.zeroTime()
                onPicked()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        picker.show()
    }

    private fun refreshInputs() {
        binding.fromInput.setText(displayFormat.format(Date(fromCal.timeInMillis)))
        binding.toInput.setText(displayFormat.format(Date(toCal.timeInMillis)))
    }

    private fun Calendar.zeroTime() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun Calendar.endOfDay() {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ExportBottomSheet"
    }
}
