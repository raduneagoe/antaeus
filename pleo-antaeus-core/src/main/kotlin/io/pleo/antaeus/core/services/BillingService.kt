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
        private val notificationService: NotificationService,
        private val maxRetry: Int
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Schedule task to run every day. The task checks if it's the 1st of the month to settle all pending invoices.
     * The task also checks every day if there are any pending invoices that failed in the past and retries again
     * if they didn't reach max retry number.
     *
     * @param dayOfMonth
     */
    fun schedulePaymentOfPendingInvoices(dayOfMonth: Int) {
        // TODO replace with https://github.com/shyiko/skedule library for more accurate time scheduling tasks
        Timer().scheduleAtFixedRate(timerTask {
            if (LocalDateTime.now().dayOfMonth == dayOfMonth) {
                settleInvoices(getPendingInvoices(), Int.MAX_VALUE)
            }

            // Everyday retry pending invoices that have 1 <= retry <= maxRetry
            settleInvoices(getPendingInvoicesWithRetry(), maxRetry)
        }, 0, TimeUnit.DAYS.toMillis(1))
    }


    protected fun settleInvoices(invoices: List<Invoice>, maxRetry: Int) {
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
    protected fun settleInvoice(invoice: Invoice, maxRetry: Int) {
        if (invoice.status == InvoiceStatus.PAID) {
            logger.info {
                String.format("Customer '%d' already charged for invoice '%d'", invoice.customerId, invoice.id)
            }
            return
        } else if (invoice.retry > maxRetry) {
            logger.info {
                String.format("Failed too many times to charge invoice '%d'", invoice.customerId, invoice.id)
            }
            return
        }

        try {
            if (paymentProvider.charge(invoice)) {
                logger.info {
                    String.format("Customer '%d' successfully charged for invoice '%d'", invoice.customerId, invoice.id)
                }

                invoiceService.updateStatus(invoice.id, InvoiceStatus.PAID)
                customerService.updateHasSubscription(invoice.customerId, true)
            } else {
                logger.info {
                    String.format(
                        "Customer '%d' has insufficient funds for invoice '%d'. Notify customer and remove subscription.",
                        invoice.customerId,
                        invoice.id
                    )
                }

                // Reset retry number because invoice can only be settled by the customer adding more funds to his account
                // Nothing to do on backend side
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
            logger.info {
                String.format("Customer '%d' not found to pay invoice '%d'", invoice.customerId, invoice.id)
            }
        } catch (e: CurrencyMismatchException) {
            convertCurrencyForInvoiceAndTryAgain(invoice, maxRetry)
        } catch (e: NetworkException) {
            logger.error {
                String.format(
                    "NetworkException: %s.\n"
                    + "Remove subscription for customer '%d' and increase retry number for invoice '%d'",
                    e.message,
                    invoice.customerId,
                    invoice.id
                )
            }

            // Increment retry number so that invoice can be retried again by schedulePaymentOfPendingInvoices
            invoiceService.incrementRetry(invoice.id)
            // Remove subscription
            customerService.updateHasSubscription(invoice.customerId, false)
            // Notify customer about error so that he can take action
            notificationService.notifyCustomer(
                id = invoice.customerId,
                message = "Subscription suspended because of internal server error! Will try again tomorrow."
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
            logger.info {
                String.format("Customer '%d' has different currency than invoice '%d'", invoice.customerId, invoice.id)
            }

            // Convert amount to customer currency
            val customer = customerService.fetch(invoice.customerId)
            val convertedAmount = converterService.convertCurrency(
                invoice.amount.value,
                invoice.amount.currency,
                customer.currency
            )
            val convertedInvoice = invoice.copy(amount = Money(convertedAmount, customer.currency))

            // Try again with new invoice containing converted amount
            settleInvoice(convertedInvoice, maxRetry)
        } catch (e: NetworkException) {
            logger.error {
                String.format(
                    "CurrencyConverterService error: %s.\nRemove subscription for customer '%d'",
                    e.message,
                    invoice.customerId
                )
            }

            // Increment retry number so that invoice can be retried again by schedulePaymentOfPendingInvoices
            invoiceService.incrementRetry(invoice.id)
            // Remove subscription
            customerService.updateHasSubscription(invoice.customerId, false)
            // Notify customer about error so that he can take action
            notificationService.notifyCustomer(
                id = invoice.customerId,
                message = String.format(
                    "Subscription suspended because of currency mismatch. Invoice currency is '%s'",
                    invoice.amount.currency
                )
            )
        }
    }

    protected fun getPendingInvoices(): List<Invoice> {
        return invoiceService.fetch(InvoiceStatus.PENDING)
    }

    protected fun getPendingInvoicesWithRetry(): List<Invoice> {
        return invoiceService.fetch(InvoiceStatus.PENDING, 1)
    }
}
