/*
 * Copyright (c) 2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.ja.marketplace.services

class Countries(private val countries: List<CountryWithCurrency>) : Iterable<CountryWithCurrency> {
    private val countryNameMapping: Map<String, CountryWithCurrency> by lazy {
        countries.associateBy { it.country.printableName }
    }

    private val countryIsoCodeMapping: Map<String, CountryWithCurrency> by lazy {
        countries.associateBy { it.country.isoCode }
    }

    private val currencyCodeMapping: Map<String, List<CountryWithCurrency>> by lazy {
        countries.groupBy { it.currency.isoCode }
    }

    override fun iterator(): Iterator<CountryWithCurrency> {
        return countries.iterator()
    }

    fun byCountryName(name: String): CountryWithCurrency? {
        return countryNameMapping[name]
    }

    fun byCountryIsoCode(isoCode: String): CountryWithCurrency? {
        return countryIsoCodeMapping[isoCode]
    }

    fun byCurrencyIsoCode(isoCode: String): List<CountryWithCurrency>? {
        return currencyCodeMapping[isoCode]
    }
}