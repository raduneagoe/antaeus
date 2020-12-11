/*
    Implements the data access layer (DAL).
    The data access layer generates and executes requests to the database.

    See the `mappings` module for the conversions between database rows and Kotlin objects.
 */

package io.pleo.antaeus.data

import io.pleo.antaeus.models.Currency
import io.pleo.antaeus.models.Customer
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus
import io.pleo.antaeus.models.Money
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class AntaeusDal(private val db: Database) {
    fun fetchInvoice(id: Int): Invoice? {
        // transaction(db) runs the internal query as a new database transaction.
        return transaction(db) {
            // Returns the first invoice with matching id.
            InvoiceTable
                .select { InvoiceTable.id.eq(id) }
                .firstOrNull()
                ?.toInvoice()
        }
    }

    fun fetchInvoices(): List<Invoice> {
        return transaction(db) {
            InvoiceTable
                .selectAll()
                .map { it.toInvoice() }
        }
    }

    /**
     * Fetch all invoices with matching status.
     *
     * @param status the status to be searched by.
     * @return List of invoices.
     */
    fun fetchInvoicesWithStatus(status: InvoiceStatus): List<Invoice> {
        return transaction(db) {
            InvoiceTable
                .select { InvoiceTable.status.eq(status.name) }
                .map { it.toInvoice() }
        }
    }

    /**
     * Fetch all invoices with matching status and at least retry number.
     *
     * @param status the status to be searched by.
     * @param minRetry at least retry number for invoice.
     * @return List of invoices.
     */
    fun fetchInvoicesWithStatusAndRetry(status: InvoiceStatus, minRetry: Int): List<Invoice> {
        return transaction(db) {
            InvoiceTable
                    .select { InvoiceTable.status.eq(status.name) and InvoiceTable.retry.greaterEq(minRetry) }
                    .map { it.toInvoice() }
        }
    }

    /**
     * Increment retry number for invoice with matching id.
     *
     * @param id the id of the invoice.
     */
    fun incrementInvoiceRetry(id: Int) {
        transaction(db) {
            InvoiceTable.update (
                where = { InvoiceTable.id.eq(id) },
                body = {
                    with(SqlExpressionBuilder) {
                        it.update(InvoiceTable.retry, InvoiceTable.retry + 1)
                    }
                }
            )
        }
    }

    /**
     * Put retry number to 0 for invoice with matching id.
     *
     * @param id the id of the invoice.
     */
    fun resetInvoiceRetry(id: Int) {
        transaction(db) {
            InvoiceTable.update (
                where = { InvoiceTable.id.eq(id) },
                body = { it[InvoiceTable.retry] = 0}
            )
        }
    }

    /**
     * Update status of invoice with matching id.
     *
     * @param id the id of the invoice.
     * @param status the status to be set.
     */
    fun updateInvoiceStatus(id: Int, status: InvoiceStatus) {
        transaction(db) {
            InvoiceTable.update (
                where = { InvoiceTable.id.eq(id) },
                body = { it[InvoiceTable.status] = status.name }
            )
        }
    }

    fun createInvoice(amount: Money, customer: Customer, status: InvoiceStatus = InvoiceStatus.PENDING, retry: Int = 0): Invoice? {
        val id = transaction(db) {
            // Insert the invoice and returns its new id.
            InvoiceTable
                .insert {
                    it[this.value] = amount.value
                    it[this.currency] = amount.currency.toString()
                    it[this.status] = status.toString()
                    it[this.customerId] = customer.id
                    it[this.retry] = retry
                } get InvoiceTable.id
        }

        return fetchInvoice(id)
    }

    fun fetchCustomer(id: Int): Customer? {
        return transaction(db) {
            CustomerTable
                .select { CustomerTable.id.eq(id) }
                .firstOrNull()
                ?.toCustomer()
        }
    }

    fun fetchCustomers(): List<Customer> {
        return transaction(db) {
            CustomerTable
                .selectAll()
                .map { it.toCustomer() }
        }
    }

    fun createCustomer(currency: Currency, hasSubscription: Boolean): Customer? {
        val id = transaction(db) {
            // Insert the customer and return its new id.
            CustomerTable.insert {
                it[this.currency] = currency.toString()
                it[this.hasSubscription] = hasSubscription
            } get CustomerTable.id
        }

        return fetchCustomer(id)
    }

    /**
     * Update subscription status of customer with matching id.
     *
     * @param id the id of the customer.
     * @param hasSubscription true for valid subscription, false otherwise.
     */
    fun updateCustomerHasSubscription(id: Int, hasSubscription: Boolean) {
        transaction(db) {
            CustomerTable.update (
                    where = { CustomerTable.id.eq(id) },
                    body = { it[CustomerTable.hasSubscription] = hasSubscription }
            )
        }
    }
}
