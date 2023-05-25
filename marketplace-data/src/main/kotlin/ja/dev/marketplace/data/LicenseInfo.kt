package ja.dev.marketplace.data

import ja.dev.marketplace.client.*
import java.math.BigDecimal
import java.math.BigInteger

typealias LicenseId = String

/**
 * Purchase of a single plugin license, identified by a unique ID.
 */
data class LicenseInfo(
    // unique identifier of the license, there may be multiple LicenseInfo items with the same license ID
    val id: LicenseId,
    // dates, when this license is valid
    val validity: YearMonthDayRange,
    // amount of this particular license
    val amount: Amount,
    // currency of Amount
    val currency: Currency,
    // same as amount, but in USD
    val amountUSD: Amount,
    // the sale of this particular license purchase, which also contains the saleLineItem
    val sale: PluginSale,
    // the sale line item of this particular license purchase
    val saleLineItem: PluginSaleItem,
) : WithDateRange, Comparable<LicenseInfo> {

    override val dateRange: YearMonthDayRange
        get() = validity

    override fun compareTo(other: LicenseInfo): Int {
        return validity.compareTo(other.validity)
    }

    companion object {
        fun create(sales: List<PluginSale>): List<LicenseInfo> {
            return sales.flatMap { sale ->
                val licenses = mutableListOf<LicenseInfo>()

                for (lineItem in sale.lineItems) {
                    val fixedAmount = when (sale.amount.toDouble()) {
                        0.0 -> Amount(BigInteger.ZERO)
                        else -> lineItem.amount
                    }
                    val fixedAmountUSD = when (sale.amountUSD.toDouble()) {
                        0.0 -> Amount(BigInteger.ZERO)
                        else -> lineItem.amountUSD
                    }

                    SplitAmount.split(fixedAmount, fixedAmountUSD, lineItem.licenseIds) { amount, amountUSD, license ->
                        licenses += LicenseInfo(
                            license,
                            lineItem.subscriptionDates,
                            amount,
                            sale.currency,
                            amountUSD,
                            sale,
                            lineItem
                        )
                    }
                }

                if (licenses.sumOf { it.amountUSD }.toDouble() != sale.amountUSD.toDouble()) {
                    println("Sum does not match: $sale. item sum: ${licenses.sumOf { it.amountUSD }.toDouble()}, total: ${sale.amountUSD.toDouble()}")
                }

                licenses.sorted()
            }
        }
    }
}
