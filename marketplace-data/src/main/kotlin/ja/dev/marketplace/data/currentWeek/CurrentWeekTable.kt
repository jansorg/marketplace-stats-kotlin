package ja.dev.marketplace.data.currentWeek

import ja.dev.marketplace.client.*
import ja.dev.marketplace.client.Currency
import ja.dev.marketplace.data.*
import java.math.BigDecimal
import java.util.*

class CurrentWeekTable : SimpleDataTable("This week", cssClass = "small table-striped"), MarketplaceDataSink {
    private val columnDay = DataTableColumn("day", null)
    private val columnTotal = DataTableColumn("total", "Sales", "num")

    private val dateRange = YearMonthDayRange.currentWeek()
    private val data = TreeMap<YearMonthDay, Amount>()

    override val columns: List<DataTableColumn> = listOf(columnDay, columnTotal)

    override fun init() {
        dateRange.days().forEach {
            data[it] = BigDecimal.ZERO
        }
    }

    override fun process(licenseInfo: LicenseInfo) {
        if (licenseInfo.sale.date in dateRange) {
            data.compute(licenseInfo.sale.date) { _, current ->
                (current ?: BigDecimal.ZERO) + licenseInfo.amountUSD
            }
        }
    }

    override val sections: List<DataTableSection>
        get() {
            val now = YearMonthDay.now()
            val rows = data.entries.map { (date, totalAmount) ->
                object : DataTableRow {
                    override val cssClass: String?
                        get() {
                            return when {
                                date == now -> "today"
                                date > now -> "future"
                                else -> null
                            }
                        }

                    override fun getValue(column: DataTableColumn): Any? {
                        return when (column) {
                            columnDay -> date
                            columnTotal -> totalAmount.withCurrency(Currency.USD)
                            else -> null
                        }
                    }
                }
            }

            return listOf(
                SimpleTableSection(
                    rows, footer = SimpleTableSection(
                        SimpleDateTableRow(columnTotal to data.values.sumOf { it }.withCurrency(Currency.USD))
                    )
                )
            )
        }
}