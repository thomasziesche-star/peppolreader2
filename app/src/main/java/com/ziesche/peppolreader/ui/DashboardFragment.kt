package com.ziesche.peppolreader.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.databinding.FragmentDashboardBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import java.text.NumberFormat
import java.util.Locale

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: InvoiceViewModel by activityViewModels()

    // Anthropic Colors
    private val COLOR_ACCENT = Color.parseColor("#D97757")
    private val COLOR_ACCENT_ALT = Color.parseColor("#6A9BCC")
    private val COLOR_ACCENT_ALT2 = Color.parseColor("#788C5D")
    
    // Will be set based on theme
    private var COLOR_TEXT = Color.BLACK
    private var COLOR_GRID = Color.LTGRAY

    private val FILTER_LAST_6_MONTHS = 0
    private val FILTER_LAST_12_MONTHS = 1
    private val FILTER_CURRENT_MONTH = 2
    private val FILTER_LAST_MONTH = 3
    private val FILTER_ALL = 4
    
    // Track current filter index to avoid parsing localized text
    private var currentFilterIndex = FILTER_LAST_6_MONTHS

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupThemeColors()
        setupFilters()
        setupCharts()
        observeData()
    }
    
    private fun setupThemeColors() {
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNight = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        if (isNight) {
            COLOR_TEXT = Color.parseColor("#E8E6DC") // Anthropic Light Gray
            COLOR_GRID = Color.parseColor("#4A4944") // Anthropic Dark Panel
        } else {
            COLOR_TEXT = Color.parseColor("#141413") // Anthropic Dark
            COLOR_GRID = Color.parseColor("#E8E6DC") // Anthropic Light Gray
        }
    }

    private fun setupFilters() {
        // Load localized strings
        val items = listOf(
            getString(R.string.filter_last_6_months),
            getString(R.string.filter_last_12_months),
            getString(R.string.filter_current_month),
            getString(R.string.filter_last_month),
            getString(R.string.filter_all)
        )
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, items)
        binding.filterSpinner.setAdapter(adapter)
        binding.filterSpinner.setText(items[0], false)
        
        binding.filterSpinner.setOnItemClickListener { _, _, position, _ ->
            currentFilterIndex = position
            updateCharts(currentFilterIndex)
        }
    }

    private fun setupCharts() {
        // Monthly Bar Chart
        binding.chartMonthly.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.textColor = COLOR_TEXT
            xAxis.granularity = 1f
            
            axisLeft.textColor = COLOR_TEXT
            axisLeft.gridColor = COLOR_GRID
            axisRight.isEnabled = false
            
            legend.textColor = COLOR_TEXT
            animateY(1000)
        }

        // Supplier Pie Chart
        binding.chartSuppliers.apply {
            description.isEnabled = false
            setUsePercentValues(true)
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(12f)
            legend.textColor = COLOR_TEXT
            legend.isWordWrapEnabled = true
            setHoleColor(Color.TRANSPARENT)
            setTransparentCircleColor(Color.TRANSPARENT)
            animateY(1400)
        }
    }

    private fun observeData() {
        // Observe ALL invoices and filter locally
        viewModel.allInvoices.observe(viewLifecycleOwner) { 
             updateCharts(currentFilterIndex)
        }
    }
    
    private fun updateCharts(filterIndex: Int) {
        val allInvoices = viewModel.allInvoices.value ?: return
        
        // Filter invoices
        val filteredInvoices = filterInvoicesByDate(allInvoices, filterIndex)
        
        updateMonthlyChart(filteredInvoices)
        updateSupplierChart(filteredInvoices)
    }

    private fun filterInvoicesByDate(invoices: List<com.ziesche.peppolreader.data.model.Invoice>, filterIndex: Int): List<com.ziesche.peppolreader.data.model.Invoice> {
        val calendar = java.util.Calendar.getInstance()
        val currentYear = calendar.get(java.util.Calendar.YEAR)
        val currentMonth = calendar.get(java.util.Calendar.MONTH) + 1 // 1-based

        return invoices.filter { invoice ->
            try {
                // issueDate expected format YYYY-MM-DD
                if (invoice.issueDate.length >= 7) {
                    val invYear = invoice.issueDate.substring(0, 4).toInt()
                    val invMonth = invoice.issueDate.substring(5, 7).toInt()
                    
                    when (filterIndex) {
                        FILTER_LAST_6_MONTHS -> {
                            val diffMonth = (currentYear - invYear) * 12 + (currentMonth - invMonth)
                            diffMonth in 0..6
                        }
                        FILTER_LAST_12_MONTHS -> {
                            val diffMonth = (currentYear - invYear) * 12 + (currentMonth - invMonth)
                            diffMonth in 0..12
                        }
                        FILTER_CURRENT_MONTH -> {
                            invYear == currentYear && invMonth == currentMonth
                        }
                        FILTER_LAST_MONTH -> {
                             // Handle Jan -> Prev Dec logic
                            val prevMonth = if (currentMonth == 1) 12 else currentMonth - 1
                            val prevYear = if (currentMonth == 1) currentYear - 1 else currentYear
                            invYear == prevYear && invMonth == prevMonth
                        }
                        else -> true // All
                    }
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun updateMonthlyChart(invoices: List<com.ziesche.peppolreader.data.model.Invoice>) {
        // Aggregate by month
        // Map: "YYYY-MM" -> Total
        val agg = invoices.groupBy { it.issueDate.substring(0, 7) }
            .mapValues { entry -> entry.value.sumOf { it.payableAmount } }
            .toList()
            .sortedBy { it.first } // Sort by date ascending

        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()
        
        agg.forEachIndexed { index, (month, total) ->
             entries.add(BarEntry(index.toFloat(), total.toFloat()))
             // Format: YYYY-MM -> MM/YY or similar. reusing substring logic
             labels.add(month.substring(5)) 
        }

        val dataSet = BarDataSet(entries, getString(R.string.chart_label_expenses))
        dataSet.color = COLOR_ACCENT
        dataSet.valueTextColor = COLOR_TEXT
        dataSet.valueTextSize = 10f
        
        val data = BarData(dataSet)
        data.barWidth = 0.6f
        
        binding.chartMonthly.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        binding.chartMonthly.data = data
        binding.chartMonthly.notifyDataSetChanged()
        binding.chartMonthly.invalidate()
    }

    private fun updateSupplierChart(invoices: List<com.ziesche.peppolreader.data.model.Invoice>) {
        // Top 5 Suppliers
        val agg = invoices.groupBy { it.supplierName }
            .mapValues { entry -> entry.value.sumOf { it.payableAmount } }
            .toList()
            .sortedByDescending { it.second }
            .take(5)

        val entries = ArrayList<PieEntry>()
        
        agg.forEach { (name, total) ->
            entries.add(PieEntry(total.toFloat(), name))
        }

        val dataSet = PieDataSet(entries, "")
        dataSet.sliceSpace = 3f
        dataSet.selectionShift = 5f
        
        val colors = ArrayList<Int>()
        colors.add(COLOR_ACCENT)
        colors.add(COLOR_ACCENT_ALT)
        colors.add(COLOR_ACCENT_ALT2)
        colors.add(ColorTemplate.getHoloBlue())
        colors.add(Color.GRAY)
        dataSet.colors = colors
        
        dataSet.valueLinePart1OffsetPercentage = 80f
        dataSet.valueLinePart1Length = 0.2f
        dataSet.valueLinePart2Length = 0.4f
        dataSet.yValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
        dataSet.valueTextColor = COLOR_TEXT
        
        val data = PieData(dataSet)
        data.setValueFormatter(PercentFormatter(binding.chartSuppliers))
        data.setValueTextSize(11f)
        data.setValueTextColor(COLOR_TEXT)
        
        binding.chartSuppliers.data = data
        binding.chartSuppliers.notifyDataSetChanged()
        binding.chartSuppliers.invalidate()
        
        // Show empty state if no data?
        if (entries.isEmpty()) {
            binding.chartSuppliers.clear()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
