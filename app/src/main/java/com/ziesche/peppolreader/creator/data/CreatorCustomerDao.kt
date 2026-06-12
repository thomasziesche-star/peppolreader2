package com.ziesche.peppolreader.creator.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ziesche.peppolreader.creator.model.CreatorCustomer

@Dao
interface CreatorCustomerDao {

    @Query("SELECT * FROM creator_customers ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<CreatorCustomer>

    @Query("SELECT * FROM creator_customers ORDER BY name COLLATE NOCASE")
    fun getAllLiveData(): LiveData<List<CreatorCustomer>>

    /** The unique name index makes this an update for an already-known customer. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(customer: CreatorCustomer)

    @Delete
    suspend fun delete(customer: CreatorCustomer)
}
