package com.aurasight.app.ai

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "items")
data class Item(
    @PrimaryKey val name: String,
    val price: Double,
    val stock: Int
)
