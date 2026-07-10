package com.example.services

import android.content.Context
import android.content.SharedPreferences
import com.example.utils.Company

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ceyvana_settings", Context.MODE_PRIVATE)

    var companyName: String
        get() = prefs.getString("company_name", Company.NAME) ?: Company.NAME
        set(value) = prefs.edit().putString("company_name", value).apply()

    var companyOwner: String
        get() = prefs.getString("company_owner", Company.OWNER) ?: Company.OWNER
        set(value) = prefs.edit().putString("company_owner", value).apply()

    var companyEmail: String
        get() = prefs.getString("company_email", Company.EMAIL) ?: Company.EMAIL
        set(value) = prefs.edit().putString("company_email", value).apply()

    var companyPhone: String
        get() = prefs.getString("company_phone", Company.PHONE1) ?: Company.PHONE1
        set(value) = prefs.edit().putString("company_phone", value).apply()

    var companyPhone2: String
        get() = prefs.getString("company_phone_2", Company.PHONE2) ?: Company.PHONE2
        set(value) = prefs.edit().putString("company_phone_2", value).apply()

    var companyWhatsapp: String
        get() = prefs.getString("company_whatsapp", Company.WHATSAPP) ?: Company.WHATSAPP
        set(value) = prefs.edit().putString("company_whatsapp", value).apply()

    var companyAddress: String
        get() = prefs.getString("company_address", Company.ADDRESS) ?: Company.ADDRESS
        set(value) = prefs.edit().putString("company_address", value).apply()

    var currencySymbol: String
        get() = prefs.getString("currency_symbol", "Rs.") ?: "Rs."
        set(value) = prefs.edit().putString("currency_symbol", value).apply()

    var googleSheetsId: String
        get() = prefs.getString("google_sheets_id", "1t_gZ2vN_ceyvana_sheet_id_demo") ?: "1t_gZ2vN_ceyvana_sheet_id_demo"
        set(value) = prefs.edit().putString("google_sheets_id", value).apply()

    var googleScriptUrl: String
        get() = prefs.getString("google_script_url", "") ?: ""
        set(value) = prefs.edit().putString("google_script_url", value).apply()

    var isGoogleSheetsEnabled: Boolean
        get() = prefs.getBoolean("gsheets_enabled", true)
        set(value) = prefs.edit().putBoolean("gsheets_enabled", value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean("is_dark_mode", false)
        set(value) = prefs.edit().putBoolean("is_dark_mode", value).apply()

    var companyLogo: String
        get() = prefs.getString("company_logo", "") ?: ""
        set(value) = prefs.edit().putString("company_logo", value).apply()
}
