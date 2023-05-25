package ja.dev.marketplace.client

import kotlinx.datetime.TimeZone

object Marketplace {
    val ServerTimeZone = TimeZone.of("Europe/Berlin")
    val FeeChangeTimestamp = YearMonthDay(2020, 7, 1)
    val Birthday = YearMonthDay(2019, 6, 25)
    val LifetimeRange = Birthday.rangeTo(YearMonthDay.now())

    fun feeAmount(date: YearMonthDay, amount: Amount): Amount {
        return when {
            date < FeeChangeTimestamp -> amount * 0.05.toBigDecimal()
            else -> amount * 0.15.toBigDecimal()
        }
    }
}