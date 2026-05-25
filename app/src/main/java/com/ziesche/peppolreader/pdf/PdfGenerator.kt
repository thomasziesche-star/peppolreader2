package com.ziesche.peppolreader.pdf

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.print.PrintAttributes
import android.print.pdf.PrintedPdfDocument
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ziesche.peppolreader.data.model.DocumentType
import com.ziesche.peppolreader.data.model.ParsedInvoice
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import com.ziesche.peppolreader.R

/**
 * Generates HTML and PDF from parsed invoice data
 */
class PdfGenerator(private val context: Context) {
    
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)
    private val inputDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val outputDateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
    
    /**
     * Generate HTML content from parsed invoice
     */
    fun generateHtml(invoice: ParsedInvoice, fileName: String = "", isDarkMode: Boolean = false): String {
        val titleResId = when (invoice.documentTypeCode) {
            DocumentType.CREDIT_NOTE -> R.string.label_credit_note
            DocumentType.CORRECTED_INVOICE -> R.string.label_corrected_invoice
            else -> R.string.label_invoice
        }
        val documentTitle = context.getString(titleResId)
        // Define colors based on mode
        val cssVariables = if (isDarkMode) {
            """
            --bg-color: #141413;
            --text-color: #E8E6DC;
            --panel-bg: #4A4944;
            --panel-text: #E8E6DC;
            --muted-text: #B0AEA5;
            --border-color: #4A4944;
            --accent-color: #D97757;
            --charge-bg: #1A1A19;
            --total-row-border: #E8E6DC;
            """
        } else {
            """
            --bg-color: #FAF9F5;
            --text-color: #141413;
            --panel-bg: #E8E6DC;
            --panel-text: #141413;
            --muted-text: #B0AEA5;
            --border-color: #E8E6DC;
            --accent-color: #D97757;
            --charge-bg: #FAF9F5;
            --total-row-border: #141413;
            """
        }

        return buildString {
            append("""
<!DOCTYPE html>
<html lang="${context.getString(R.string.html_lang_code)}">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$documentTitle ${invoice.invoice.id}</title>
    <style>
        :root {
            $cssVariables
        }
        
        @media print {
            :root {
                --bg-color: #ffffff !important;
                --text-color: #000000 !important;
                --panel-bg: #f0f0f0 !important;
                --panel-text: #000000 !important;
                --muted-text: #666666 !important;
                --border-color: #cccccc !important;
                --accent-color: #000000 !important;
                --charge-bg: #ffffff !important;
                --total-row-border: #000000 !important;
            }
            body { 
                background: white !important;
                color: black !important;
                margin: 0;
                padding: 20mm !important;
            }
            .container { width: 100%; max-width: none; }
            .header { border-bottom-color: black !important; }
            th { border-bottom-color: black !important; color: black !important; }
        }

        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 14px;
            line-height: 1.5;
            color: var(--text-color);
            background: var(--bg-color);
            padding: 40px;
        }
        .container { max-width: 700px; margin: 0 auto; }
        .header {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
            margin-bottom: 30px;
            padding-bottom: 20px;
            border-bottom: 3px solid var(--accent-color);
        }

        .logo {
            font-size: 24px;
            font-weight: bold;
            color: var(--accent-color);
        }
        .invoice-meta { text-align: right; }
        .invoice-title {
            font-size: 28px;
            color: var(--text-color);
            margin-bottom: 10px;
        }
        .meta-row {
            display: flex;
            justify-content: flex-end;
            gap: 10px;
            font-size: 13px;
            color: var(--muted-text);
        }
        .meta-label { font-weight: 600; }
        .parties {
            display: flex;
            gap: 40px;
            margin-bottom: 30px;
        }
        .party {
            flex: 1;
            padding: 16px;
            background: var(--panel-bg);
            border-radius: 8px;
            color: var(--panel-text);
        }
        .party h3 {
            font-size: 12px;
            text-transform: uppercase;
            color: var(--muted-text);
            margin-bottom: 8px;
            letter-spacing: 0.5px;
        }
        .party-name {
            font-size: 16px;
            font-weight: 600;
            color: var(--panel-text);
            margin-bottom: 4px;
        }
        .party-address { color: var(--panel-text); opacity: 0.8; font-size: 13px; }
        .party-tax { color: var(--muted-text); font-size: 12px; margin-top: 8px; }
        
        table {
            width: 100%;
            border-collapse: collapse;
            margin-bottom: 30px;
        }
        th {
            text-align: left;
            padding: 12px 8px;
            border-bottom: 2px solid var(--accent-color);
            color: var(--accent-color);
            font-size: 12px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        th.right, td.right { text-align: right; }
        td {
            padding: 12px 8px;
            border-bottom: 1px solid var(--border-color);
            vertical-align: top;
            color: var(--text-color);
        }
        tr.charge td {
            font-style: italic;
            color: var(--muted-text);
            background: var(--charge-bg);
        }
        .item-id {
            font-family: monospace;
            font-size: 12px;
            color: var(--muted-text);
        }
        
        .totals {
            display: flex;
            justify-content: flex-end;
        }
        .totals-table {
            width: 280px;
        }
        .totals-table td {
            padding: 8px 0;
            border-bottom: 1px solid var(--border-color);
        }
        .totals-table .total-row td {
            font-size: 18px;
            font-weight: bold;
            border-top: 2px solid var(--total-row-border);
            border-bottom: none;
            padding-top: 12px;
        }
        
        .footer {
            margin-top: 40px;
            padding-top: 20px;
            border-top: 1px solid var(--border-color);
            text-align: center;
            color: var(--muted-text);
            font-size: 12px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="logo">${escapeHtml(invoice.supplier.name)}</div>
            <div class="invoice-meta">
                <div class="invoice-title">$documentTitle</div>
                <div class="meta-row">
                    <span class="meta-label">${context.getString(R.string.label_no)}</span>
                    <span>${escapeHtml(invoice.invoice.id)}</span>
                </div>
                <div class="meta-row">
                    <span class="meta-label">${context.getString(R.string.label_date)}</span>
                    <span>${formatDate(invoice.invoice.issueDate)}</span>
                </div>
""")
            
            invoice.invoice.dueDate?.let {
                append("""
                <div class="meta-row">
                    <span class="meta-label">${context.getString(R.string.label_due_date)}</span>
                    <span>${formatDate(it)}</span>
                </div>
""")
            }
            
            invoice.invoice.orderId?.let {
                append("""
                <div class="meta-row">
                    <span class="meta-label">${context.getString(R.string.label_order_id)}</span>
                    <span>${escapeHtml(it)}</span>
                </div>
""")
            }
            
            append("""
            </div>
        </div>
        
        <div class="parties">
            <div class="party">
                <h3>${context.getString(R.string.label_supplier)}</h3>
                <div class="party-name">${escapeHtml(invoice.supplier.name)}</div>
                <div class="party-address">
                    ${invoice.supplier.street?.let { escapeHtml(it) + "<br>" } ?: ""}
                    ${invoice.supplier.zip ?: ""} ${invoice.supplier.city ?: ""}<br>
                    ${invoice.supplier.country ?: ""}
                </div>
                ${invoice.supplier.taxId?.let { "<div class='party-tax'>${context.getString(R.string.label_tax_id, escapeHtml(it))}</div>" } ?: ""}
                
                <div style="margin-top: 8px; font-size: 13px;">
                    ${invoice.supplier.contactName?.let { "<div style='color: var(--panel-text);'>${escapeHtml(it)}</div>" } ?: ""}
                    ${invoice.supplier.email?.let { "<div><a href='mailto:${escapeHtml(it)}' style='color: var(--accent-color); text-decoration: none;'>${escapeHtml(it)}</a></div>" } ?: ""}
                    ${invoice.supplier.phone?.let { "<div><a href='tel:${escapeHtml(it)}' style='color: var(--accent-color); text-decoration: none;'>${escapeHtml(it)}</a></div>" } ?: ""}
                </div>
            </div>
            <div class="party">
                <h3>${context.getString(R.string.label_customer)}</h3>
                <div class="party-name">${escapeHtml(invoice.customer.name)}</div>
                <div class="party-address">
                    ${invoice.customer.street?.let { escapeHtml(it) + "<br>" } ?: ""}
                    ${invoice.customer.zip ?: ""} ${invoice.customer.city ?: ""}<br>
                    ${invoice.customer.country ?: ""}
                </div>
                ${invoice.customer.taxId?.let { "<div class='party-tax'>${context.getString(R.string.label_tax_id, escapeHtml(it))}</div>" } ?: ""}
            </div>
        </div>
        
        <table>
            <thead>
                <tr>
                    <th style="width: 20%">${context.getString(R.string.table_article_no)}</th>
                    <th style="width: 35%">${context.getString(R.string.table_description)}</th>
                    <th class="right" style="width: 15%">${context.getString(R.string.table_quantity)}</th>
                    <th class="right" style="width: 15%">${context.getString(R.string.table_price)}</th>
                    <th class="right" style="width: 15%">${context.getString(R.string.table_total)}</th>
                </tr>
            </thead>
            <tbody>
""")
            
            for (item in invoice.items) {
                val rowClass = if (item.isCharge) " class='charge'" else ""
                append("""
                <tr$rowClass>
                    <td class="item-id">${escapeHtml(item.id)}</td>
                    <td>${escapeHtml(item.description)}</td>
                    <td class="right">${if (!item.isCharge) "${item.quantity} ${item.unit}" else ""}</td>
                    <td class="right">${if (!item.isCharge) formatCurrency(item.price) else ""}</td>
                    <td class="right">${formatCurrency(item.lineTotal)}</td>
                </tr>
""")
            }
            
            append("""
            </tbody>
        </table>
        
        <div class="totals">
            <table class="totals-table">
                <tr>
                    <td>${context.getString(R.string.total_net)}</td>
                    <td class="right">${formatCurrency(invoice.totals.lineExtension)}</td>
                </tr>
""")
            
            if (invoice.totals.chargeTotal > 0) {
                append("""
                <tr>
                    <td>${context.getString(R.string.total_surcharges)}</td>
                    <td class="right">${formatCurrency(invoice.totals.chargeTotal)}</td>
                </tr>
""")
            }
            
            if (invoice.totals.allowanceTotal > 0) {
                append("""
                <tr>
                    <td>${context.getString(R.string.total_allowances)}</td>
                    <td class="right">- ${formatCurrency(invoice.totals.allowanceTotal)}</td>
                </tr>
""")
            }
            
            append("""
                <tr>
                    <td><strong>${context.getString(R.string.total_taxable)}</strong></td>
                    <td class="right"><strong>${formatCurrency(invoice.totals.netAmount)}</strong></td>
                </tr>
""")
            
            for (subtotal in invoice.totals.taxSubtotals) {
                append("""
                <tr>
                    <td>${context.getString(R.string.total_vat)} ${subtotal.percent.toInt()}%</td>
                    <td class="right">${formatCurrency(subtotal.taxAmount)}</td>
                </tr>
""")
            }
            
            // If no tax subtotals but we have tax amount
            if (invoice.totals.taxSubtotals.isEmpty() && invoice.totals.taxAmount > 0) {
                append("""
                <tr>
                    <td>${context.getString(R.string.total_vat)}</td>
                    <td class="right">${formatCurrency(invoice.totals.taxAmount)}</td>
                </tr>
""")
            }
            
            append("""
                <tr class="total-row">
                    <td>${context.getString(R.string.total_gross)}</td>
                    <td class="right">${formatCurrency(invoice.totals.grossAmount)}</td>
                </tr>
""")
            
            if (invoice.totals.payableAmount != invoice.totals.grossAmount) {
                append("""
                <tr>
                    <td>${context.getString(R.string.total_payable)}</td>
                    <td class="right">${formatCurrency(invoice.totals.payableAmount)}</td>
                </tr>
""")
            }
            
            append("""
            </table>
        </div>
        
        <div style="margin-top: 30px; padding-top: 15px; border-top: 1px solid var(--border-color);">
            <h3 style="font-size: 12px; text-transform: uppercase; color: var(--muted-text); margin-bottom: 8px;">${context.getString(R.string.payment_info_title)}</h3>
            
            ${invoice.paymentTermsNote?.let { "<p style='margin-bottom: 15px; font-style: italic; color: var(--panel-text);'>${escapeHtml(it)}</p>" } ?: ""}
            
            ${invoice.paymentMeans?.payeeFinancialAccount?.let { account ->
                buildString {
                    append("<div style='display: flex; gap: 24px; font-size: 13px; color: var(--text-color);'>")
                    append("<div><span style='color: var(--muted-text);'>${context.getString(R.string.label_iban)}</span> ${escapeHtml(account.id)}</div>")
                    if (account.financialInstitutionBranchId != null) {
                        append("<div><span style='color: var(--muted-text);'>${context.getString(R.string.label_bic)}</span> ${escapeHtml(account.financialInstitutionBranchId)}</div>")
                    }
                    if (account.name != null) {
                        append("<div><span style='color: var(--muted-text);'>${context.getString(R.string.label_account)}</span> ${escapeHtml(account.name)}</div>")
                    }
                    append("</div>")
                }
            } ?: ""}
        </div>
        
        <div class="footer">
            <p>${context.getString(R.string.footer_thanks)}</p>
            <p>${context.getString(
                R.string.footer_generated,
                invoice.formatLabel ?: "UBL",
                if (fileName.isNotEmpty()) context.getString(R.string.footer_file_hint, fileName) else ""
            )}</p>
            <p style="margin-top: 10px; font-style: italic;">${context.getString(R.string.footer_disclaimer)}</p>
        </div>
    </div>
</body>
</html>
""")
        }
    }
    
    private fun formatCurrency(value: Double): String {
        return currencyFormat.format(value)
    }
    
    private fun formatDate(dateStr: String): String {
        return try {
            val date = inputDateFormat.parse(dateStr)
            date?.let { outputDateFormat.format(it) } ?: dateStr
        } catch (e: Exception) {
            dateStr
        }
    }
    
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
    
    /**
     * Get the Downloads directory for saving PDFs
     */
    fun getDownloadsDir(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
    
    /**
     * Generate a unique PDF filename
     */
    fun generatePdfFileName(invoiceId: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sanitizedId = invoiceId.replace(Regex("[^a-zA-Z0-9-_]"), "_")
        return "Rechnung_${sanitizedId}_$timestamp.pdf"
    }
}
