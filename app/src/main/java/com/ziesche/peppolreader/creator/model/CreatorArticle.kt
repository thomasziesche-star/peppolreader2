package com.ziesche.peppolreader.creator.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry of the article/service catalog ("Artikelstamm") for the Invoice Creator. Stored in
 * its own `creator_articles` table; the name is unique, so saving an article with a known name
 * updates it in place instead of duplicating it. Picking an article in the invoice editor
 * pre-fills a new [CreatorLine]; the article number stays catalog-internal.
 */
@Entity(
    tableName = "creator_articles",
    indices = [Index(value = ["name"], unique = true)]
)
data class CreatorArticle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val articleNumber: String? = null,
    val unit: String = "C62",
    val unitPrice: Double = 0.0,
    val vatRate: Double = 19.0,
    val updatedAt: Long = System.currentTimeMillis()
)
