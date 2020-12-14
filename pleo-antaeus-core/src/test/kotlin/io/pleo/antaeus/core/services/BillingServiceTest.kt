package io.pleo.antaeus.core.services

import io.mockk.*
import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.data.AntaeusDal
import io.pleo.antaeus.models.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random

class BillingServiceTest {
    private val customer = Customer(1, Currency.EUR, true)

    private val pendingInvoice = Invoice(
        id = 1,
        customerId = customer.id,
        amount = Money(value = BigDecimal(100.0), currency = Currency.EUR),
        status = InvoiceStatus.PENDING,
        retry = 0
    )

    private val dal = mockk<AntaeusDal> {
        every { updateInvoiceStatus(any(), any()) } just Runs
        every { updateCustomerHasSubscription(any(), any()) } just Runs
        every { resetInvoiceRetry(any()) } just Runs
        every { fetchCustomer(any()) } returns customer
        every { incrementInvoiceRetry(any()) } just Runs
    }

    private val customerService = CustomerService(dal = dal)
    private val invoiceService = InvoiceService(dal = dal)

    private val notificationService = mockk<NotificationService> {
        every { notifyCustomer(any(), any()) } just Runs
    }

    private val paymentProvider = mockk<PaymentProvider>()
    private val converterService = mockk<CurrencyConverterService>()

    private val billingService = BillingService(
        paymentProvider = paymentProvider,
        invoiceService = invoiceService,
        customerService = customerService,
        converterService = converterService,
        notificationService = notificationService
    )

    @Test
    fun `successfully charge`() {
        every { paymentProvider.charge(any()) } returns true

        billingService.settleInvoice(pendingInvoice, 0)

        verifyAll {
            dal.updateInvoiceStatus(pendingInvoice.id, InvoiceStatus.PAID)
            dal.updateCustomerHasSubscription(pendingInvoice.customerId, true)
        }
    }

    @Test
    fun `insufficient funds`() {
        every { paymentProvider.charge(any()) } returns false

        billingService.settleInvoice(pendingInvoice, 0)

        verifyAll {
            dal.resetInvoiceRetry(pendingInvoice.id)
            dal.updateCustomerHasSubscription(pendingInvoice.customerId, false)
            notificationService.notifyCustomer(pendingInvoice.customerId, any())
        }
    }

    @Test
    fun `successfully charge on currency mismatch`() {
        every { paymentProvider.charge(any()) } throws CurrencyMismatchException(pendingInvoice.id, pendingInvoice.customerId) andThen true
        every { converterService.convertCurrency(any(), any(), any()) } returns BigDecimal(Random.nextDouble(10.0, 500.0))

        val pendingInvoiceWithOtherCurrency = pendingInvoice.copy(amount = Money(BigDecimal(110), Currency.USD))
        billingService.settleInvoice(pendingInvoiceWithOtherCurrency, 0)

        verifyAll {
            dal.fetchCustomer(pendingInvoiceWithOtherCurrency.customerId)
            converterService.convertCurrency(
                value = pendingInvoiceWithOtherCurrency.amount.value,
                fromCurrency = pendingInvoiceWithOtherCurrency.amount.currency,
                toCurrency = customer.currency
            )
            dal.updateInvoiceStatus(pendingInvoiceWithOtherCurrency.id, InvoiceStatus.PAID)
            dal.updateCustomerHasSubscription(pendingInvoiceWithOtherCurrency.customerId, true)
        }
    }

    @Test
    fun `insufficient funds on currency mismatch`() {
        every { paymentProvider.charge(any()) } throws CurrencyMismatchException(pendingInvoice.id, pendingInvoice.customerId) andThen false
        every { converterService.convertCurrency(any(), any(), any()) } returns BigDecimal(Random.nextDouble(10.0, 500.0))

        val pendingInvoiceWithOtherCurrency = pendingInvoice.copy(amount = Money(BigDecimal(110), Currency.USD))
        billingService.settleInvoice(pendingInvoiceWithOtherCurrency, 0)

        verifyAll {
            dal.fetchCustomer(pendingInvoiceWithOtherCurrency.customerId)
            converterService.convertCurrency(
                    value = pendingInvoiceWithOtherCurrency.amount.value,
                    fromCurrency = pendingInvoiceWithOtherCurrency.amount.currency,
                    toCurrency = customer.currency
            )
            dal.resetInvoiceRetry(pendingInvoice.id)
            dal.updateCustomerHasSubscription(pendingInvoice.customerId, false)
            notificationService.notifyCustomer(pendingInvoice.customerId, any())
        }
    }

    @Test
    fun `converterService throws network exception on currency mismatch`() {
        every { paymentProvider.charge(any()) } throws CurrencyMismatchException(pendingInvoice.id, pendingInvoice.customerId)
        every { converterService.convertCurrency(any(), any(), any()) } throws NetworkException()

        val pendingInvoiceWithOtherCurrency = pendingInvoice.copy(amount = Money(BigDecimal(110), Currency.USD))
        billingService.settleInvoice(pendingInvoiceWithOtherCurrency, 0)

        verifyAll {
            dal.fetchCustomer(pendingInvoiceWithOtherCurrency.customerId)
            converterService.convertCurrency(
                    value = pendingInvoiceWithOtherCurrency.amount.value,
                    fromCurrency = pendingInvoiceWithOtherCurrency.amount.currency,
                    toCurrency = customer.currency
            )
            dal.incrementInvoiceRetry(pendingInvoiceWithOtherCurrency.id)
            dal.updateCustomerHasSubscription(pendingInvoiceWithOtherCurrency.customerId, false)
            notificationService.notifyCustomer(pendingInvoiceWithOtherCurrency.customerId, any())
        }
    }

    @Test
    fun `network exception`() {
        every { paymentProvider.charge(any()) } throws NetworkException()

        billingService.settleInvoice(pendingInvoice, 0)

        verifyAll {
            dal.incrementInvoiceRetry(pendingInvoice.id)
            dal.updateCustomerHasSubscription(pendingInvoice.customerId, false)
            notificationService.notifyCustomer(pendingInvoice.customerId, any())
        }
    }

    @Test
    fun `invoice already payed skips paymentProvider`() {
        val paidInvoice = pendingInvoice.copy(status = InvoiceStatus.PAID)
        billingService.settleInvoice(paidInvoice, 0)


        verify { paymentProvider wasNot Called }
    }

    @Test
    fun `too many retries skips paymentProvider`() {
        val tooManyRetriesInvoice = pendingInvoice.copy(retry = 4)
        billingService.settleInvoice(tooManyRetriesInvoice, tooManyRetriesInvoice.retry - 1)


        verify { paymentProvider wasNot Called }
    }
}
