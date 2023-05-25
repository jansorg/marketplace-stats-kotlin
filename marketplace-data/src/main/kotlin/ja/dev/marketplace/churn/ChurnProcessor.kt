package ja.dev.marketplace.churn

import ja.dev.marketplace.client.YearMonthDayRange

interface ChurnProcessor<ID, T> {
    fun init()

    fun processValue(id: ID, value: T, validity: YearMonthDayRange, isAcceptedValue: Boolean)

    fun getResult(): ChurnResult<T>
}