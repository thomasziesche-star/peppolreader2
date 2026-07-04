package com.ziesche.peppolreader.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.navigation.NavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ziesche.peppolreader.BuildConfig
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.ui.InvoiceListFragment
import com.ziesche.peppolreader.ui.InvoiceViewModel

/**
 * Routes everything that arrives via Intent: notification deep-links (invoice detail /
 * Create tab) and file imports via ACTION_VIEW / ACTION_SEND, including the pending-URI
 * handshake with [InvoiceListFragment] and the reader/creator tab switching.
 *
 * All collaborators are lambdas: the NavController and BottomNavigationView only exist
 * after setContentView, the list fragment only while the reader tab is up.
 */
class ImportIntentHandler(
    private val nav: () -> NavController,
    private val bottomNav: () -> BottomNavigationView,
    private val viewModel: () -> InvoiceViewModel,
    private val listFragment: () -> InvoiceListFragment?
) {

    /**
     * URI handed in via ACTION_VIEW / ACTION_SEND before the list fragment is ready.
     * Consumed by InvoiceListFragment.onViewCreated (via the activity), then cleared.
     * Intentionally NOT saved across re-creation — same behaviour as before the extraction.
     */
    var pendingImportUri: Uri? = null
        private set

    fun handle(intent: Intent?) {
        if (intent == null) return

        // Notification deep-link: overdue outgoing invoice → bring the Create tab up.
        if (intent.getBooleanExtra(EXTRA_OPEN_CREATOR, false)) {
            intent.removeExtra(EXTRA_OPEN_CREATOR)
            if (BuildConfig.ENABLE_INVOICE_CREATOR) switchToCreatorTab()
            return
        }

        // Notification deep-link
        val deepLinkId = intent.getLongExtra(EXTRA_OPEN_INVOICE_ID, -1L)
        if (deepLinkId != -1L) {
            // Clear the extra so re-rotation doesn't re-trigger
            intent.removeExtra(EXTRA_OPEN_INVOICE_ID)
            openInvoiceFromDeepLink(deepLinkId)
            return
        }

        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data ?: return
            Intent.ACTION_SEND -> intent.extraStreamCompat() ?: return
            else -> return
        }

        val current = listFragment()
        if (current != null) {
            current.importExternalUri(uri)
        } else {
            pendingImportUri = uri
            // Imports belong to the reader: bring the list fragment up so it
            // drains the pending URI (e.g. when the Creator tab is active).
            switchToReaderTab()
        }
    }

    /** Called by InvoiceListFragment after it is ready, to drain any pending import URI. */
    fun consumePendingImportUri(): Uri? {
        val uri = pendingImportUri
        pendingImportUri = null
        return uri
    }

    /** Brings the Create tab's list fragment to the front (tab switch + pop if needed). */
    fun switchToCreatorTab() {
        val navController = nav()
        if (navController.currentDestination?.id != R.id.invoiceCreatorListFragment) {
            bottomNav().selectedItemId = R.id.invoiceCreatorListFragment
        }
        if (navController.currentDestination?.id != R.id.invoiceCreatorListFragment) {
            navController.popBackStack(R.id.invoiceCreatorListFragment, false)
        }
    }

    /** Brings the Read tab's list fragment to the front (tab switch + pop if needed). */
    fun switchToReaderTab() {
        val navController = nav()
        if (navController.currentDestination?.id != R.id.invoiceListFragment) {
            bottomNav().selectedItemId = R.id.invoiceListFragment
        }
        // Restoring the tab may land on a saved deep screen (e.g. detail).
        if (navController.currentDestination?.id != R.id.invoiceListFragment) {
            navController.popBackStack(R.id.invoiceListFragment, false)
        }
    }

    private fun openInvoiceFromDeepLink(invoiceId: Long) {
        viewModel().selectInvoiceById(invoiceId)
        val navController = nav()
        // Already on detail? Don't push again.
        if (navController.currentDestination?.id == R.id.invoiceDetailFragment) return
        // The action only exists on the list destination; make sure we are there
        // (the Creator tab could be active) before pushing the detail screen.
        switchToReaderTab()
        if (navController.currentDestination?.id == R.id.invoiceListFragment) {
            navController.navigate(R.id.action_invoiceList_to_invoiceDetail)
        } else {
            navController.navigate(R.id.invoiceDetailFragment)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.extraStreamCompat(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    companion object {
        /** Intent extra used by [com.ziesche.peppolreader.notifications.DueDateWorker] for the deep-link tap. */
        const val EXTRA_OPEN_INVOICE_ID = "com.ziesche.peppolreader.OPEN_INVOICE_ID"

        /** Intent extra (Boolean): overdue outgoing-invoice notification → open the Create tab. */
        const val EXTRA_OPEN_CREATOR = "com.ziesche.peppolreader.OPEN_CREATOR"
    }
}
