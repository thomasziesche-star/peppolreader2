package com.ziesche.peppolreader.creator.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry of the customer master ("Kundenstamm") for the Invoice Creator. Stored in its own
 * `creator_customers` table; the name is unique, so generating an invoice for a known buyer
 * silently refreshes their address instead of duplicating them.
 */
@Entity(
    tableName = "creator_customers",
    indices = [Index(value = ["name"], unique = true)]
)
data class CreatorCustomer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val street: String? = null,
    val zip: String? = null,
    val city: String? = null,
    val country: String? = null,
    val vatId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
