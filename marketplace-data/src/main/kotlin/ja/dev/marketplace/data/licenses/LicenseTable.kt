package ja.dev.marketplace.data.licenses

import ja.dev.marketplace.client.Currency
import ja.dev.marketplace.client.withCurrency
import ja.dev.marketplace.data.*

class LicenseTable : SimpleDataTable("Licenses", "licenses", "section-wide"), MarketplaceDataSink {
    private val columnLicenseId = DataTableColumn("license-id", "License ID")
    private val columnPurchaseDate = DataTableColumn("sale-date", "Purchase", "date")
    private val columnValidityStart = DataTableColumn("license-validity", "License Start", "date")
    private val columnValidityEnd = DataTableColumn("license-validity", "End", "date")
    private val columnCustomerName = DataTableColumn("customer", "Name", cssStyle = "max-width: 35%")
    private val columnCustomerId = DataTableColumn("customer-id", "Cust. ID", "num")
    private val columnAmountUSD = DataTableColumn("sale-amount-usd", "Amount", "num")
    private val columnLicenseType = DataTableColumn("license-type", "License")
    private val columnLicenseRenewalType = DataTableColumn("license-type", "Type")

    private val data = mutableListOf<LicenseInfo>()

    override val columns: List<DataTableColumn> = listOf(
        columnPurchaseDate,
        columnValidityStart,
        columnValidityEnd,
        columnCustomerName,
        columnCustomerId,
        columnAmountUSD,
        columnLicenseType,
        columnLicenseRenewalType,
        columnLicenseId,
    )

    override val sections: List<DataTableSection>
        get() {
            val rows = data.map { license ->
                SimpleDateTableRow(
                    columnLicenseId to license.id,
                    columnPurchaseDate to license.sale.date,
                    columnValidityStart to license.validity.start,
                    columnValidityEnd to license.validity.end,
                    columnCustomerName to license.sale.customer.name,
                    columnCustomerId to license.sale.customer.code,
                    columnAmountUSD to license.amountUSD.withCurrency(Currency.USD),
                    columnLicenseType to license.sale.licensePeriod,
                    columnLicenseRenewalType to license.saleLineItem.type,
                )
            }
            return listOf(SimpleTableSection(rows, null))
        }

    override fun process(licenseInfo: LicenseInfo) {
        data += licenseInfo
    }
}
