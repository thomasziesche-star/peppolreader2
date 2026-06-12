package com.ziesche.peppolreader.creator.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.edit
import com.ziesche.peppolreader.creator.model.CompanyProfile
import org.json.JSONObject
import java.io.File

/**
 * SharedPreferences wrapper for the single [CompanyProfile] (sender master data).
 * Mirrors the [com.ziesche.peppolreader.ai.AiCredentialStore] pattern; uses MODE_PRIVATE.
 *
 * Also owns the company logo image: [importLogo] copies a user-picked image into
 * `filesDir/creator/` (downscaled to [MAX_LOGO_DIMENSION] px, PNG keeps transparency) so the
 * PDF writer can read it without holding a content-URI permission.
 */
class CompanyProfileStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Returns the saved profile, or an empty default when nothing has been stored yet. */
    fun load(): CompanyProfile {
        val raw = prefs.getString(KEY_PROFILE, null) ?: return CompanyProfile()
        return runCatching { CompanyProfile.fromJson(JSONObject(raw)) }
            .getOrDefault(CompanyProfile())
    }

    fun save(profile: CompanyProfile) {
        prefs.edit { putString(KEY_PROFILE, profile.toJson().toString()) }
    }

    // ----- logo ---------------------------------------------------------------------------

    /**
     * Copies the image behind [uri] into app storage, downscaled so its longest side is at most
     * [MAX_LOGO_DIMENSION] px. Returns the stored file's absolute path, or null when the image
     * cannot be decoded.
     */
    fun importLogo(uri: Uri): String? = runCatching {
        val resolver = appContext.contentResolver

        // First pass: bounds only, to pick a power-of-two sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_LOGO_DIMENSION) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null

        // Exact downscale when sampling left it above the limit.
        val longest = maxOf(decoded.width, decoded.height)
        val bitmap = if (longest > MAX_LOGO_DIMENSION) {
            val scale = MAX_LOGO_DIMENSION.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else decoded

        val file = logoFile()
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.getOrNull()

    /** Deletes the stored logo file (the caller clears [CompanyProfile.logoPath] itself). */
    fun deleteLogo() {
        logoFile().delete()
    }

    private fun logoFile(): File = File(File(appContext.filesDir, "creator"), LOGO_FILE)

    companion object {
        private const val NAME = "creator_prefs"
        private const val KEY_PROFILE = "company_profile"
        private const val LOGO_FILE = "company_logo.png"
        private const val MAX_LOGO_DIMENSION = 1000
    }
}
