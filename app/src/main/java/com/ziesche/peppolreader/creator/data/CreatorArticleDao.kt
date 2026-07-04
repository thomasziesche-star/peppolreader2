package com.ziesche.peppolreader.creator.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ziesche.peppolreader.creator.model.CreatorArticle

@Dao
interface CreatorArticleDao {

    @Query("SELECT * FROM creator_articles ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<CreatorArticle>

    @Query("SELECT * FROM creator_articles ORDER BY name COLLATE NOCASE")
    fun getAllLiveData(): LiveData<List<CreatorArticle>>

    /** The unique name index makes this an update for an already-known article. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(article: CreatorArticle)

    @Delete
    suspend fun delete(article: CreatorArticle)
}
