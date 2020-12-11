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
) {
    private val logger = KotlinLogging.logger {}

    fun schedulePaymentOfPendingInvoices(dayOfMonth: Int) {
        // TODO replace with https://github.com/shyiko/skedule library for more accurate time scheduling tasks
        Timer().scheduleAtFixedRate(timerTask {
            if (LocalDateTime.now().dayOfMonth == dayOfMonth) {
                settleInvoices(getPendingInvoices())
            }
        }, 0, TimeUnit.DAYS.toMillis(1))
    }

    protected fun settleInvoices(invoices: List<Invoice>) {
        for (invoice in invoices) {
            settleInvoice(invoice)
        }
    }

    protected fun settleInvoice(invoice: Invoice) {
        if (invoice.status == InvoiceStatus.PAID) {
            logger.info {
                String.format("Customer '%d' already charged for invoice '%d'", invoice.customerId, invoice.id)
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

                customerService.updateHasSubscription(invoice.customerId, false)

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
            convertCurrencyForInvoiceAndTryAgain(invoice)
        } catch (e: NetworkException) {
            // TODO add retry logic
        }
    }

    private fun convertCurrencyForInvoiceAndTryAgain(invoice: Invoice) {
        try {
            logger.info {
                String.format("Customer '%d' has different currency than invoice '%d'", invoice.customerId, invoice.id)
            }

            val customer = customerService.fetch(invoice.customerId)
            val convertedAmount = converterService.convertCurrency(
                invoice.amount.value,
                invoice.amount.currency,
                customer.currency
            )
            val convertedInvoice = invoice.copy(amount = Money(convertedAmount, customer.currency))

            // Try again with new invoice containing converted amount
            settleInvoice(convertedInvoice)
        } catch (e: NetworkException) {
            logger.error {
                String.format(
                    "CurrencyConverterService error: %s.\nRemove subscription for customer '%d'",
                    e.message,
                    invoice.customerId
                )
            }

            customerService.updateHasSubscription(invoice.customerId, false)

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
}
