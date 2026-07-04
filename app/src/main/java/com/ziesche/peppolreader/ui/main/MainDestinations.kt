package com.ziesche.peppolreader.ui.main

import com.ziesche.peppolreader.R

/** Destination groups shared between the toolbar-title listener and the menu handler. */
object MainDestinations {

    /**
     * All Invoice Creator destinations: they hide the FAB, rebrand the toolbar headline and
     * route the "Dashboard" menu action to the revenue dashboard.
     */
    val creator = setOf(
        R.id.invoiceCreatorListFragment,
        R.id.invoiceCreatorEditFragment,
        R.id.companyProfileFragment,
        R.id.creatorCustomerListFragment,
        R.id.creatorArticleListFragment,
        R.id.creatorDashboardFragment
    )
}
