package com.ziesche.peppolreader.ui.onboarding

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.core.view.updateLayoutParams
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.util.AppPreferences
import kotlin.math.abs

/**
 * Full-screen intro shown on launch until the user ticks "don't show again". Hosts a three-page
 * [ViewPager2] (welcome + language picker, the 3-step how-to, the privacy/backup notice). It is a
 * standalone activity — not a nav-graph destination — so it can be truly full-screen without the
 * host toolbar / bottom nav / FAB, and so a language change (which recreates the activity) simply
 * re-renders the intro in the new locale.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var nextButton: MaterialButton
    private lateinit var dontShowAgain: MaterialCheckBox
    private val dots = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.onboarding_pager)
        nextButton = findViewById(R.id.btn_next)
        dontShowAgain = findViewById(R.id.check_dont_show_again)

        pager.adapter = OnboardingPagerAdapter(onLanguageSelected = ::applyLanguage)
        pager.setPageTransformer(::transformPage)

        buildDots(pager.adapter?.itemCount ?: 0)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                updateNextButton(position)
            }
        })
        updateDots(0)
        updateNextButton(0)

        nextButton.setOnClickListener {
            val last = (pager.adapter?.itemCount ?: 1) - 1
            if (pager.currentItem < last) {
                pager.currentItem += 1
            } else {
                finishOnboarding()
            }
        }
        findViewById<MaterialButton>(R.id.btn_skip).setOnClickListener { finishOnboarding() }
    }

    /** Applies the picked app language; AppCompatDelegate recreates the activity in the new locale. */
    private fun applyLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        // Mark the locale as user-chosen so the DE first-run default never overrides it later.
        AppPreferences.get(this).edit { putBoolean(AppPreferences.KEY_LOCALE_INITIALIZED, true) }
    }

    /** Honors the "don't show again" tick, then leaves the intro. */
    private fun finishOnboarding() {
        if (dontShowAgain.isChecked) {
            AppPreferences.get(this).edit { putBoolean(AppPreferences.KEY_ONBOARDING_HIDDEN, true) }
        }
        finish()
    }

    // ----- page indicator -------------------------------------------------------------------

    private fun buildDots(count: Int) {
        val container = findViewById<LinearLayout>(R.id.dots)
        container.removeAllViews()
        dots.clear()
        val size = dp(8)
        val margin = dp(4)
        repeat(count) {
            val dot = View(this).apply {
                setBackgroundResource(R.drawable.onboarding_dot)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = margin
                    marginEnd = margin
                }
            }
            container.addView(dot)
            dots.add(dot)
        }
    }

    /** Active dot is widened to a pill and fully opaque; the others stay small and faded. */
    private fun updateDots(active: Int) {
        dots.forEachIndexed { index, dot ->
            val isActive = index == active
            dot.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = if (isActive) dp(24) else dp(8)
            }
            dot.alpha = if (isActive) 1f else 0.3f
        }
    }

    private fun updateNextButton(position: Int) {
        val last = (pager.adapter?.itemCount ?: 1) - 1
        nextButton.setText(if (position == last) R.string.onboarding_start else R.string.onboarding_next)
    }

    // ----- page animation -------------------------------------------------------------------

    /** Cross-fade + gentle inward scale as pages slide, for a livelier feel than a plain swipe. */
    private fun transformPage(page: View, position: Float) {
        page.alpha = 1f - (abs(position) * 0.5f).coerceIn(0f, 1f)
        val scale = 1f - (abs(position) * 0.10f).coerceIn(0f, 0.10f)
        page.scaleX = scale
        page.scaleY = scale
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
