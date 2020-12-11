package io.pleo.antaeus.core.services

import io.pleo.antaeus.models.Currency
import java.math.BigDecimal
import kotlin.random.Random

class CurrencyConverterService {
    /**
     * Converts value from one currency to the other contacting external API.
     *
     * @param value to be converted.
     * @param fromCurrency
     * @param toCurrency
     * @throws NetworkException if API doesn't respond.
     */
    fun convertCurrency(value: BigDecimal, fromCurrency: Currency, toCurrency: Currency): BigDecimal{
        return BigDecimal(Random.nextDouble(10.0, 500.0))
    }
}