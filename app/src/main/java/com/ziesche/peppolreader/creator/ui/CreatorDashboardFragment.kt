package com.ziesche.peppolreader.creator.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.creator.data.OutgoingInvoiceRepository
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.databinding.FragmentCreatorDashboardBinding
import java.text.NumberFormat
import java.util.Locale

/**
 * Revenue dashboard for the Invoice Creator: KPIs, payment status, revenue per month, VAT per
 * quarter and top customers over the created (GENERATED) invoices. Structure and chart styling
 * mirror the reader's [com.ziesche.peppolreader.ui.DashboardFragment]; aggregation lives in
 * [CreatorDashboardStats].
 */
class CreatorDashboardFragment : Fragment() {

    private var _binding: FragmentCreatorDashboardBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy { OutgoingInvoiceRepository.from(requireContext()) }

    // Anthropic Colors (same palette as the reader dashboard)
    private val COLOR_ACCENT = Color.parseColor("#D97757")
    private val COLOR_ACCENT_ALT = Color.parseColor("#6A9BCC")
    private val COLOR_ACCENT_ALT2 = Color.parseColor("#788C5D")

    // Will be set based on theme
    private var COLOR_TEXT = Color.BLACK
    private var COLOR_GRID = Color.LTGRAY

    private val currencyFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())

    private val FILTER_LAST_6_MONTHS = 0
    private val FILTER_LAST_12_MONTHS = 1
    private val FILTER_CURRENT_MONTH = 2
    private val FILTER_LAST_MONTH = 3
    private val FILTER_ALL = 4

    private var currentFilterIndex = FILTER_LAST_6_MONTHS

    /** Unfiltered invoices from the database; the dashboard applies only its own date filter. */
    private var allInvoices: List<OutgoingInvoice> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreatorDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupThemeColors()
        setupFilters()
        setupCharts()

        repository.allLiveData().observe(viewLifecycleOwner) { invoices ->
            allInvoices = invoices
            updateCharts(currentFilterIndex)
        }
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
        // Payment status donut
        binding.chartPaymentStatus.apply {
            description.isEnabled = false
            setUsePercentValues(true)
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(12f)
            legend.textColor = COLOR_TEXT
            legend.isWordWrapEnabled = true
            setHoleColor(Color.TRANSPARENT)
            setTransparentCircleColor(Color.TRANSPARENT)
            holeRadius = 55f
            transparentCircleRadius = 60f
            animateY(1000)
        }

        // Monthly revenue bar chart
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

        // Customer pie chart
        binding.chartCustomers.apply {
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

    private fun updateCharts(filterIndex: Int) {
        val filtered = filterInvoicesByDate(allInvoices, filterIndex)
        val stats = CreatorDashboardStats.compute(filtered)

        renderKpis(stats)
        renderPaymentStatus(stats)
        renderMonthly(stats)
        renderTax(stats)
        renderCustomers(stats)
    }

    private fun filterInvoicesByDate(
        invoices: List<OutgoingInvoice>,
        filterIndex: Int
    ): List<OutgoingInvoice> {
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

    private fun renderKpis(stats: CreatorDashboardStats.Result) {
        val total = currencyFormat.format(stats.totalRevenue)
        val count = stats.invoiceCount.toString()
        val open = currencyFormat.format(stats.openAmount)
        val overdue = currencyFormat.format(stats.overdueAmount)

        binding.kpiTotalValue.text = total
        binding.kpiCountValue.text = count
        binding.kpiOpenValue.text = open
        binding.kpiOverdueValue.text = overdue

        binding.kpiTotalCard.contentDescription =
            getString(R.string.kpi_cd, getString(R.string.kpi_total_revenue), total)
        binding.kpiCountCard.contentDescription =
            getString(R.string.kpi_cd, getString(R.string.kpi_invoice_count), count)
        binding.kpiOpenCard.contentDescription =
            getString(R.string.kpi_cd, getString(R.string.kpi_open_amount), open)
        binding.kpiOverdueCard.contentDescription =
            getString(R.string.kpi_cd, getString(R.string.kpi_overdue_amount), overdue)
    }

    private fun renderPaymentStatus(stats: CreatorDashboardStats.Result) {
        val chart = binding.chartPaymentStatus
        // Pie slices cannot be negative; clamp for display. Exact figures live in the KPI cards.
        val paid = stats.statusBreakdown.paid.coerceAtLeast(0.0)
        val open = stats.statusBreakdown.open.coerceAtLeast(0.0)
        val overdue = stats.statusBreakdown.overdue.coerceAtLeast(0.0)

        if (paid + open + overdue <= 0.0) {
            chart.clear()
            chart.invalidate()
            return
        }

        val entries = ArrayList<PieEntry>()
        val colors = ArrayList<Int>()
        if (paid > 0.0) {
            entries.add(PieEntry(paid.toFloat(), getString(R.string.legend_paid)))
            colors.add(COLOR_ACCENT_ALT2)
        }
        if (open > 0.0) {
            entries.add(PieEntry(open.toFloat(), getString(R.string.legend_open)))
            colors.add(COLOR_ACCENT_ALT)
        }
        if (overdue > 0.0) {
            entries.add(PieEntry(overdue.toFloat(), getString(R.string.legend_overdue)))
            colors.add(COLOR_ACCENT)
        }

        val dataSet = PieDataSet(entries, "")
        dataSet.sliceSpace = 3f
        dataSet.colors = colors
        dataSet.valueTextColor = COLOR_TEXT

        val data = PieData(dataSet)
        data.setValueFormatter(PercentFormatter(chart))
        data.setValueTextSize(11f)
        data.setValueTextColor(Color.WHITE)

        chart.data = data
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    private fun renderMonthly(stats: CreatorDashboardStats.Result) {
        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        stats.perMonth.forEachIndexed { index, point ->
            entries.add(BarEntry(index.toFloat(), point.amount.toFloat()))
            // "YYYY-MM" -> "MM"
            labels.add(point.month.substring(5))
        }

        val dataSet = BarDataSet(entries, getString(R.string.chart_label_revenue))
        dataSet.color = COLOR_ACCENT
        dataSet.valueTextColor = COLOR_TEXT
        dataSet.valueTextSize = 10f

        val data = BarData(dataSet)
        data.barWidth = 0.6f

        binding.chartMonthly.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        binding.chartMonthly.data = data
        binding.chartMonthly.notifyDataSetChanged()
        binding.chartMonthly.invalidate()

        if (entries.isEmpty()) {
            binding.chartMonthly.clear()
        }
    }

    private fun renderTax(stats: CreatorDashboardStats.Result) {
        binding.taxNetValue.text = currencyFormat.format(stats.taxTotals.net)
        binding.taxVatValue.text = currencyFormat.format(stats.taxTotals.tax)
        binding.taxGrossValue.text = currencyFormat.format(stats.taxTotals.gross)

        // VAT per quarter — one line each, most recent first. Drives the Umsatzsteuervoranmeldung.
        binding.taxQuarterly.text = if (stats.perQuarter.isEmpty()) {
            getString(R.string.tax_quarterly_empty)
        } else {
            stats.perQuarter.joinToString("\n") { q ->
                getString(
                    R.string.tax_quarterly_row,
                    q.label,
                    currencyFormat.format(q.net),
                    currencyFormat.format(q.tax),
                    currencyFormat.format(q.gross)
                )
            }
        }
    }

    private fun renderCustomers(stats: CreatorDashboardStats.Result) {
        val chart = binding.chartCustomers
        // Customers whose credit notes outweigh invoices net to <= 0; drop them from the pie.
        val shares = stats.topCustomers.filter { it.amount > 0.0 }

        if (shares.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }

        val entries = ArrayList<PieEntry>()
        shares.forEach { share ->
            entries.add(PieEntry(share.amount.toFloat(), share.customerName))
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
        data.setValueFormatter(PercentFormatter(chart))
        data.setValueTextSize(11f)
        data.setValueTextColor(COLOR_TEXT)

        chart.data = data
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
