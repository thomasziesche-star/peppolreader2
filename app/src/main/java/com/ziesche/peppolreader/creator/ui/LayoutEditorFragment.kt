package com.ziesche.peppolreader.creator.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.creator.data.CompanyProfileStore
import com.ziesche.peppolreader.creator.data.LayoutThemeStore
import com.ziesche.peppolreader.creator.model.LayoutTheme
import com.ziesche.peppolreader.creator.pdf.SampleInvoiceFactory
import com.ziesche.peppolreader.creator.pdf.ZugferdPdfA3Writer
import com.ziesche.peppolreader.databinding.FragmentLayoutEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Layout editor for generated invoice PDFs: preset chips, an accent swatch row and three
 * toggle groups, all feeding a debounced live preview. The preview goes through the real
 * [ZugferdPdfA3Writer] with a sample invoice — WYSIWYG, no second rendering path to maintain.
 */
class LayoutEditorFragment : Fragment() {

    private var _binding: FragmentLayoutEditorBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: LayoutThemeStore
    private var theme = LayoutTheme()

    private val swatchButtons = mutableListOf<MaterialButton>()
    /** Guards against control listeners firing while we programmatically apply a theme. */
    private var applying = false
    private var previewJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLayoutEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = LayoutThemeStore(requireContext())
        theme = store.load()

        buildSwatchRow()
        applyThemeToControls()
        wireListeners()
        renderPreview(debounce = false)

        binding.btnSaveLayout.setOnClickListener {
            store.save(theme)
            Snackbar.make(binding.root, R.string.creator_saved, Snackbar.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    // ----- controls -------------------------------------------------------------------------

    private fun buildSwatchRow() {
        val size = (40 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        SWATCHES.forEachIndexed { index, (hex, nameRes) ->
            val button = MaterialButton(
                requireContext(), null,
                com.google.android.material.R.attr.materialIconButtonFilledStyle
            ).apply {
                layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                    if (index > 0) leftMargin = margin
                }
                insetTop = 0
                insetBottom = 0
                cornerRadius = size / 2
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(hex))
                iconTint = android.content.res.ColorStateList.valueOf(Color.WHITE)
                iconPadding = 0
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                contentDescription = getString(nameRes)
                setOnClickListener {
                    if (!applying) onControlChanged { copy(accentHex = hex) }
                    markSelectedSwatch()
                }
            }
            swatchButtons.add(button)
            binding.containerSwatches.addView(button)
        }
    }

    /** Check icon on the swatch matching the current accent (if any). */
    private fun markSelectedSwatch() {
        SWATCHES.forEachIndexed { index, (hex, _) ->
            swatchButtons[index].icon = if (hex.equals(theme.accentHex, ignoreCase = true)) {
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    requireContext(), R.drawable.ic_check
                )
            } else null
        }
    }

    private fun wireListeners() {
        binding.groupPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (applying) return@setOnCheckedStateChangeListener
            val presetId = when (checkedIds.firstOrNull()) {
                R.id.chip_preset_warm -> LayoutTheme.PRESET_WARM
                R.id.chip_preset_classic -> LayoutTheme.PRESET_CLASSIC
                R.id.chip_preset_modern -> LayoutTheme.PRESET_MODERN
                else -> LayoutTheme.PRESET_CUSTOM
            }
            if (presetId != LayoutTheme.PRESET_CUSTOM) {
                theme = LayoutTheme.preset(presetId)
                applyThemeToControls()
                renderPreview()
            } else {
                theme = theme.copy(presetId = LayoutTheme.PRESET_CUSTOM)
            }
        }
        binding.toggleFont.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (applying || !isChecked) return@addOnButtonCheckedListener
            onControlChanged {
                copy(
                    fontPairing = when (checkedId) {
                        R.id.btn_font_sans -> LayoutTheme.PAIRING_SANS
                        R.id.btn_font_serif -> LayoutTheme.PAIRING_SERIF
                        else -> LayoutTheme.PAIRING_EDITORIAL
                    }
                )
            }
        }
        binding.togglePaper.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (applying || !isChecked) return@addOnButtonCheckedListener
            onControlChanged {
                copy(
                    paperTint = when (checkedId) {
                        R.id.btn_paper_ivory -> LayoutTheme.PAPER_IVORY
                        R.id.btn_paper_cool -> LayoutTheme.PAPER_COOL
                        else -> LayoutTheme.PAPER_WHITE
                    }
                )
            }
        }
        binding.toggleTableHeader.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (applying || !isChecked) return@addOnButtonCheckedListener
            onControlChanged {
                copy(
                    tableHeaderStyle = if (checkedId == R.id.btn_header_band)
                        LayoutTheme.HEADER_BAND else LayoutTheme.HEADER_RULE
                )
            }
        }
    }

    /** Every manual tweak turns the selection into the "custom" preset and refreshes preview. */
    private fun onControlChanged(change: LayoutTheme.() -> LayoutTheme) {
        theme = theme.change().copy(presetId = LayoutTheme.PRESET_CUSTOM)
        applying = true
        binding.groupPresets.check(R.id.chip_preset_custom)
        applying = false
        renderPreview()
    }

    /** Pushes [theme] into every control without triggering the listeners. */
    private fun applyThemeToControls() {
        applying = true
        binding.groupPresets.check(
            when (theme.presetId) {
                LayoutTheme.PRESET_WARM -> R.id.chip_preset_warm
                LayoutTheme.PRESET_CLASSIC -> R.id.chip_preset_classic
                LayoutTheme.PRESET_MODERN -> R.id.chip_preset_modern
                else -> R.id.chip_preset_custom
            }
        )
        binding.toggleFont.check(
            when (theme.fontPairing) {
                LayoutTheme.PAIRING_SANS -> R.id.btn_font_sans
                LayoutTheme.PAIRING_SERIF -> R.id.btn_font_serif
                else -> R.id.btn_font_editorial
            }
        )
        binding.togglePaper.check(
            when (theme.paperTint) {
                LayoutTheme.PAPER_IVORY -> R.id.btn_paper_ivory
                LayoutTheme.PAPER_COOL -> R.id.btn_paper_cool
                else -> R.id.btn_paper_white
            }
        )
        binding.toggleTableHeader.check(
            if (theme.tableHeaderStyle == LayoutTheme.HEADER_BAND) R.id.btn_header_band
            else R.id.btn_header_rule
        )
        markSelectedSwatch()
        applying = false
    }

    // ----- preview --------------------------------------------------------------------------

    private fun renderPreview(debounce: Boolean = true) {
        previewJob?.cancel()
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounce) delay(300)
            binding.progressPreview.isVisible = true
            val currentTheme = theme
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { renderSamplePage(currentTheme) }.getOrNull()
            }
            _binding ?: return@launch
            binding.progressPreview.isVisible = false
            bitmap?.let { binding.imagePreview.setImageBitmap(it) }
        }
    }

    /**
     * Renders page 1 of the sample invoice through the real writer. PdfRenderer needs a
     * seekable fd, so the bytes take a detour through a cache file.
     */
    private fun renderSamplePage(theme: LayoutTheme): Bitmap {
        val ctx = requireContext().applicationContext
        val profile = CompanyProfileStore(ctx).load()
        val sample = SampleInvoiceFactory.build(profile)
        // The preview only shows the visible page; a stub XML keeps the writer happy.
        val bytes = ZugferdPdfA3Writer(ctx).write(
            sample, "<preview/>", profile.logoPath.takeIf { it.isNotBlank() }, theme
        )
        val cacheFile = File(ctx.cacheDir, "layout_preview.pdf")
        cacheFile.writeBytes(bytes)
        ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val targetWidth = binding.imagePreview.width.takeIf { it > 0 } ?: 1080
                    val height = (targetWidth.toFloat() / page.width * page.height).toInt()
                    val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewJob?.cancel()
        swatchButtons.clear()
        _binding = null
    }

    companion object {
        /** Accent swatches: hex → localized name (contentDescription). */
        private val SWATCHES = listOf(
            "#C15F3C" to R.string.layout_color_terracotta,
            "#8C2F39" to R.string.layout_color_bordeaux,
            "#3F6B4F" to R.string.layout_color_green,
            "#16697A" to R.string.layout_color_petrol,
            "#2E5E8C" to R.string.layout_color_blue,
            "#5B4B8A" to R.string.layout_color_violet,
            "#B8860B" to R.string.layout_color_ochre,
            "#1F1E1D" to R.string.layout_color_black
        )
    }
}
