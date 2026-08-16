package com.aurasight.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RealCartCalculator(private val itemDao: ItemDao) : CartCalculator {
    private val _entries = MutableStateFlow<List<CartEntry>>(emptyList())
    override val entries: StateFlow<List<CartEntry>> = _entries.asStateFlow()

    private val _totalState = MutableStateFlow(0.0)
    override val totalState: StateFlow<Double> = _totalState.asStateFlow()

    private var paymentReceived = 0.0

    override fun addItem(name: String, quantity: Int): Result<String> {
        val item = itemDao.getByName(name)
            ?: return Result.failure(NoSuchElementException("No item named \"$name\""))
        if (quantity > item.stock) {
            return Result.failure(IllegalStateException("Not enough stock of \"${item.name}\""))
        }
        itemDao.update(item.copy(stock = item.stock - quantity))
        val lineTotal = item.price * quantity
        
        val currentEntries = _entries.value.toMutableList()
        currentEntries.add(CartEntry(item.name, quantity, item.price, lineTotal))
        _entries.value = currentEntries
        
        _totalState.value += lineTotal
        return Result.success("Added $quantity x ${item.name} — Rs. ${"%.2f".format(lineTotal)}")
    }

    override fun runningTotal(): Double = _totalState.value

    override fun acceptPayment(amount: Double): Result<Unit> {
        if (amount < _totalState.value) return Result.failure(IllegalArgumentException("Payment less than total"))
        paymentReceived = amount
        return Result.success(Unit)
    }

    override fun computeChange(): Double = paymentReceived - _totalState.value

    override fun getCartContents(): Result<String> {
        val currentEntries = _entries.value
        if (currentEntries.isEmpty()) {
            return Result.success("The cart is currently empty.")
        }
        val sb = java.lang.StringBuilder()
        sb.append("Cart Contents:\n")
        for (entry in currentEntries) {
            sb.append("- ${entry.quantity}x ${entry.name} at Rs. ${entry.price} each (Total: Rs. ${entry.lineTotal})\n")
        }
        sb.append("Running Total: Rs. ${_totalState.value}")
        return Result.success(sb.toString())
    }
}
