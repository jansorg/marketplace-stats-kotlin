package ja.dev.marketplace.data.customerType

import ja.dev.marketplace.client.Amount
import ja.dev.marketplace.client.Currency
import ja.dev.marketplace.client.CustomerType
import ja.dev.marketplace.client.withCurrency
import ja.dev.marketplace.data.*
import java.math.BigDecimal
import java.util.*

class CustomerTypeTable : SimpleDataTable("Customer Type", "customer-type"), MarketplaceDataSink {
    private val columnType = DataTableColumn("customer-type", null, "num")
    private val columnAmount = DataTableColumn("amount", null, "num")
    private val columnPercentage = DataTableColumn("percentage", "% of Sales", "num num-percentage")

    private val data = TreeMap<CustomerType, Amount>()

    override val columns: List<DataTableColumn> = listOf(columnType, columnAmount, columnPercentage)

    override val sections: List<DataTableSection>
        get() {
            val totalAmount = data.values.sumOf { it }
            val rows = data.entries.map { (customerType, amount) ->
                SimpleDateTableRow(
                    mapOf(
                        columnType to customerType,
                        columnAmount to amount.withCurrency(Currency.USD),
                        columnPercentage to PercentageValue.of(amount, totalAmount)
                    )
                )
            }

            return listOf(
                SimpleTableSection(
                    rows, footer = SimpleTableSection(
                        SimpleDateTableRow(
                            columnPercentage to PercentageValue.ONE_HUNDRED,
                            columnAmount to data.values.sumOf { it }.withCurrency(Currency.USD)
                        )
                    )
                )
            )
        }

    override fun process(licenseInfo: LicenseInfo) {
        data.compute(licenseInfo.sale.customer.type) { _, current ->
            (current ?: BigDecimal.ZERO) + licenseInfo.amountUSD
        }
    }
}