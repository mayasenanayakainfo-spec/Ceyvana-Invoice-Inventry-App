package com.example.services

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.data.model.InvoiceWithItems
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InvoicePdfService {

    private fun drawCenteredText(canvas: Canvas, text: String, y: Float, paint: Paint) {
        val width = paint.measureText(text)
        val x = (595f - width) / 2
        canvas.drawText(text, x, y, paint)
    }

    private fun drawRightAlignedValue(canvas: Canvas, text: String, rightX: Float, y: Float, paint: Paint) {
        val width = paint.measureText(text)
        canvas.drawText(text, rightX - width, y, paint)
    }

    fun generateInvoicePdf(context: Context, invoiceWithItems: InvoiceWithItems): File? {
        val pdfDocument = PdfDocument()
        
        // Standard A4 Size: 595 x 842 points
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        // Setup paints
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            isAntiAlias = true
        }

        val borderPaint = Paint().apply {
            color = Color.parseColor("#CCCCCC") // Clean divider gray
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }

        val boldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val invoice = invoiceWithItems.invoice
        val items = invoiceWithItems.items
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        val settingsManager = SettingsManager(context)
        val currencySym = if (invoice.currency == "USD") "USD" else "Rs."

        var currentY = 40f

        // Top Divider Line
        canvas.drawLine(30f, currentY, 565f, currentY, borderPaint)
        currentY += 25f

        // 1. Load and Render Company Logo
        val logoBitmap = try {
            context.assets.open("logo.png").use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            null
        }

        if (logoBitmap != null) {
            val logoWidth = 64f
            val logoHeight = 64f
            val logoX = (595f - logoWidth) / 2
            val logoRect = RectF(logoX, currentY, logoX + logoWidth, currentY + logoHeight)
            canvas.drawBitmap(logoBitmap, null, logoRect, null)
            currentY += logoHeight + 15f
        } else {
            // [ COMPANY LOGO ] placeholder text if missing
            boldPaint.textSize = 12f
            boldPaint.color = Color.GRAY
            drawCenteredText(canvas, "[ COMPANY LOGO ]", currentY, boldPaint)
            currentY += 25f
        }

        // 2. Center Company Name & Owner (M3-styled spacing & typography)
        boldPaint.textSize = 14f
        boldPaint.color = Color.parseColor("#14532D") // Deep rich green accent
        drawCenteredText(canvas, settingsManager.companyName, currentY, boldPaint)
        currentY += 18f

        textPaint.textSize = 10f
        textPaint.color = Color.parseColor("#475569") // Slate-600
        drawCenteredText(canvas, settingsManager.companyOwner, currentY, textPaint)
        currentY += 20f

        // 3. Center Address Lines (Splitting address dynamically by comma for gorgeous stacked output)
        textPaint.textSize = 10f
        textPaint.color = Color.BLACK
        val addressParts = settingsManager.companyAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (addressParts.isNotEmpty()) {
            for (part in addressParts) {
                drawCenteredText(canvas, part, currentY, textPaint)
                currentY += 15f
            }
        } else {
            drawCenteredText(canvas, settingsManager.companyAddress, currentY, textPaint)
            currentY += 15f
        }
        currentY += 5f

        // 4. Center Phone Header & Numbers
        boldPaint.textSize = 10f
        boldPaint.color = Color.BLACK
        drawCenteredText(canvas, "Phone:", currentY, boldPaint)
        currentY += 15f

        val phones = mutableListOf<String>()
        if (settingsManager.companyPhone.isNotEmpty()) phones.add(settingsManager.companyPhone)
        if (settingsManager.companyPhone2.isNotEmpty()) phones.add(settingsManager.companyPhone2)
        
        textPaint.color = Color.BLACK
        for (phone in phones) {
            drawCenteredText(canvas, phone, currentY, textPaint)
            currentY += 15f
        }
        currentY += 10f

        // 5. Center Invoice No & Date
        boldPaint.textSize = 11f
        boldPaint.color = Color.parseColor("#14532D")
        drawCenteredText(canvas, "Invoice No : ${invoice.invoiceNumber}", currentY, boldPaint)
        currentY += 15f

        textPaint.textSize = 10f
        textPaint.color = Color.parseColor("#475569")
        val dateText = "Date        : ${sdf.format(Date(invoice.issueDate))}"
        drawCenteredText(canvas, dateText, currentY, textPaint)
        currentY += 20f

        // Divider after Company Profile Info
        canvas.drawLine(30f, currentY, 565f, currentY, borderPaint)
        currentY += 20f

        // 6. Left-Aligned "Bill To" Customer Profile Info
        boldPaint.textSize = 11f
        boldPaint.color = Color.parseColor("#14532D")
        canvas.drawText("Bill To", 35f, currentY, boldPaint)
        currentY += 18f

        textPaint.textSize = 10f
        textPaint.color = Color.BLACK
        canvas.drawText(invoice.clientName, 35f, currentY, textPaint)
        currentY += 15f

        val clientAddressParts = invoice.clientAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (part in clientAddressParts) {
            canvas.drawText(part, 35f, currentY, textPaint)
            currentY += 15f
        }

        if (invoice.clientPhone.isNotEmpty()) {
            canvas.drawText(invoice.clientPhone, 35f, currentY, textPaint)
            currentY += 15f
        }
        currentY += 10f

        // Divider after Customer Profile Info
        canvas.drawLine(30f, currentY, 565f, currentY, borderPaint)
        currentY += 20f

        // 7. Table Header (Description, Qty, Price, Total)
        boldPaint.textSize = 10f
        boldPaint.color = Color.parseColor("#14532D")
        canvas.drawText("Description", 35f, currentY, boldPaint)
        drawRightAlignedValue(canvas, "Qty", 330f, currentY, boldPaint)
        drawRightAlignedValue(canvas, "Price", 430f, currentY, boldPaint)
        drawRightAlignedValue(canvas, "Total", 540f, currentY, boldPaint)
        currentY += 12f

        // Table Header Divider
        canvas.drawLine(30f, currentY, 565f, currentY, borderPaint)
        currentY += 8f

        // 8. Render Items with exact Spacing
        textPaint.textSize = 10f
        textPaint.color = Color.BLACK
        for (item in items) {
            currentY += 18f
            if (currentY > 740f) break // Basic multi-page boundary check

            canvas.drawText(item.name, 35f, currentY, textPaint)
            drawRightAlignedValue(canvas, "%.0f".format(item.quantity), 330f, currentY, textPaint)
            drawRightAlignedValue(canvas, "%,.2f".format(item.price), 430f, currentY, textPaint)
            drawRightAlignedValue(canvas, "%,.2f".format(item.total), 540f, currentY, textPaint)
        }
        currentY += 15f

        // Table Footer Divider
        canvas.drawLine(30f, currentY, 565f, currentY, borderPaint)
        currentY += 20f

        // 9. Summary & Grand Total Section
        textPaint.textSize = 10f
        boldPaint.textSize = 10f
        
        // Subtotal row
        canvas.drawText("Subtotal", 35f, currentY, textPaint)
        drawRightAlignedValue(canvas, "$currencySym %,.2f".format(invoice.subtotal), 540f, currentY, textPaint)
        currentY += 16f
        
        // Discount row
        if (invoice.discountPercent > 0.0) {
            val discountVal = invoice.subtotal * (invoice.discountPercent / 100.0)
            canvas.drawText("Discount (${invoice.discountPercent}%)", 35f, currentY, textPaint)
            drawRightAlignedValue(canvas, "-$currencySym %,.2f".format(discountVal), 540f, currentY, textPaint)
            currentY += 16f
        }
        
        // Shipping row
        if (invoice.shippingCharge > 0.0) {
            canvas.drawText("Shipping", 35f, currentY, textPaint)
            drawRightAlignedValue(canvas, "$currencySym %,.2f".format(invoice.shippingCharge), 540f, currentY, textPaint)
            currentY += 16f
        }
        
        // Tax row
        if (invoice.taxTotal > 0.0) {
            canvas.drawText("Tax Amount", 35f, currentY, textPaint)
            drawRightAlignedValue(canvas, "$currencySym %,.2f".format(invoice.taxTotal), 540f, currentY, textPaint)
            currentY += 16f
        }
        
        // Payment Method row
        canvas.drawText("Payment Method", 35f, currentY, textPaint)
        drawRightAlignedValue(canvas, invoice.paymentMethod, 540f, currentY, textPaint)
        currentY += 16f

        // Divider before Grand Total
        canvas.drawLine(30f, currentY, 565f, currentY, borderPaint)
        currentY += 16f

        // Grand Total Row
        boldPaint.textSize = 11f
        boldPaint.color = Color.parseColor("#14532D")
        canvas.drawText("Grand Total", 35f, currentY, boldPaint)
        drawRightAlignedValue(canvas, "$currencySym %,.2f".format(invoice.total), 540f, currentY, boldPaint)
        currentY += 10f

        // Grand Total Bottom Divider
        canvas.drawLine(30f, currentY, 565f, currentY, borderPaint)
        currentY += 35f

        // 10. Center Thank You Footer message
        boldPaint.textSize = 11f
        boldPaint.color = Color.parseColor("#14532D")
        drawCenteredText(canvas, "Thank You For Your Business", currentY, boldPaint)

        // Draw Notes if present
        if (invoice.notes.isNotEmpty()) {
            currentY += 20f
            textPaint.textSize = 9f
            textPaint.color = Color.GRAY
            drawCenteredText(canvas, "Notes: ${invoice.notes}", currentY, textPaint)
        }

        // 11. Draw Professional Bottom Footer (fixed at the bottom of the page)
        val footerYStart = 760f
        canvas.drawLine(30f, footerYStart, 565f, footerYStart, borderPaint)
        
        val footerPaint = Paint().apply {
            color = Color.parseColor("#475569") // Slate-600
            textSize = 8.5f
            isAntiAlias = true
        }
        
        val addressLine = settingsManager.companyAddress
        val contactLine = "Phone: ${settingsManager.companyPhone} | ${settingsManager.companyPhone2}  •  WhatsApp: ${settingsManager.companyWhatsapp}"
        val emailLine = "Email: ${settingsManager.companyEmail}"
        
        drawCenteredText(canvas, addressLine, footerYStart + 15f, footerPaint)
        drawCenteredText(canvas, contactLine, footerYStart + 30f, footerPaint)
        drawCenteredText(canvas, emailLine, footerYStart + 45f, footerPaint)

        pdfDocument.finishPage(page)

        // Save PDF to App Documents directory with names such as INV-00001.pdf
        val documentsDir = context.filesDir
        val file = File(documentsDir, "${invoice.invoiceNumber}.pdf")
        return try {
            val fos = FileOutputStream(file)
            pdfDocument.writeTo(fos)
            pdfDocument.close()
            fos.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            pdfDocument.close()
            null
        }
    }
}
