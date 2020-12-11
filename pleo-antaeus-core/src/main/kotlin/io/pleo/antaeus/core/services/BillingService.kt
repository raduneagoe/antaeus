package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus
import mu.KotlinLogging
import java.time.LocalDateTime
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timerTask

open class BillingService(
        private val paymentProvider: PaymentProvider,
        private val invoiceService: InvoiceService,
        private val customerService: CustomerService
) {
    private val logger = KotlinLogging.logger {}

    fun schedulePaymentOfPendingInvoices() {
        // TODO replace with https://github.com/shyiko/skedule library for more accurate time scheduling tasks
        Timer().scheduleAtFixedRate(timerTask {
            if (LocalDateTime.now().dayOfMonth == 1) {
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
                String.format("Customer '%d' already charged for invoice '%d'",
                        invoice.customerId,
                        invoice.id)
            }

            return
        }

        try {
            if (paymentProvider.charge(invoice)) {
                logger.info {
                    String.format("Customer '%d' successfully charged for invoice '%d'",
                            invoice.customerId,
                            invoice.id)
                }

                invoiceService.updateStatus(invoice.id, InvoiceStatus.PAID)
                customerService.updateHasSubscription(invoice.customerId, true)
            } else {
                logger.info {
                    String.format("Customer '%d' has insufficient funds for invoice '%d'",
                            invoice.customerId,
                            invoice.id)
                }

                logger.info { "Notify customer and remove subscription" }

                customerService.updateHasSubscription(invoice.customerId, false)

                // TODO notify customer to fix the issue through NotificationService
            }
        } catch (e: CustomerNotFoundException) {
            logger.info {
                String.format("Not found customer '%d' to pay invoice with id '%d'",
                        invoice.customerId,
                        invoice.id)
            }
        } catch (e: CurrencyMismatchException) {
            // TODO add CurrencyConverterService or notify customer to fix the issue through NotificationService
        } catch (e: NetworkException) {
            // TODO add retry logic
        }
    }

    protected fun getPendingInvoices(): List<Invoice> {
        return invoiceService.fetch(InvoiceStatus.PENDING)
    }
}
