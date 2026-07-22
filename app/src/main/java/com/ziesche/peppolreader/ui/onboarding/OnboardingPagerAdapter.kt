package com.ziesche.peppolreader.ui.onboarding

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.ui.main.LocaleSwitcher

/**
 * Three static intro pages for [OnboardingActivity]. The first page carries the language
 * picker; a tap forwards the language tag to [onLanguageSelected] (which re-applies the app
 * locale and recreates the activity, so the whole intro re-renders translated).
 */
class OnboardingPagerAdapter(
    private val onLanguageSelected: (String) -> Unit
) : RecyclerView.Adapter<OnboardingPagerAdapter.PageViewHolder>() {

    private val layouts = intArrayOf(
        R.layout.onboarding_page_welcome,
        R.layout.onboarding_page_steps,
        R.layout.onboarding_page_privacy
    )

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int = layouts.size

    override fun getItemViewType(position: Int): Int = layouts[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        if (getItemViewType(position) == R.layout.onboarding_page_welcome) {
            bindLanguagePage(holder)
        }
    }

    private fun bindLanguagePage(holder: PageViewHolder) {
        val active = LocaleSwitcher.currentLanguage()
        val buttons = mapOf(
            "de" to holder.itemView.findViewById<MaterialButton>(R.id.btn_lang_de),
            "en" to holder.itemView.findViewById<MaterialButton>(R.id.btn_lang_en),
            "nl" to holder.itemView.findViewById<MaterialButton>(R.id.btn_lang_nl),
            "fr" to holder.itemView.findViewById<MaterialButton>(R.id.btn_lang_fr),
            "pl" to holder.itemView.findViewById<MaterialButton>(R.id.btn_lang_pl)
        )
        val accent = MaterialColors.getColor(
            holder.itemView, com.google.android.material.R.attr.colorPrimary
        )
        val muted = MaterialColors.getColor(
            holder.itemView, com.google.android.material.R.attr.colorOutline
        )
        buttons.forEach { (tag, button) ->
            val isActive = tag == active
            // Highlight the active language: thicker accent stroke + accent label.
            button.strokeColor = ColorStateList.valueOf(if (isActive) accent else muted)
            button.strokeWidth = if (isActive) 4 else 2
            button.setOnClickListener { onLanguageSelected(tag) }
        }
    }
}
