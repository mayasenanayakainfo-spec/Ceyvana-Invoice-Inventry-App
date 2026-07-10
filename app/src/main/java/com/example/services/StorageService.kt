package com.example.services

import android.content.Context
import android.util.Log
import com.example.data.model.InvoiceEntity
import com.example.data.model.InvoiceItemEntity
import com.example.data.model.InvoiceWithItems
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * StorageService handles backup, file operations, and PDF cache cleanup.
 * Designed as a robust offline-first component of the Ceyvana Invoice Manager.
 */
object StorageService {
    private const val TAG = "StorageService"
    private const val BACKUP_DIR_NAME = "invoice_backups"

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    /**
     * Clear all cached invoice PDFs to free up disk space.
     */
    fun clearPdfCache(context: Context): Boolean {
        return try {
            val cacheDir = context.cacheDir
            val pdfFiles = cacheDir.listFiles { _, name -> name.endsWith(".pdf") }
            var success = true
            pdfFiles?.forEach { file ->
                if (file.exists() && !file.delete()) {
                    success = false
                    Log.w(TAG, "Failed to delete cached PDF: ${file.name}")
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing PDF cache: ${e.message}", e)
            false
        }
    }

    /**
     * Get the total size of all cached PDFs in bytes.
     */
    fun getPdfCacheSize(context: Context): Long {
        return try {
            val cacheDir = context.cacheDir
            val pdfFiles = cacheDir.listFiles { _, name -> name.endsWith(".pdf") }
            pdfFiles?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating PDF cache size: ${e.message}", e)
            0L
        }
    }

    /**
     * Export all invoices and their line items as a backup JSON file.
     */
    fun exportInvoicesBackup(context: Context, invoices: List<InvoiceWithItems>): File? {
        return try {
            val backupDir = File(context.filesDir, BACKUP_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }
            
            // Map the Room entities to serializable list
            val dataList = invoices.map { invoiceWithItems ->
                mapOf(
                    "invoice" to mapOf(
                        "id" to invoiceWithItems.invoice.id,
                        "invoiceNumber" to invoiceWithItems.invoice.invoiceNumber,
                        "issueDate" to invoiceWithItems.invoice.issueDate,
                        "dueDate" to invoiceWithItems.invoice.dueDate,
                        "clientName" to invoiceWithItems.invoice.clientName,
                        "clientEmail" to invoiceWithItems.invoice.clientEmail,
                        "clientPhone" to invoiceWithItems.invoice.clientPhone,
                        "clientAddress" to invoiceWithItems.invoice.clientAddress,
                        "status" to invoiceWithItems.invoice.status,
                        "notes" to invoiceWithItems.invoice.notes,
                        "discountPercent" to invoiceWithItems.invoice.discountPercent,
                        "subtotal" to invoiceWithItems.invoice.subtotal,
                        "taxTotal" to invoiceWithItems.invoice.taxTotal,
                        "total" to invoiceWithItems.invoice.total
                    ),
                    "items" to invoiceWithItems.items.map { item ->
                        mapOf(
                            "id" to item.id,
                            "invoiceId" to item.invoiceId,
                            "name" to item.name,
                            "quantity" to item.quantity,
                            "price" to item.price,
                            "taxPercent" to item.taxPercent,
                            "total" to item.total
                        )
                    }
                )
            }

            val type = Types.newParameterizedType(List::class.java, Map::class.java)
            val adapter: JsonAdapter<List<Map<String, Any>>> = moshi.adapter(type)
            val jsonString = adapter.toJson(dataList)

            val fileName = "ceyvana_backup_${System.currentTimeMillis()}.json"
            val file = File(backupDir, fileName)
            FileOutputStream(file).use { output ->
                output.write(jsonString.toByteArray(Charsets.UTF_8))
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export invoices backup: ${e.message}", e)
            null
        }
    }

    /**
     * Import invoices from a JSON backup file.
     */
    fun importInvoicesBackup(context: Context, backupFile: File): List<InvoiceWithItems>? {
        if (!backupFile.exists()) return null
        return try {
            val jsonString = backupFile.readText(Charsets.UTF_8)
            val type = Types.newParameterizedType(List::class.java, Map::class.java)
            val adapter: JsonAdapter<List<Map<String, Any>>> = moshi.adapter(type)
            val parsedList = adapter.fromJson(jsonString) ?: return null

            parsedList.map { itemMap ->
                val invoiceMap = itemMap["invoice"] as? Map<*, *> ?: throw IOException("Invalid backup format")
                val itemsList = itemMap["items"] as? List<*> ?: throw IOException("Invalid backup format")

                val invoice = InvoiceEntity(
                    id = (invoiceMap["id"] as? Double)?.toLong() ?: 0L,
                    invoiceNumber = invoiceMap["invoiceNumber"] as? String ?: "",
                    issueDate = (invoiceMap["issueDate"] as? Double)?.toLong() ?: 0L,
                    dueDate = (invoiceMap["dueDate"] as? Double)?.toLong() ?: 0L,
                    clientName = invoiceMap["clientName"] as? String ?: "",
                    clientEmail = invoiceMap["clientEmail"] as? String ?: "",
                    clientPhone = invoiceMap["clientPhone"] as? String ?: "",
                    clientAddress = invoiceMap["clientAddress"] as? String ?: "",
                    status = invoiceMap["status"] as? String ?: "Draft",
                    notes = invoiceMap["notes"] as? String ?: "",
                    discountPercent = invoiceMap["discountPercent"] as? Double ?: 0.0,
                    subtotal = invoiceMap["subtotal"] as? Double ?: 0.0,
                    taxTotal = invoiceMap["taxTotal"] as? Double ?: 0.0,
                    total = invoiceMap["total"] as? Double ?: 0.0
                )

                val items = itemsList.map { it ->
                    val itMap = it as? Map<*, *> ?: throw IOException("Invalid item format")
                    InvoiceItemEntity(
                        id = (itMap["id"] as? Double)?.toLong() ?: 0L,
                        invoiceId = (itMap["invoiceId"] as? Double)?.toLong() ?: 0L,
                        name = itMap["name"] as? String ?: "",
                        quantity = itMap["quantity"] as? Double ?: 1.0,
                        price = itMap["price"] as? Double ?: 0.0,
                        taxPercent = itMap["taxPercent"] as? Double ?: 0.0,
                        total = itMap["total"] as? Double ?: 0.0
                    )
                }

                InvoiceWithItems(invoice, items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import invoices backup: ${e.message}", e)
            null
        }
    }

    /**
     * List all available backup files.
     */
    fun listBackups(context: Context): List<File> {
        return try {
            val backupDir = File(context.filesDir, BACKUP_DIR_NAME)
            if (!backupDir.exists()) return emptyList()
            backupDir.listFiles { _, name -> name.endsWith(".json") }?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing backup files: ${e.message}", e)
            emptyList()
        }
    }
}
