package com.ziesche.peppolreader.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.ziesche.peppolreader.R

/**
 * Renders each page of an open [PdfRenderer] into a card-wrapped [ImageView].
 *
 * The renderer is owned by the host fragment (which opens/closes it tied to the
 * view lifecycle). Pages are rendered synchronously on bind at a width matching
 * the RecyclerView; the bitmap is held by the ImageView until the view is rebound,
 * which keeps memory bounded by the number of attached view-holders.
 *
 * PdfRenderer allows only one open page at a time, so [openPage] / [Page.close]
 * are scoped to a single bind call.
 */
class PdfPageAdapter(
    private val renderer: PdfRenderer,
    /** Target render width in pixels; the RecyclerView's measured width. */
    private val targetWidthPx: Int
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    override fun getItemCount(): Int = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view.findViewById(R.id.pdf_page_image))
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        renderer.openPage(position).use { page ->
            val width = if (targetWidthPx > 0) targetWidthPx else page.width
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE) // PdfRenderer expects a white background
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            holder.image.setImageBitmap(bitmap)
        }
        holder.image.contentDescription =
            holder.image.context.getString(R.string.cd_pdf_page, position + 1, itemCount)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.image.setImageBitmap(null)
    }

    class PageViewHolder(val image: ImageView) : RecyclerView.ViewHolder(image.rootView)
}
