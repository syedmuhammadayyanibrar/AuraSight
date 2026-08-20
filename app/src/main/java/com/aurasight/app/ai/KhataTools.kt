package com.aurasight.app.ai

import android.util.Log

private const val TAG = "AuraSight/KhataTools"

class KhataToolSet(private val dao: KhataDao) : ToolSet {

    @Tool(description = "Add a credit or payment entry to a customer's Khata (ledger). Type must be 'credit' (udhaar) or 'payment' (jama).")
    fun addKhataEntry(
        @ToolParam(description = "Name of the customer") customerName: String,
        @ToolParam(description = "Amount in Rupees") amount: Double,
        @ToolParam(description = "Type of entry: 'credit' or 'payment'") type: String
    ): Map<String, Any> {
        Log.d(TAG, "addKhataEntry(customer='$customerName', amount=$amount, type='$type')")
        
        if (type != "credit" && type != "payment") {
            return mapOf("status" to "error", "message" to "Invalid type. Must be 'credit' or 'payment'")
        }
        
        val entry = KhataEntry(
            customerName = customerName,
            amount = amount,
            type = type,
            timestamp = System.currentTimeMillis()
        )
        dao.insert(entry)
        
        return mapOf("status" to "ok", "message" to "Entry added successfully")
    }

    @Tool(description = "Get the current outstanding balance for a specific customer in their Khata. Returns a positive number if they owe money (udhaar).")
    fun getKhataBalance(
        @ToolParam(description = "Name of the customer") customerName: String
    ): Double {
        Log.d(TAG, "getKhataBalance(customer='$customerName')")
        val entries = dao.getEntriesForCustomer(customerName)
        var totalCredit = 0.0
        var totalPayment = 0.0
        
        for (entry in entries) {
            if (entry.type == "credit") totalCredit += entry.amount
            if (entry.type == "payment") totalPayment += entry.amount
        }
        
        val balance = totalCredit - totalPayment
        Log.d(TAG, "  -> Balance: Rs.$balance")
        return balance
    }

    @Tool(description = "List all customers who have a Khata (ledger) with us.")
    fun listKhataCustomers(): List<String> {
        Log.d(TAG, "listKhataCustomers()")
        return dao.getAllCustomers()
    }
}
