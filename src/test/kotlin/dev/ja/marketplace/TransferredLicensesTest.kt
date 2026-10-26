/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace

import dev.ja.marketplace.client.YearMonthDay
import dev.ja.marketplace.client.currency.MarketplaceCurrencies
import dev.ja.marketplace.client.model.*
import dev.ja.marketplace.data.DataTable
import dev.ja.marketplace.data.DataTableRow
import dev.ja.marketplace.data.LinkedCustomer
import dev.ja.marketplace.data.LinkedLicense
import dev.ja.marketplace.data.RenderedDataTable
import dev.ja.marketplace.data.TransferredLicenseEnd
import dev.ja.marketplace.data.customers.CustomerTable
import dev.ja.marketplace.data.licenses.LicenseStatus
import dev.ja.marketplace.data.licenses.LicenseTable
import dev.ja.marketplace.mcp.McpTestData
import kotlinx.coroutines.runBlocking
import org.javamoney.moneta.FastMoney
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * A license, which another customer renewed, e.g. after a transfer to an organization, isn't churned.
 */
class TransferredLicensesTest {
    private val today = YearMonthDay(2026, 10, 24)

    private val previousOwner = CustomerInfo(1, "Canada", CustomerType.Organization, "Previous owner")
    private val newOwner = CustomerInfo(2, "United States", CustomerType.Organization, "New owner")

    private val sales = listOf(
        // bought by the previous owner, renewed by the new owner
        sale("ref-1", "transferred", previousOwner, YearMonthDay(2025, 3, 31), PluginSaleItemType.New),
        sale("ref-2", "transferred", newOwner, YearMonthDay(2026, 3, 31), PluginSaleItemType.Renew),
        // not renewed
        sale("ref-3", "churned", previousOwner, YearMonthDay(2024, 1, 1), PluginSaleItemType.New),
    )

    private fun sale(ref: String, license: String, customer: CustomerInfo, start: YearMonthDay, type: PluginSaleItemType): PluginSale {
        val amount = FastMoney.of(100, MarketplaceCurrencies.USD)
        val validity = start.rangeTo(start.add(1, 0, -1))
        val lineItem = SubscriptionPluginSaleItem(type, listOf(license), amount, amount, emptyList(), validity)
        return PluginSale(ref, start, amount, amount, LicensePeriod.Annual, customer, null, listOf(lineItem))
    }

    private fun render(table: DataTable): RenderedDataTable = runBlocking {
        val data = McpTestData.pluginData(sales = sales)
        table.init(data)
        data.getSales()!!.forEach { table.process(it) }
        data.getLicenses()!!.forEach { table.process(it) }
        table.renderTable()
    }

    private fun RenderedDataTable.value(row: DataTableRow, columnId: String): Any? {
        return row.getValue(columns.single { it.id == columnId })
    }

    private fun RenderedDataTable.rows(): List<DataTableRow> = sections.single().rows

    private fun RenderedDataTable.licenseIds(): List<String> {
        return rows().map { (value(it, "license-id") as LinkedLicense).id }
    }

    @Test
    fun transferredLicenseIsNotChurned() {
        val churned = render(LicenseTable(statusFilter = LicenseStatus.Churned, showOnlyLatestLicenseInfo = true, nowDate = today))
        assertEquals(listOf("churned"), churned.licenseIds())

        val active = render(LicenseTable(statusFilter = LicenseStatus.Active, showOnlyLatestLicenseInfo = true, nowDate = today))
        assertEquals(listOf("transferred"), active.licenseIds())
        // the latest purchase shows the period of all purchases of its sale
        val row = active.rows().single()
        assertEquals(YearMonthDay(2026, 3, 31), active.value(row, "license-validity"))
        assertEquals(YearMonthDay(2027, 3, 30), active.value(row, "license-validity-end"))
    }

    @Test
    fun rowLimitCountsTheShownLicenses() {
        // the latest purchases are both purchases of the transferred license, the limit applies to the shown licenses
        val table = render(LicenseTable(maxTableRows = 2, showOnlyLatestLicenseInfo = true, nowDate = today))
        assertEquals(listOf("transferred", "churned"), table.licenseIds())
    }

    @Test
    fun customersCountTransferredLicenses() {
        val table = render(CustomerTable(nowDate = today))
        assertTrue(table.columns.any { it.id == "customer-licenses-transferred" })

        // the names link to the customers, the IDs of customers with a name aren't linked
        val rowsByCustomer = table.rows().associateBy { (table.value(it, "customer-id") as LinkedCustomer).id }
        val previous = rowsByCustomer.getValue(previousOwner.code)
        assertEquals(BigInteger.ZERO, table.value(previous, "customer-licenses-active"))
        assertEquals(BigInteger.ONE, table.value(previous, "customer-licenses-churned"))
        assertEquals(BigInteger.ONE, table.value(previous, "customer-licenses-transferred"))
        assertEquals(BigInteger.TWO, table.value(previous, "customer-licenses-total"))

        val new = rowsByCustomer.getValue(newOwner.code)
        assertEquals(BigInteger.ONE, table.value(new, "customer-licenses-active"))
        assertEquals(BigInteger.ZERO, table.value(new, "customer-licenses-transferred"))
    }

    @Test
    fun licensesOfACustomerShowTheTransfer() {
        val table = render(LicenseTable(showDetails = false, nowDate = today) { it.sale.customer.code == previousOwner.code })
        val transferredRow = table.rows().single { (table.value(it, "license-id") as LinkedLicense).id == "transferred" }

        // the license is still valid, the row isn't gray
        assertEquals(null, transferredRow.cssClass)
        val end = table.value(transferredRow, "license-validity-end") as TransferredLicenseEnd
        assertEquals(newOwner.code, end.customer.id)
        assertEquals(YearMonthDay(2027, 3, 30), end.licenseEnd)
    }
}
