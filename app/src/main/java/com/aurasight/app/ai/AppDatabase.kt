package com.aurasight.app.ai

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Executors

@Database(entities = [Item::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "aurasight.db"
                ).addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        Executors.newSingleThreadExecutor().execute {
                            INSTANCE?.itemDao()?.insertAll(SEED_ITEMS)
                        }
                    }
                })
                // No allowMainThreadQueries — all DB calls must be on IO dispatcher
                .build().also { INSTANCE = it }
            }

        private val SEED_ITEMS = listOf(
            Item("Rice 1kg", 220.0, 30),
            Item("Sugar 1kg", 180.0, 25),
            Item("Tea Patti 250g", 260.0, 20),
            Item("Cooking Oil 1L", 480.0, 15),
            Item("Wheat Flour 1kg", 130.0, 40),
            Item("Salt 1kg", 40.0, 50),
            Item("Soap Bar", 60.0, 35),
            Item("Matchbox", 10.0, 60),
            Item("Biscuits Pack", 50.0, 30),
            Item("Detergent 1kg", 150.0, 20)
        )
    }
}
