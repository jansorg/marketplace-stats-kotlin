package ja.dev.marketplace.data

import ja.dev.marketplace.client.Amount
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Split an amount into parts without errors by rounding.
 */
object SplitAmount {
    fun <T> split(
        total: Amount,
        totalUSD: Amount,
        items: List<T>,
        block: (amount: Amount, amountUSD: Amount, item: T) -> Unit
    ) {
        val size = items.size
        val itemCount = size.toBigDecimal()

        if (size == 0) {
            return
        }
        if (size == 1) {
            block(total, totalUSD, items[0])
            return
        }

        val itemAmount = (total / itemCount).setScale(2, RoundingMode.DOWN).setScale(10)
        val itemAmountLast = total - itemAmount * (itemCount - BigDecimal.ONE).setScale(10)

        val itemAmountUSD = (totalUSD / itemCount).setScale(2, RoundingMode.DOWN).setScale(10)
        val itemAmountUSDLast = totalUSD - itemAmountUSD * (itemCount - BigDecimal.ONE).setScale(10)

        items.forEachIndexed { index, item ->
            when (index) {
                size - 1 -> block(itemAmountLast, itemAmountUSDLast, item)
                else -> block(itemAmount, itemAmountUSD, item)
            }
        }
    }
}