package ja.dev.marketplace.churn

import ja.dev.marketplace.client.YearMonthDay
import ja.dev.marketplace.client.YearMonthDayRange

/**
 * Churn rate is calculated as "number of users lost in the date range / users at beginning of the date range".
 */
class SimpleChurnProcessor<T>(
    private val previouslyActiveMarkerDate: YearMonthDay,
    private val currentlyActiveMarkerDate: YearMonthDay,
) : ChurnProcessor<Int, T> {
    private val previousPeriodItems = mutableSetOf<Int>()
    private val activeItems = mutableSetOf<Int>()
    private val activeItemsUnaccepted = mutableSetOf<Int>()

    override fun init() {}

    override fun processValue(id: Int, value: T, validity: YearMonthDayRange, isAcceptedValue: Boolean) {
        if (isAcceptedValue && previouslyActiveMarkerDate in validity) {
            previousPeriodItems += id
        }

        // valid before end, valid until end or later
        if (currentlyActiveMarkerDate in validity) {
            if (isAcceptedValue) {
                activeItems += id
            } else {
                activeItemsUnaccepted += id
            }
        }
    }

    override fun getResult(): ChurnResult<T> {
        val activeAtStart = previousPeriodItems.size
        val churned = previousPeriodItems.count { it !in activeItems && it !in activeItemsUnaccepted }
        val churnRate = when (activeAtStart) {
            0 -> 0.0
            else -> churned.toDouble() / activeAtStart.toDouble()
        }

        return ChurnResult(churnRate, activeAtStart, activeItems.size, churned)
    }
}