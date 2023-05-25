package ja.dev.marketplace.data.customers

import ja.dev.marketplace.client.*
import ja.dev.marketplace.client.LicenseId
import ja.dev.marketplace.data.*

class CustomerTable(val licenseFilter: (LicenseInfo) -> Boolean) :
    SimpleDataTable("Customers", cssClass = "section-wide"), MarketplaceDataSink {
    private val customers = mutableSetOf<CustomerInfo>()
    private val latestLicenseValid = mutableMapOf<CustomerInfo, YearMonthDay>()
    private val customerSales = mutableMapOf<CustomerInfo, Amount>()
    private val activeLicenses = mutableMapOf<CustomerInfo, MutableSet<LicenseId>>()

    private val columnValidUntil = DataTableColumn("customer-type", "Valid Until")
    private val columnName = DataTableColumn("customer-name", "Name")
    private val columnCountry = DataTableColumn("customer-country", "Country")
    private val columnType = DataTableColumn("customer-type", "Type")
    private val columnSales = DataTableColumn("customer-type", "Total Sales", "num")
    private val columnActiveLicenses = DataTableColumn("customer-licenses", "Active Licenses", "num")
    private val columnId = DataTableColumn("customer-id", "Cust. ID", "num")

    override val columns: List<DataTableColumn> = listOf(
        columnValidUntil, columnName, columnCountry, columnType, columnSales, columnActiveLicenses, columnId
    )

    override fun process(licenseInfo: LicenseInfo) {
        val customer = licenseInfo.sale.customer
        customerSales.merge(customer, licenseInfo.amountUSD) { a, b -> a + b }
        latestLicenseValid.merge(customer, licenseInfo.validity.end, ::maxOf)
        activeLicenses.computeIfAbsent(customer) { mutableSetOf() } += licenseInfo.id

        if (licenseFilter(licenseInfo)) {
            customers += customer
        }
    }

    override val sections: List<DataTableSection>
        get() {
            val sortedByValidity = customers.sortedBy { latestLicenseValid[it]!! }
            val now = YearMonthDay.now()
            val rows = sortedByValidity.map { customer ->
                val latestValid = latestLicenseValid[customer]!!
                val cssClass: String? = when {
                    latestValid < now -> "churned"
                    else -> null
                }
                SimpleDateTableRow(
                    mapOf(
                        columnId to customer.code,
                        columnName to customer.name,
                        columnCountry to customer.country,
                        columnType to customer.type,
                        columnSales to customerSales[customer]!!.withCurrency(Currency.USD),
                        columnActiveLicenses to activeLicenses[customer]!!.size,
                        columnValidUntil to latestValid
                    ),
                    cssClass = cssClass
                )
            }
            val footer = SimpleRowGroup(SimpleDateTableRow(columnName to "${sortedByValidity.size} customers"))
            return listOf(SimpleTableSection(rows, footer = footer))
        }
}
