package ja.dev.marketplace.data.topCountries

import ja.dev.marketplace.client.Amount
import ja.dev.marketplace.client.Country
import ja.dev.marketplace.client.Currency
import ja.dev.marketplace.client.withCurrency
import ja.dev.marketplace.data.*
import java.math.BigDecimal
import java.util.*

class TopCountriesTable(private val maxItems: Int = 10) : SimpleDataTable("Top Countries", "top-countries"),
    MarketplaceDataSink {
    private val columnCountry = DataTableColumn("country", null)
    private val columnSales = DataTableColumn("sales", null, "num")
    private val columnSalesPercentage = DataTableColumn("sales", "% of Sales", "num num-percentage")

    private val data = TreeMap<Country, Amount>()

    override val columns: List<DataTableColumn> = listOf(columnCountry, columnSales, columnSalesPercentage)

    override fun process(licenseInfo: LicenseInfo) {
        data.compute(licenseInfo.sale.customer.country) { _, amount ->
            (amount ?: BigDecimal.ZERO) + licenseInfo.amountUSD
        }
    }

    override val sections: List<DataTableSection>
        get() {
            val totalAmount = data.values.sumOf { it }
            val rows = data.entries
                .sortedByDescending { it.value }
                .take(maxItems)
                .map { (country, amount) ->
                    SimpleDateTableRow(
                        columnCountry to country,
                        columnSales to amount.withCurrency(Currency.USD),
                        columnSalesPercentage to PercentageValue.of(amount, totalAmount)
                    )
                }

            return listOf(
                SimpleTableSection(
                    rows, footer = SimpleTableSection(
                        SimpleDateTableRow(
                            columnCountry to "${data.size} countries",
                            columnSalesPercentage to PercentageValue(BigDecimal(100.0))
                        )
                    )
                )
            )
        }
}