package com.ziesche.peppolreader.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Decodes [file] with downsampling so neither side exceeds [maxDimension] px, using the
 * two-pass `inJustDecodeBounds` + `inSampleSize` pattern. This avoids loading a full-resolution
 * bitmap into memory (the Play Console "BitmapFactory.Options parameter missing" advisory).
 *
 * Mirrors the sampling logic in [com.ziesche.peppolreader.creator.data.CompanyProfileStore.importLogo],
 * but reads from a file path rather than a content stream. Returns null when the file is missing
 * or cannot be decoded.
 */
fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
    if (!file.exists()) return null

    // First pass: bounds only, to pick a power-of-two sample size.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}
