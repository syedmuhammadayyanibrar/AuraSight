package com.aurasight.app.ai

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Entity(tableName = "khata_entries")
data class KhataEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val customerName: String,
    val amount: Double,
    val type: String, // "credit" or "payment"
    val timestamp: Long
)

@Dao
interface KhataDao {
    @Insert
    fun insert(entry: KhataEntry): Long

    @Query("SELECT * FROM khata_entries WHERE customerName = :customerName ORDER BY timestamp DESC")
    fun getEntriesForCustomer(customerName: String): List<KhataEntry>

    @Query("SELECT DISTINCT customerName FROM khata_entries")
    fun getAllCustomers(): List<String>
}
