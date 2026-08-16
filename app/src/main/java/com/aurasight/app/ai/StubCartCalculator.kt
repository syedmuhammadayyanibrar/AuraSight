package com.aurasight.app.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
/**
 * Temporary in-memory fake for testing Gemma tool-calling before the real DB-backed
 * CartCalculator exists. Deliberately minimal — flat fake price for every item, no
 * persistence, no lookup, no stock/unknown-item checks. Will be discarded once the
 * real impl (CartCalculatorImpl) is wired in. See handoff for what's simplified away.
 */
class StubCartCalculator : CartCalculator {

    override val entries: StateFlow<List<CartEntry>> = MutableStateFlow(emptyList())
    override val totalState: StateFlow<Double> = MutableStateFlow(0.0)

    private var total = 0.0
    private var paymentReceived = 0.0

    override fun addItem(name: String, quantity: Int): Result<String> {
        val lineTotal = FAKE_UNIT_PRICE * quantity
        total += lineTotal
        return Result.success("Added $quantity x $name — Rs. ${"%.2f".format(lineTotal)}")
    }

    override fun runningTotal(): Double = total

    override fun acceptPayment(amount: Double): Result<Unit> {
        if (amount < total) {
            return Result.failure(IllegalArgumentException("Payment less than total"))
        }
        paymentReceived = amount
        return Result.success(Unit)
    }

    override fun computeChange(): Double = paymentReceived - total

    override fun getCartContents(): Result<String> {
        return Result.success("The cart is empty (stub)")
    }

    private companion object {
        const val FAKE_UNIT_PRICE = 10.0
    }
}
