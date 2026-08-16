package com.aurasight.app.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(items: List<Item>)

    @Query("SELECT * FROM items WHERE name = :name COLLATE NOCASE LIMIT 1")
    fun getByName(name: String): Item?

    @Update
    fun update(item: Item)
}
