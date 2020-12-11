/*
    Implements endpoints related to invoices.
 */

package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.InvoiceNotFoundException
import io.pleo.antaeus.data.AntaeusDal
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus

class InvoiceService(private val dal: AntaeusDal) {
    fun fetchAll(): List<Invoice> {
        return dal.fetchInvoices()
    }

    fun fetch(id: Int): Invoice {
        return dal.fetchInvoice(id) ?: throw InvoiceNotFoundException(id)
    }

    fun fetch(status: InvoiceStatus): List<Invoice> {
        return dal.fetchInvoicesWithStatus(status)
    }

    fun fetch(status: InvoiceStatus, minRetry: Int): List<Invoice> {
        return dal.fetchInvoicesWithStatusAndRetry(status, minRetry)
    }

    fun incrementRetry(id: Int) {
        dal.incrementInvoiceRetry(id)
    }

    fun updateStatus(id: Int, status: InvoiceStatus) {
        dal.updateInvoiceStatus(id, status)
    }
}
