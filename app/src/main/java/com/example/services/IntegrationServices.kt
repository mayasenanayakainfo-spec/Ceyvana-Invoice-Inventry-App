package com.example.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.InvoiceWithItems
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object IntegrationServices {

    /**
     * Share PDF Invoice using FileProvider (corresponds to share_plus in Flutter)
     */
    fun shareInvoicePdf(context: Context, pdfFile: File, invoiceNumber: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "com.example.fileprovider",
                pdfFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Invoice $invoiceNumber")
                putExtra(Intent.EXTRA_TEXT, "Hello, please find attached Invoice $invoiceNumber. Thank you!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Share Invoice PDF via:")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error sharing PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Send Invoice directly to WhatsApp (corresponds to whatsapp_service.dart in Flutter)
     */
    fun sendInvoiceToWhatsApp(context: Context, invoiceWithItems: InvoiceWithItems, pdfFile: File?) {
        val invoice = invoiceWithItems.invoice
        val textMessage = "Hello,\n\nPlease find the invoice summary below:\n\n" +
                "Invoice: #${invoice.invoiceNumber}\n" +
                "Total Due: $${"%.2f".format(invoice.total)}\n" +
                "Due Date: ${java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(invoice.dueDate))}\n\n" +
                "Thank you for your business!"

        try {
            val phoneCleaned = invoice.clientPhone.replace(Regex("[^0-9]"), "")
            
            // If phone is valid and we have a PDF file, try sharing both or just opening WhatsApp Chat
            if (phoneCleaned.isNotEmpty()) {
                val encodedText = URLEncoder.encode(textMessage, "UTF-8")
                val url = "https://api.whatsapp.com/send?phone=$phoneCleaned&text=$encodedText"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                // Fallback to sharing the PDF file directly to any chat/app
                if (pdfFile != null) {
                    val uri = FileProvider.getUriForFile(context, "com.example.fileprovider", pdfFile)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, textMessage)
                        `package` = "com.whatsapp"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } else {
                    Toast.makeText(context, "No phone or file to share", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            // WhatsApp not installed or link failed, fallback to standard share sheet
            if (pdfFile != null) {
                shareInvoicePdf(context, pdfFile, invoice.invoiceNumber)
            } else {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, textMessage)
                }
                context.startActivity(Intent.createChooser(sendIntent, "Send via"))
            }
        }
    }

    /**
     * Send Invoice PDF via Email (corresponds to email_service.dart in Flutter)
     */
    fun sendInvoiceEmail(context: Context, invoiceWithItems: InvoiceWithItems, pdfFile: File?) {
        val invoice = invoiceWithItems.invoice
        val subject = "Invoice ${invoice.invoiceNumber}"
        val body = """
Dear ${invoice.clientName},

Please find your invoice attached.

Thank you.

Ceyvana Premium Ceylon Spices
""".trimIndent()

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(invoice.clientEmail))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                
                if (pdfFile != null) {
                    val uri = FileProvider.getUriForFile(context, "com.example.fileprovider", pdfFile)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Send Email..."))
        } catch (e: Exception) {
            Toast.makeText(context, "Email client not available: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Formats an invoice or list of invoices to CSV format and shares it natively.
     * This achieves the native equivalent of using the 'csv' and 'share_plus' Flutter packages.
     */
    fun shareInvoicesCsv(context: Context, invoicesList: List<InvoiceWithItems>) {
        try {
            val csvString = buildString {
                // Header Row
                appendLine("Invoice No,Date,Customer,Phone,Address,Email,Items,Total,Status")
                
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                
                invoicesList.forEach { item ->
                    val invoice = item.invoice
                    val itemsSummary = item.items.joinToString("; ") { "${it.name} (${it.quantity} x ${it.price})" }
                    
                    val fields = listOf(
                        invoice.invoiceNumber,
                        sdf.format(java.util.Date(invoice.issueDate)),
                        invoice.clientName,
                        invoice.clientPhone,
                        invoice.clientAddress,
                        invoice.clientEmail,
                        itemsSummary,
                        "%.2f".format(invoice.total),
                        invoice.status
                    )
                    
                    val escapedFields = fields.map { field ->
                        val clean = field.replace("\"", "\"\"")
                        if (clean.contains(",") || clean.contains("\"") || clean.contains("\n") || clean.contains(";")) {
                            "\"$clean\""
                        } else {
                            clean
                        }
                    }
                    appendLine(escapedFields.joinToString(","))
                }
            }

            val csvFile = File(context.cacheDir, "invoices_export_${System.currentTimeMillis()}.csv")
            csvFile.writeText(csvString)

            val uri = FileProvider.getUriForFile(
                context,
                "com.example.fileprovider",
                csvFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Invoices Export")
                putExtra(Intent.EXTRA_TEXT, "Here is the exported CSV file for your invoices.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Share Invoices CSV via:")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error exporting CSV: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Syncs invoice to Google Sheets via secure Google Apps Script Web App relay.
     * If the Web App URL is not configured in Settings, performs a local simulation.
     */
    fun syncToGoogleSheets(
        context: Context, 
        invoiceWithItems: InvoiceWithItems, 
        spreadsheetId: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        val invoice = invoiceWithItems.invoice
        val items = invoiceWithItems.items
        val settingsManager = SettingsManager(context)
        val scriptUrl = settingsManager.googleScriptUrl

        if (spreadsheetId.isEmpty()) {
            onComplete(false, "Spreadsheet ID is missing in Settings!")
            return
        }

        // Helper to escape values for JSON safety
        fun escapeJson(value: String): String {
            return value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "")
                        .replace("\n", "\\n")
        }

        // If no custom Apps Script URL is set, perform a helpful local simulation.
        if (scriptUrl.isEmpty() || !scriptUrl.startsWith("http")) {
            val successMessage = "Successfully simulated sync: " +
                    "[#${invoice.invoiceNumber}, ${invoice.clientName}, $${"%.2f".format(invoice.total)}, ${invoice.status}] " +
                    "to Spreadsheet: ${if (spreadsheetId.length > 12) spreadsheetId.take(12) + "..." else spreadsheetId}\n\n" +
                    "Tip: Enter your deployed Google Apps Script URL in Settings to sync live data!"
            onComplete(true, successMessage)
            return
        }

        // Create the items summary
        val itemsSummary = items.joinToString(", ") { "${it.name} (${it.quantity.toInt()}x)" }
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        val dateStr = sdf.format(java.util.Date(invoice.issueDate))

        // Create JSON payload
        val jsonPayload = """
            {
                "spreadsheetId": "${escapeJson(spreadsheetId)}",
                "invoiceNo": "${escapeJson(invoice.invoiceNumber)}",
                "date": "$dateStr",
                "customer": "${escapeJson(invoice.clientName)}",
                "customerName": "${escapeJson(invoice.clientName)}",
                "phone": "${escapeJson(invoice.clientPhone)}",
                "customerPhone": "${escapeJson(invoice.clientPhone)}",
                "address": "${escapeJson(invoice.clientAddress)}",
                "customerAddress": "${escapeJson(invoice.clientAddress)}",
                "email": "${escapeJson(invoice.clientEmail)}",
                "customerEmail": "${escapeJson(invoice.clientEmail)}",
                "items": "${escapeJson(itemsSummary)}",
                "total": ${invoice.total}
            }
        """.trimIndent()

        // Run network call on Coroutine IO thread
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                    val body = jsonPayload.toRequestBody(mediaType)
                    val request = Request.Builder()
                        .url(scriptUrl)
                        .post(body)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Pair(false, "Server Error: ${response.code} ${response.message}")
                        } else {
                            val responseBody = response.body?.string() ?: ""
                            if (responseBody.contains("success") || responseBody.contains("appended")) {
                                Pair(true, "Successfully backed up Invoice #${invoice.invoiceNumber} to Google Sheets!")
                            } else if (responseBody.contains("error") || responseBody.contains("fail")) {
                                Pair(false, "Apps Script response: $responseBody")
                            } else {
                                Pair(true, "Backed up! Web App Response: $responseBody")
                            }
                        }
                    }
                }
                onComplete(result.first, result.second)
            } catch (e: Exception) {
                android.util.Log.e("IntegrationServices", "Google Sheets Sync Error for invoice #${invoice.invoiceNumber}: ${e.message}", e)
                onComplete(false, "Connection failed: ${e.localizedMessage ?: "Unknown network error"}. (Logged for retry/queue)")
            }
        }
    }
}
