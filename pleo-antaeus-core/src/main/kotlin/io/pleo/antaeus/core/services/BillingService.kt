package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus
import io.pleo.antaeus.models.Money
import mu.KotlinLogging
import java.time.LocalDateTime
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timerTask

open class BillingService(
        private val paymentProvider: PaymentProvider,
        private val invoiceService: InvoiceService,
        private val customerService: CustomerService,
        private val converterService: CurrencyConverterService,
        private val notificationService: NotificationService
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Schedule task to run every day. The task checks if it's the 1st of the month to settle all pending invoices.
     * The task also checks every day if there are any pending invoices that failed in the past and retries again
     * if they didn't reach max retry number.
     *
     * @param dayOfMonth
     */
    fun schedulePaymentOfPendingInvoices(dayOfMonth: Int, maxRetry: Int) {
        // TODO replace with https://github.com/shyiko/skedule library for more accurate time scheduling tasks
        Timer().scheduleAtFixedRate(timerTask {
            if (LocalDateTime.now().dayOfMonth == dayOfMonth) {
                settleInvoices(getPendingInvoices(), Int.MAX_VALUE)
            }

            // Everyday retry pending invoices that have 1 <= retry <= maxRetry
            settleInvoices(getPendingInvoicesWithRetry(), maxRetry)
        }, 0, TimeUnit.DAYS.toMillis(1))
    }


    private fun settleInvoices(invoices: List<Invoice>, maxRetry: Int) {
        for (invoice in invoices) {
            settleInvoice(invoice, maxRetry)
        }
    }

    /**
     * Settle pending invoice with number of retries less than maxRetry.
     * In case of a false response or a Exception from paymentProvider, remove customer subscription.
     *
     * NetworkException will increase the invoice retry number and the scheduleAtFixedRate running every day,
     * will retry only the ones that have the retry number greater than 0.
     *
     * @param invoice
     * @param maxRetry
     */
    internal fun settleInvoice(invoice: Invoice, maxRetry: Int) {
        if (invoice.status == InvoiceStatus.PAID) {
            logger.info { "Customer '${invoice.customerId}' already charged for invoice '${invoice.id}'" }
            return
        } else if (invoice.retry > maxRetry) {
            logger.info { "Failed too many times to charge invoice '${invoice.id}'" }
            return
        }

        try {
            if (paymentProvider.charge(invoice)) {
                logger.info { "Customer '${invoice.customerId}' successfully charged for invoice '${invoice.id}'" }

                invoiceService.updateStatus(invoice.id, InvoiceStatus.PAID)
                customerService.updateHasSubscription(invoice.customerId, true)
            } else {
                logger.info { "Customer '${invoice.customerId}' has insufficient funds for invoice '${invoice.id}'." }

                // Invoice can only be settled by the customer adding more funds to his account
                // Nothing to do on backend side so resetting retry number
                invoiceService.resetRetry(invoice.id)
                // Remove subscription
                customerService.updateHasSubscription(invoice.customerId, false)
                // Notify customer about error so that he can take action
                notificationService.notifyCustomer(
                    id = invoice.customerId,
                    message = "Subscription suspended because of insufficient funds!"
                )
            }
        } catch (e: CustomerNotFoundException) {
            logger.info { "Customer '${invoice.customerId}' not found to pay invoice '${invoice.id}'"}
        } catch (e: CurrencyMismatchException) {
            convertCurrencyForInvoiceAndTryAgain(invoice, maxRetry)
        } catch (e: NetworkException) {
            logger.error {
                "NetworkException: ${e.message}. Removing subscription for customer '${invoice.customerId}' and " +
                "increasing retry number for invoice '${invoice.id}'"
            }

            // Increment retry number so that invoice can be retried by schedulePaymentOfPendingInvoices
            invoiceService.incrementRetry(invoice.id)
            // Remove subscription
            customerService.updateHasSubscription(invoice.customerId, false)
            // Notify customer about error so that he can take action
            notificationService.notifyCustomer(
                id = invoice.customerId,
                message = "Subscription suspended because of internal server error! Automatic retry tomorrow."
            )
        }
    }

    /**
     * Convert invoice amount to customer currency and try again to Settle pending invoice calling settleInvoice().
     *
     * NetworkException will increase the invoice retry number and the scheduleAtFixedRate running every day,
     * will retry only the ones that have the retry number greater than 0.
     *
     * @param invoice
     * @param maxRetry
     */
    private fun convertCurrencyForInvoiceAndTryAgain(invoice: Invoice, maxRetry: Int) {
        try {
            logger.info { "Customer '${invoice.customerId}' has different currency than invoice '${invoice.id}'" }

            // Convert amount to customer currency
            val customer = customerService.fetch(invoice.customerId)
            val convertedAmount = converterService.convertCurrency(invoice.amount.value, invoice.amount.currency, customer.currency)
            val convertedInvoice = invoice.copy(amount = Money(convertedAmount, customer.currency))

            // Try again with new invoice containing converted amount
            settleInvoice(convertedInvoice, maxRetry)
        } catch (e: NetworkException) {
            logger.error {
                "CurrencyConverterService NetworkException: ${e.message}. " +
                "Removing subscription for customer '${invoice.customerId}' and increasing retry number for invoice '${invoice.id}'"
            }

            // Increment retry number so that invoice can be retried again by schedulePaymentOfPendingInvoices
            invoiceService.incrementRetry(invoice.id)
            // Remove subscription
            customerService.updateHasSubscription(invoice.customerId, false)
            // Notify customer about error so that he can take action
            notificationService.notifyCustomer(
                id = invoice.customerId,
                message = "Subscription suspended because of currency mismatch. " +
                          "Change currency to '${invoice.amount.currency}' or wait for automatic retry tomorrow."
            )
        }
    }

    private fun getPendingInvoices(): List<Invoice> {
        return invoiceService.fetch(InvoiceStatus.PENDING)
    }

    private fun getPendingInvoicesWithRetry(): List<Invoice> {
        return invoiceService.fetch(InvoiceStatus.PENDING, 1)
    }
}
