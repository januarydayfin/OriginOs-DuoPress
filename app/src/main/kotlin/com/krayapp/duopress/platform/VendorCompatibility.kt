package com.krayapp.duopress.platform

/** iQOO может указываться как бренд, а не как производитель. */
internal fun isSupportedVendor(manufacturer: String, brand: String): Boolean =
    isVivoOrIqoo(manufacturer) || isVivoOrIqoo(brand)

private fun isVivoOrIqoo(value: String): Boolean =
    value.equals("vivo", ignoreCase = true) || value.equals("iqoo", ignoreCase = true)
