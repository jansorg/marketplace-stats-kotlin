package ja.dev.marketplace.data.yearSummary

import ja.dev.marketplace.client.*
import ja.dev.marketplace.client.Currency
import ja.dev.marketplace.data.*
import java.util.*

class YearlySummaryTable : SimpleDataTable("Years", "years", "section-wide"), MarketplaceDataSink {
    private val data = TreeMap<Int, YearSummary>()

    private data class YearSummary(
        val salesTotal: Amount = Amount(0),
        val sales: Amount = Amount(0),
        val fees: Amount = Amount(0),
        val paid: Amount = Amount(0),
        val downloads: Int = 0,
    )

    override fun process(sale: PluginSale) {
        data.compute(sale.date.year) { _, value ->
            val previous = value ?: YearSummary()
            previous.copy(
                salesTotal = previous.salesTotal + sale.amountUSD,
            )
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        data.compute(licenseInfo.sale.date.year) { _, value ->
            val totalAmount = licenseInfo.amountUSD
            val feeAmount = Marketplace.feeAmount(licenseInfo.sale.date, totalAmount)
            val paidAmount = totalAmount - feeAmount

            val previous = value ?: YearSummary()
            previous.copy(
                sales = previous.sales + totalAmount,
                fees = previous.fees + feeAmount,
                paid = previous.paid + paidAmount,
            )
        }
    }

    private val columnYear = DataTableColumn("year", null)
    private val columnSalesTotal = DataTableColumn("salesTotal", "Sales (OLD)", "num")
    private val columnSales = DataTableColumn("sales", "Sales", "num")
    private val columnFees = DataTableColumn("fees", "Fees", "num")
    private val columnPaid = DataTableColumn("paid", "Paid Out", "num")

    override val columns: List<DataTableColumn> = listOf(
        columnYear,
        columnSalesTotal,
        columnSales,
        columnFees,
        columnPaid,
    )

    override val sections: List<DataTableSection>
        get() {
            val rows = data.entries.map { (year, value) ->
                SimpleDateTableRow(
                    columnYear to year,
                    columnSalesTotal to value.salesTotal.withCurrency(Currency.USD),
                    columnSales to value.sales.withCurrency(Currency.USD),
                    columnFees to value.fees.withCurrency(Currency.USD),
                    columnPaid to value.paid.withCurrency(Currency.USD)
                )
            }

            return listOf(
                SimpleTableSection(
                    rows, footer = SimpleTableSection(
                        SimpleDateTableRow(
                            columnSalesTotal to data.values.sumOf(YearSummary::salesTotal).withCurrency(Currency.USD),
                            columnSales to data.values.sumOf(YearSummary::sales).withCurrency(Currency.USD),
                            columnFees to data.values.sumOf(YearSummary::fees).withCurrency(Currency.USD),
                            columnPaid to data.values.sumOf(YearSummary::paid).withCurrency(Currency.USD),
                        )
                    )
                )
            )
        }
}