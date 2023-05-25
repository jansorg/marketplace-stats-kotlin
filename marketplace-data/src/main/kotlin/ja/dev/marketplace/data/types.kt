package ja.dev.marketplace.data

import java.math.BigDecimal
import java.math.RoundingMode

data class PercentageValue(val value: BigDecimal) {
    companion object {
        val ONE_HUNDRED = PercentageValue(BigDecimal(100.0))

        fun of(first: BigDecimal, second: BigDecimal): PercentageValue {
            return PercentageValue(first.divide(second, 10, RoundingMode.HALF_UP) * BigDecimal(100.0))
        }
    }
}

fun Double.asPercentageValue(multiply: Boolean = true): PercentageValue? {
    return when {
        this.isNaN() -> null
        multiply -> PercentageValue(BigDecimal.valueOf(this * 100.0))
        else -> PercentageValue(BigDecimal.valueOf(this))
    }
}