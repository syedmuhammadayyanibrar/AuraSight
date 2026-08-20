package com.aurasight.app.ai

import android.util.Log
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AuraSight/Cart"

data class CartEntry(
    val name: String,
    val quantity: Int,
    val price: Double,
    val lineTotal: Double
)

/**
 * Tools Gemma can call for cart/money actions. Gemma NEVER computes these
 * itself — this is the enforcement point for that rule.
 * Delegates to Dev A's CartCalculator (interface below is the contract;
 * Dev A provides the real implementation).
 */
interface CartCalculator {
    val entries: StateFlow<List<CartEntry>>
    val totalState: StateFlow<Double>

    fun addItem(name: String, quantity: Int): Result<String>
    fun runningTotal(): Double
    fun acceptPayment(amount: Double): Result<Unit>
    fun computeChange(): Double
    fun getCartContents(): Result<String>
}

class CartToolSet(private val cart: CartCalculator) : ToolSet {

    @Tool(description = "Add an item to the current cart by name and quantity")
    fun addItemToCart(
        @ToolParam(description = "Item name as spoken by the user") name: String,
        @ToolParam(description = "Quantity, default 1") quantity: Int = 1,
    ): Map<String, Any> {
        Log.d(TAG, "addItemToCart(name='$name', qty=$quantity)")
        val result = cart.addItem(name, quantity)
        val total  = cart.runningTotal()
        return if (result.isSuccess) {
            Log.d(TAG, "  ✓ ${result.getOrNull()} | runningTotal=Rs.${"%.2f".format(total)}")
            mapOf("status" to "ok", "message" to result.getOrNull().orEmpty(), "runningTotal" to total)
        } else {
            Log.w(TAG, "  ✗ ${result.exceptionOrNull()?.message}")
            mapOf("status" to "error", "message" to (result.exceptionOrNull()?.message ?: "item not found"))
        }
    }

    @Tool(description = "Get the current running total of the cart")
    fun getRunningTotal(): Double {
        val total = cart.runningTotal()
        Log.d(TAG, "getRunningTotal() → Rs.${"%.2f".format(total)}")
        return total
    }

    @Tool(description = "Record payment received from the customer and get change owed")
    fun acceptPaymentAndGetChange(
        @ToolParam(description = "Amount handed over by customer, in Rupees") amount: Double,
    ): Map<String, Any> {
        Log.d(TAG, "acceptPayment(amount=Rs.${"%.2f".format(amount)})")
        val result = cart.acceptPayment(amount)
        return if (result.isSuccess) {
            val change = cart.computeChange()
            Log.d(TAG, "  ✓ change=Rs.${"%.2f".format(change)}")
            mapOf("status" to "ok", "change" to change)
        } else {
            Log.w(TAG, "  ✗ ${result.exceptionOrNull()?.message}")
            mapOf("status" to "error", "message" to (result.exceptionOrNull()?.message ?: "payment insufficient"))
        }
    }

    @Tool(
        description = "Returns a text summary of everything currently added to the cart/khata, including item names, quantities, and their line totals."
    )
    fun getCartContents(): String {
        Log.d(TAG, "Tool call: getCartContents()")
        return cart.getCartContents().getOrElse { it.message ?: "Failed" }
    }
}
