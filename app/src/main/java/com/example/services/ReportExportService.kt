package com.example.services

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.example.R
import com.example.data.model.InvoiceWithItems
import com.example.data.model.ProductEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

object ReportExportService {

    private fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return sdf.format(Date())
    }

    // --- 1. Text (Monospace ASCII) Report Generator ---
    fun generateTextReport(
        reportType: String,
        invoices: List<InvoiceWithItems>,
        products: List<ProductEntity>,
        filterLabel: String
    ): String {
        val dateStr = getFormattedDate()
        return buildString {
            appendLine("==========================================================================")
            appendLine("                      BUSINESS REPORT: ${reportType.uppercase()}")
            appendLine("==========================================================================")
            appendLine("Filter/Period: $filterLabel")
            appendLine("Generated On : $dateStr")
            appendLine("Currency     : LKR / USD")
            appendLine("--------------------------------------------------------------------------")

            when (reportType) {
                "Daily Sales" -> {
                    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                    val groups = invoices.groupBy {
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = it.invoice.issueDate
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }.toList().sortedBy { it.first }

                    appendLine("%-15s | %-10s | %15s | %15s".format("Date", "Invoices", "Billed Amount", "Paid Amount"))
                    appendLine("--------------------------------------------------------------------------")
                    if (groups.isEmpty()) {
                        appendLine("No records found.")
                    } else {
                        var totalInvoices = 0
                        var totalBilled = 0.0
                        var totalPaid = 0.0
                        for ((dateMs, invList) in groups) {
                            val dStr = sdf.format(Date(dateMs))
                            val count = invList.size
                            val billed = invList.sumOf { it.invoice.total }
                            val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
                            
                            appendLine("%-15s | %-10d | %15.2f | %15.2f".format(dStr, count, billed, paid))
                            totalInvoices += count
                            totalBilled += billed
                            totalPaid += paid
                        }
                        appendLine("--------------------------------------------------------------------------")
                        appendLine("%-15s | %-10d | %15.2f | %15.2f".format("TOTAL", totalInvoices, totalBilled, totalPaid))
                    }
                }
                "Monthly Sales" -> {
                    val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                    val groups = invoices.groupBy {
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = it.invoice.issueDate
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }.toList().sortedBy { it.first }

                    appendLine("%-18s | %-10s | %15s | %15s".format("Month", "Invoices", "Billed Amount", "Paid Amount"))
                    appendLine("--------------------------------------------------------------------------")
                    if (groups.isEmpty()) {
                        appendLine("No records found.")
                    } else {
                        var totalInvoices = 0
                        var totalBilled = 0.0
                        var totalPaid = 0.0
                        for ((dateMs, invList) in groups) {
                            val mStr = sdf.format(Date(dateMs))
                            val count = invList.size
                            val billed = invList.sumOf { it.invoice.total }
                            val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }

                            appendLine("%-18s | %-10d | %15.2f | %15.2f".format(mStr, count, billed, paid))
                            totalInvoices += count
                            totalBilled += billed
                            totalPaid += paid
                        }
                        appendLine("--------------------------------------------------------------------------")
                        appendLine("%-18s | %-10d | %15.2f | %15.2f".format("TOTAL", totalInvoices, totalBilled, totalPaid))
                    }
                }
                "Customer Report" -> {
                    val groups = invoices.groupBy { it.invoice.clientName.trim() }.toList().sortedByDescending { it.second.sumOf { inv -> inv.invoice.total } }

                    appendLine("%-22s | %-10s | %12s | %12s | %12s".format("Customer Name", "Invoices", "Billed", "Paid", "Outstanding"))
                    appendLine("--------------------------------------------------------------------------")
                    if (groups.isEmpty()) {
                        appendLine("No records found.")
                    } else {
                        var grandBilled = 0.0
                        var grandPaid = 0.0
                        var grandOutstanding = 0.0
                        for ((clientName, invList) in groups) {
                            val count = invList.size
                            val billed = invList.sumOf { it.invoice.total }
                            val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
                            val outstanding = billed - paid

                            appendLine("%-22s | %-10d | %12.2f | %12.2f | %12.2f".format(
                                if (clientName.length > 22) clientName.take(19) + "..." else clientName,
                                count, billed, paid, outstanding
                            ))
                            grandBilled += billed
                            grandPaid += paid
                            grandOutstanding += outstanding
                        }
                        appendLine("--------------------------------------------------------------------------")
                        appendLine("%-22s | %-10s | %12.2f | %12.2f | %12.2f".format("TOTAL", "", grandBilled, grandPaid, grandOutstanding))
                    }
                }
                "Product Report" -> {
                    val items = invoices.flatMap { it.items }
                    val groups = items.groupBy { it.name.trim() }.toList().sortedByDescending { it.second.sumOf { item -> item.total } }

                    appendLine("%-20s | %-8s | %10s | %12s | %12s".format("Product Name", "Qty", "Avg Price", "Total Sales", "Est. Profit"))
                    appendLine("--------------------------------------------------------------------------")
                    if (groups.isEmpty()) {
                        appendLine("No records found.")
                    } else {
                        var grandQty = 0.0
                        var grandSales = 0.0
                        var grandProfit = 0.0
                        for ((prodName, itemList) in groups) {
                            val qty = itemList.sumOf { it.quantity }
                            val sales = itemList.sumOf { it.total }
                            val avgPrice = if (qty > 0) sales / qty else 0.0
                            
                            val matchedProd = products.find { it.name.trim().equals(prodName, ignoreCase = true) }
                            val costPrice = matchedProd?.costPrice ?: 0.0
                            val estCost = qty * costPrice
                            val profit = sales - estCost

                            appendLine("%-20s | %-8.1f | %10.2f | %12.2f | %12.2f".format(
                                if (prodName.length > 20) prodName.take(17) + "..." else prodName,
                                qty, avgPrice, sales, profit
                            ))
                            grandQty += qty
                            grandSales += sales
                            grandProfit += profit
                        }
                        appendLine("--------------------------------------------------------------------------")
                        appendLine("%-20s | %-8.1f | %10s | %12.2f | %12.2f".format("TOTAL", grandQty, "", grandSales, grandProfit))
                    }
                }
                "Payment Report" -> {
                    val groups = invoices.groupBy { it.invoice.paymentMethod.trim() }
                    val grandTotal = invoices.sumOf { it.invoice.total }.coerceAtLeast(1.0)

                    appendLine("%-25s | %-12s | %15s | %10s".format("Payment Method", "Tx Count", "Total Billed", "Share %"))
                    appendLine("--------------------------------------------------------------------------")
                    if (groups.isEmpty()) {
                        appendLine("No records found.")
                    } else {
                        var grandCount = 0
                        var grandBilled = 0.0
                        for ((method, invList) in groups) {
                            val count = invList.size
                            val total = invList.sumOf { it.invoice.total }
                            val share = (total / grandTotal) * 100

                            appendLine("%-25s | %-12d | %15.2f | %9.1f%%".format(method, count, total, share))
                            grandCount += count
                            grandBilled += total
                        }
                        appendLine("--------------------------------------------------------------------------")
                        appendLine("%-25s | %-12d | %15.2f | %10s".format("TOTAL", grandCount, grandBilled, "100.0%"))
                    }
                }
                "Inventory Report" -> {
                    appendLine("%-18s | %-15s | %-8s | %10s | %12s".format("Product", "Category", "Stock", "Cost Price", "Retail Value"))
                    appendLine("--------------------------------------------------------------------------")
                    if (products.isEmpty()) {
                        appendLine("No items in inventory.")
                    } else {
                        var totalStock = 0.0
                        var totalCostValuation = 0.0
                        var totalRetailValuation = 0.0
                        for (prod in products.sortedBy { it.name }) {
                            val stock = prod.stock
                            val cost = prod.costPrice
                            val sell = prod.price
                            val costVal = stock * cost
                            val retailVal = stock * sell

                            appendLine("%-18s | %-15s | %-8.1f | %10.2f | %12.2f".format(
                                if (prod.name.length > 18) prod.name.take(15) + "..." else prod.name,
                                if (prod.category.length > 15) prod.category.take(12) + "..." else prod.category,
                                stock, cost, retailVal
                            ))
                            totalStock += stock
                            totalCostValuation += costVal
                            totalRetailValuation += retailVal
                        }
                        appendLine("--------------------------------------------------------------------------")
                        appendLine("%-18s | %-15s | %-8.1f | %10s | %12.2f".format("TOTAL", "", totalStock, "Cost Est:", totalRetailValuation))
                        appendLine("Total Cost Valuation: Rs. %,.2f".format(totalCostValuation))
                    }
                }
                else -> {
                    appendLine("Unknown report type: $reportType")
                }
            }
            appendLine("==========================================================================")
        }
    }

    // --- 2. CSV Report Generator ---
    fun generateCsvReport(
        reportType: String,
        invoices: List<InvoiceWithItems>,
        products: List<ProductEntity>,
        filterLabel: String
    ): String {
        return buildString {
            // Header comments
            appendLine("# BUSINESS REPORT: ${reportType.uppercase()}")
            appendLine("# Filter/Period: $filterLabel")
            appendLine("# Generated On: ${getFormattedDate()}")
            appendLine("")

            when (reportType) {
                "Daily Sales" -> {
                    appendLine("Date,Invoices,Billed Amount,Paid Amount")
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val groups = invoices.groupBy {
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = it.invoice.issueDate
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }.toList().sortedBy { it.first }

                    for ((dateMs, invList) in groups) {
                        val dStr = sdf.format(Date(dateMs))
                        val count = invList.size
                        val billed = invList.sumOf { it.invoice.total }
                        val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
                        appendLine("\"$dStr\",$count,%.2f,%.2f".format(billed, paid))
                    }
                }
                "Monthly Sales" -> {
                    appendLine("Month,Invoices,Billed Amount,Paid Amount")
                    val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
                    val groups = invoices.groupBy {
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = it.invoice.issueDate
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }.toList().sortedBy { it.first }

                    for ((dateMs, invList) in groups) {
                        val mStr = sdf.format(Date(dateMs))
                        val count = invList.size
                        val billed = invList.sumOf { it.invoice.total }
                        val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
                        appendLine("\"$mStr\",$count,%.2f,%.2f".format(billed, paid))
                    }
                }
                "Customer Report" -> {
                    appendLine("Customer Name,Invoices,Billed,Paid,Outstanding")
                    val groups = invoices.groupBy { it.invoice.clientName.trim() }.toList().sortedByDescending { it.second.sumOf { inv -> inv.invoice.total } }

                    for ((clientName, invList) in groups) {
                        val count = invList.size
                        val billed = invList.sumOf { it.invoice.total }
                        val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
                        val outstanding = billed - paid
                        appendLine("\"$clientName\",$count,%.2f,%.2f,%.2f".format(billed, paid, outstanding))
                    }
                }
                "Product Report" -> {
                    appendLine("Product Name,Qty Sold,Avg Selling Price,Total Sales,Estimated Profit")
                    val items = invoices.flatMap { it.items }
                    val groups = items.groupBy { it.name.trim() }.toList().sortedByDescending { it.second.sumOf { item -> item.total } }

                    for ((prodName, itemList) in groups) {
                        val qty = itemList.sumOf { it.quantity }
                        val sales = itemList.sumOf { it.total }
                        val avgPrice = if (qty > 0) sales / qty else 0.0
                        val matchedProd = products.find { it.name.trim().equals(prodName, ignoreCase = true) }
                        val costPrice = matchedProd?.costPrice ?: 0.0
                        val estCost = qty * costPrice
                        val profit = sales - estCost
                        appendLine("\"$prodName\",%.2f,%.2f,%.2f,%.2f".format(qty, avgPrice, sales, profit))
                    }
                }
                "Payment Report" -> {
                    appendLine("Payment Method,Transaction Count,Total Billed,Share Percent")
                    val groups = invoices.groupBy { it.invoice.paymentMethod.trim() }
                    val grandTotal = invoices.sumOf { it.invoice.total }.coerceAtLeast(1.0)

                    for ((method, invList) in groups) {
                        val count = invList.size
                        val total = invList.sumOf { it.invoice.total }
                        val share = (total / grandTotal) * 100
                        appendLine("\"$method\",$count,%.2f,%.2f".format(total, share))
                    }
                }
                "Inventory Report" -> {
                    appendLine("Product Name,Category,Current Stock,Cost Price,Selling Price,Total Cost Valuation,Total Retail Valuation")
                    for (prod in products.sortedBy { it.name }) {
                        val stock = prod.stock
                        val cost = prod.costPrice
                        val sell = prod.price
                        val costVal = stock * cost
                        val retailVal = stock * sell
                        appendLine("\"${prod.name}\",\"${prod.category}\",%.2f,%.2f,%.2f,%.2f,%.2f".format(
                            stock, cost, sell, costVal, retailVal
                        ))
                    }
                }
                else -> {
                    appendLine("Error,Unknown report type")
                }
            }
        }
    }

    // --- 3. Excel XML Spreadsheet Report Generator ---
    fun generateExcelReport(
        reportType: String,
        invoices: List<InvoiceWithItems>,
        products: List<ProductEntity>,
        filterLabel: String
    ): String {
        return buildString {
            appendLine("<?xml version=\"1.0\"?>")
            appendLine("<?mso-application progid=\"Excel.Sheet\"?>")
            appendLine("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"")
            appendLine(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"")
            appendLine(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"")
            appendLine(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"")
            appendLine(" xmlns:html=\"http://www.w3.org/TR/REC-html40\">")
            
            // Styles
            appendLine(" <Styles>")
            appendLine("  <Style ss:ID=\"Default\" ss:Name=\"Normal\">")
            appendLine("   <Alignment ss:Vertical=\"Bottom\"/>")
            appendLine("   <Font ss:FontName=\"Calibri\" x:Family=\"Swiss\" ss:Size=\"11\" ss:Color=\"#000000\"/>")
            appendLine("  </Style>")
            appendLine("  <Style ss:ID=\"HeaderStyle\">")
            appendLine("   <Font ss:FontName=\"Calibri\" x:Family=\"Swiss\" ss:Size=\"12\" ss:Bold=\"1\" ss:Color=\"#FFFFFF\"/>")
            appendLine("   <Interior ss:Color=\"#1F4E78\" ss:Pattern=\"Solid\"/>")
            appendLine("  </Style>")
            appendLine("  <Style ss:ID=\"TitleStyle\">")
            appendLine("   <Font ss:FontName=\"Calibri\" x:Family=\"Swiss\" ss:Size=\"16\" ss:Bold=\"1\" ss:Color=\"#1F4E78\"/>")
            appendLine("  </Style>")
            appendLine("  <Style ss:ID=\"MetaStyle\">")
            appendLine("   <Font ss:FontName=\"Calibri\" x:Family=\"Swiss\" ss:Size=\"10\" ss:Italic=\"1\" ss:Color=\"#595959\"/>")
            appendLine("  </Style>")
            appendLine("  <Style ss:ID=\"TotalStyle\">")
            appendLine("   <Font ss:FontName=\"Calibri\" x:Family=\"Swiss\" ss:Size=\"11\" ss:Bold=\"1\" ss:Color=\"#000000\"/>")
            appendLine("   <Interior ss:Color=\"#F2F2F2\" ss:Pattern=\"Solid\"/>")
            appendLine("  </Style>")
            appendLine(" </Styles>")

            // Worksheet name
            val safeTitle = reportType.replace("[^a-zA-Z0-9 ]".toRegex(), "").take(30)
            appendLine(" <Worksheet ss:Name=\"$safeTitle\">")
            appendLine("  <Table>")

            // Title block
            appendLine("   <Row ss:Height=\"25\">")
            appendLine("    <Cell><Data ss:Type=\"String\">BUSINESS REPORT: ${reportType.uppercase()}</Data></Cell>")
            appendLine("   </Row>")
            appendLine("   <Row>")
            appendLine("    <Cell><Data ss:Type=\"String\">Period: $filterLabel</Data></Cell>")
            appendLine("   </Row>")
            appendLine("   <Row>")
            appendLine("    <Cell><Data ss:Type=\"String\">Generated on: ${getFormattedDate()}</Data></Cell>")
            appendLine("   </Row>")
            appendLine("   <Row></Row>") // Empty row separator

            when (reportType) {
                "Daily Sales" -> {
                    // Headers
                    appendLine("   <Row ss:StyleID=\"HeaderStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">Date</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Invoices</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Billed Amount</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Paid Amount</Data></Cell>")
                    appendLine("   </Row>")

                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val groups = invoices.groupBy {
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = it.invoice.issueDate
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }.toList().sortedBy { it.first }

                    var totalInvoices = 0
                    var totalBilled = 0.0
                    var totalPaid = 0.0

                    for ((dateMs, invList) in groups) {
                        val dStr = sdf.format(Date(dateMs))
                        val count = invList.size
                        val billed = invList.sumOf { it.invoice.total }
                        val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }

                        appendLine("   <Row>")
                        appendLine("    <Cell><Data ss:Type=\"String\">$dStr</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$count</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$billed</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$paid</Data></Cell>")
                        appendLine("   </Row>")

                        totalInvoices += count
                        totalBilled += billed
                        totalPaid += paid
                    }

                    // Total row
                    appendLine("   <Row ss:StyleID=\"TotalStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">TOTAL</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$totalInvoices</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$totalBilled</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$totalPaid</Data></Cell>")
                    appendLine("   </Row>")
                }
                "Monthly Sales" -> {
                    // Headers
                    appendLine("   <Row ss:StyleID=\"HeaderStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">Month</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Invoices</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Billed Amount</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Paid Amount</Data></Cell>")
                    appendLine("   </Row>")

                    val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
                    val groups = invoices.groupBy {
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = it.invoice.issueDate
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }.toList().sortedBy { it.first }

                    var totalInvoices = 0
                    var totalBilled = 0.0
                    var totalPaid = 0.0

                    for ((dateMs, invList) in groups) {
                        val mStr = sdf.format(Date(dateMs))
                        val count = invList.size
                        val billed = invList.sumOf { it.invoice.total }
                        val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }

                        appendLine("   <Row>")
                        appendLine("    <Cell><Data ss:Type=\"String\">$mStr</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$count</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$billed</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$paid</Data></Cell>")
                        appendLine("   </Row>")

                        totalInvoices += count
                        totalBilled += billed
                        totalPaid += paid
                    }

                    // Total row
                    appendLine("   <Row ss:StyleID=\"TotalStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">TOTAL</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$totalInvoices</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$totalBilled</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$totalPaid</Data></Cell>")
                    appendLine("   </Row>")
                }
                "Customer Report" -> {
                    appendLine("   <Row ss:StyleID=\"HeaderStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">Customer Name</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Invoices</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Billed Amount</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Paid Amount</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Outstanding</Data></Cell>")
                    appendLine("   </Row>")

                    val groups = invoices.groupBy { it.invoice.clientName.trim() }.toList().sortedByDescending { it.second.sumOf { inv -> inv.invoice.total } }

                    var grandBilled = 0.0
                    var grandPaid = 0.0
                    var grandOutstanding = 0.0

                    for ((clientName, invList) in groups) {
                        val count = invList.size
                        val billed = invList.sumOf { it.invoice.total }
                        val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
                        val outstanding = billed - paid

                        appendLine("   <Row>")
                        appendLine("    <Cell><Data ss:Type=\"String\">$clientName</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$count</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$billed</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$paid</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$outstanding</Data></Cell>")
                        appendLine("   </Row>")

                        grandBilled += billed
                        grandPaid += paid
                        grandOutstanding += outstanding
                    }

                    appendLine("   <Row ss:StyleID=\"TotalStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">TOTAL</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\"></Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$grandBilled</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$grandPaid</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$grandOutstanding</Data></Cell>")
                    appendLine("   </Row>")
                }
                "Product Report" -> {
                    appendLine("   <Row ss:StyleID=\"HeaderStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">Product Name</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Qty Sold</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Avg Selling Price</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Total Sales</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Estimated Profit</Data></Cell>")
                    appendLine("   </Row>")

                    val items = invoices.flatMap { it.items }
                    val groups = items.groupBy { it.name.trim() }.toList().sortedByDescending { it.second.sumOf { item -> item.total } }

                    var grandQty = 0.0
                    var grandSales = 0.0
                    var grandProfit = 0.0

                    for ((prodName, itemList) in groups) {
                        val qty = itemList.sumOf { it.quantity }
                        val sales = itemList.sumOf { it.total }
                        val avgPrice = if (qty > 0) sales / qty else 0.0
                        val matchedProd = products.find { it.name.trim().equals(prodName, ignoreCase = true) }
                        val costPrice = matchedProd?.costPrice ?: 0.0
                        val estCost = qty * costPrice
                        val profit = sales - estCost

                        appendLine("   <Row>")
                        appendLine("    <Cell><Data ss:Type=\"String\">$prodName</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$qty</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$avgPrice</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$sales</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$profit</Data></Cell>")
                        appendLine("   </Row>")

                        grandQty += qty
                        grandSales += sales
                        grandProfit += profit
                    }

                    appendLine("   <Row ss:StyleID=\"TotalStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">TOTAL</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$grandQty</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\"></Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$grandSales</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$grandProfit</Data></Cell>")
                    appendLine("   </Row>")
                }
                "Payment Report" -> {
                    appendLine("   <Row ss:StyleID=\"HeaderStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">Payment Method</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Tx Count</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Total Billed</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Share %</Data></Cell>")
                    appendLine("   </Row>")

                    val groups = invoices.groupBy { it.invoice.paymentMethod.trim() }
                    val grandTotal = invoices.sumOf { it.invoice.total }.coerceAtLeast(1.0)

                    var grandCount = 0
                    var grandBilled = 0.0

                    for ((method, invList) in groups) {
                        val count = invList.size
                        val total = invList.sumOf { it.invoice.total }
                        val share = (total / grandTotal) * 100

                        appendLine("   <Row>")
                        appendLine("    <Cell><Data ss:Type=\"String\">$method</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$count</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$total</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$share</Data></Cell>")
                        appendLine("   </Row>")

                        grandCount += count
                        grandBilled += total
                    }

                    appendLine("   <Row ss:StyleID=\"TotalStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">TOTAL</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$grandCount</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$grandBilled</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">100.0</Data></Cell>")
                    appendLine("   </Row>")
                }
                "Inventory Report" -> {
                    appendLine("   <Row ss:StyleID=\"HeaderStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">Product Name</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Category</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Current Stock</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Cost Price</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Selling Price</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Cost Valuation</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\">Retail Valuation</Data></Cell>")
                    appendLine("   </Row>")

                    var totalStock = 0.0
                    var totalCostValuation = 0.0
                    var totalRetailValuation = 0.0

                    for (prod in products.sortedBy { it.name }) {
                        val stock = prod.stock
                        val cost = prod.costPrice
                        val sell = prod.price
                        val costVal = stock * cost
                        val retailVal = stock * sell

                        appendLine("   <Row>")
                        appendLine("    <Cell><Data ss:Type=\"String\">${prod.name}</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"String\">${prod.category}</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$stock</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$cost</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$sell</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$costVal</Data></Cell>")
                        appendLine("    <Cell><Data ss:Type=\"Number\">$retailVal</Data></Cell>")
                        appendLine("   </Row>")

                        totalStock += stock
                        totalCostValuation += costVal
                        totalRetailValuation += retailVal
                    }

                    appendLine("   <Row ss:StyleID=\"TotalStyle\">")
                    appendLine("    <Cell><Data ss:Type=\"String\">TOTAL</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\"></Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$totalStock</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\"></Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"String\"></Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$totalCostValuation</Data></Cell>")
                    appendLine("    <Cell><Data ss:Type=\"Number\">$totalRetailValuation</Data></Cell>")
                    appendLine("   </Row>")
                }
            }

            appendLine("  </Table>")
            appendLine(" </Worksheet>")
            appendLine("</Workbook>")
        }
    }

    // --- 4. Beautiful Native PDF Exporter ---
    fun generatePdfReport(
        context: Context,
        reportType: String,
        invoices: List<InvoiceWithItems>,
        products: List<ProductEntity>,
        filterLabel: String
    ): File? {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        // Paints setup
        val titlePaint = Paint().apply {
            color = Color.parseColor("#1F4E78")
            textSize = 18f
            isAntiAlias = true
            isFakeBoldText = true
        }

        val metaPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 9f
            isAntiAlias = true
        }

        val headerBgPaint = Paint().apply {
            color = Color.parseColor("#1F4E78")
            style = Paint.Style.FILL
        }

        val headerTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 9f
            isAntiAlias = true
            isFakeBoldText = true
        }

        val rowTextPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8.5f
            isAntiAlias = true
        }

        val footerPaint = Paint().apply {
            color = Color.GRAY
            textSize = 8f
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = Color.parseColor("#DDDDDD")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        // Draw title
        canvas.drawText("BUSINESS REPORT: ${reportType.uppercase()}", 40f, 50f, titlePaint)
        
        // Draw Metadata
        canvas.drawText("Date Range: $filterLabel", 40f, 72f, metaPaint)
        canvas.drawText("Generated On: ${getFormattedDate()}", 40f, 86f, metaPaint)

        // Draw Logo if available in top-right corner
        try {
            val logoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ceyvana_invoice_logo_1783697801136)
            if (logoBitmap != null) {
                val logoRect = Rect(495, 25, 555, 85)
                canvas.drawBitmap(logoBitmap, null, logoRect, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Draw Table Header
        var currentY = 110f
        canvas.drawRect(40f, currentY, 555f, currentY + 24f, headerBgPaint)

        // Helper to draw left & right aligned columns
        fun drawRow(
            cols: List<String>,
            alignments: List<Paint.Align>,
            widths: List<Float>,
            isHeader: Boolean,
            y: Float
        ) {
            var currentX = 40f
            val paint = if (isHeader) headerTextPaint else rowTextPaint
            for (i in cols.indices) {
                val text = cols[i]
                val align = alignments[i]
                val width = widths[i]
                
                paint.textAlign = align
                
                val drawX = when (align) {
                    Paint.Align.RIGHT -> currentX + width - 5f
                    Paint.Align.CENTER -> currentX + (width / 2f)
                    else -> currentX + 5f
                }
                
                // Truncate text if it is too long for the column width
                var truncatedText = text
                if (paint.measureText(text) > width - 10f) {
                    for (j in text.length downTo 1) {
                        val sub = text.take(j) + "..."
                        if (paint.measureText(sub) <= width - 10f) {
                            truncatedText = sub
                            break
                        }
                    }
                }
                
                canvas.drawText(truncatedText, drawX, y + 15f, paint)
                currentX += width
            }
        }

        val (cols, alignments, widths) = when (reportType) {
            "Daily Sales" -> Triple(
                listOf("Date", "InvoicesCount", "Billed Amount (Rs.)", "Paid Amount (Rs.)"),
                listOf(Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT, Paint.Align.RIGHT),
                listOf(150f, 90f, 135f, 140f)
            )
            "Monthly Sales" -> Triple(
                listOf("Month", "InvoicesCount", "Billed Amount (Rs.)", "Paid Amount (Rs.)"),
                listOf(Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT, Paint.Align.RIGHT),
                listOf(150f, 90f, 135f, 140f)
            )
            "Customer Report" -> Triple(
                listOf("Customer Name", "Invoices", "Billed (Rs.)", "Paid (Rs.)", "Oustanding (Rs.)"),
                listOf(Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT, Paint.Align.RIGHT, Paint.Align.RIGHT),
                listOf(155f, 65f, 95f, 100f, 100f)
            )
            "Product Report" -> Triple(
                listOf("Product Name", "Qty", "Avg Price", "Total Sales", "Est. Profit"),
                listOf(Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT, Paint.Align.RIGHT, Paint.Align.RIGHT),
                listOf(155f, 65f, 95f, 100f, 100f)
            )
            "Payment Report" -> Triple(
                listOf("Payment Method", "Tx Count", "Total Billed (Rs.)", "Share Percent"),
                listOf(Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT, Paint.Align.RIGHT),
                listOf(165f, 90f, 140f, 120f)
            )
            "Inventory Report" -> Triple(
                listOf("Product Name", "Category", "Stock", "Cost Price", "Retail Value"),
                listOf(Paint.Align.LEFT, Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT, Paint.Align.RIGHT),
                listOf(145f, 110f, 60f, 100f, 100f)
            )
            else -> Triple(listOf("Column"), listOf(Paint.Align.LEFT), listOf(515f))
        }

        // Draw header text
        drawRow(cols, alignments, widths, true, currentY)
        currentY += 24f

        // Draw rows
        var rowCount = 0
        when (reportType) {
            "Daily Sales" -> {
                val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                val groups = invoices.groupBy {
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = it.invoice.issueDate
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                }.toList().sortedBy { it.first }

                var grandInvoices = 0
                var grandBilled = 0.0
                var grandPaid = 0.0

                for ((dateMs, invList) in groups) {
                    if (currentY > 750f) break // Simple page overflow safeguard
                    val dStr = sdf.format(Date(dateMs))
                    val count = invList.size
                    val billed = invList.sumOf { it.invoice.total }
                    val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }

                    drawRow(listOf(dStr, count.toString(), "%,.2f".format(billed), "%,.2f".format(paid)), alignments, widths, false, currentY)
                    currentY += 20f
                    canvas.drawLine(40f, currentY, 555f, currentY, dividerPaint)
                    
                    grandInvoices += count
                    grandBilled += billed
                    grandPaid += paid
                    rowCount++
                }

                // Total Row
                if (currentY <= 750f) {
                    drawRow(listOf("TOTAL", grandInvoices.toString(), "%,.2f".format(grandBilled), "%,.2f".format(grandPaid)), alignments, widths, true, currentY)
                }
            }
            "Monthly Sales" -> {
                val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                val groups = invoices.groupBy {
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = it.invoice.issueDate
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                }.toList().sortedBy { it.first }

                var grandInvoices = 0
                var grandBilled = 0.0
                var grandPaid = 0.0

                for ((dateMs, invList) in groups) {
                    if (currentY > 750f) break
                    val mStr = sdf.format(Date(dateMs))
                    val count = invList.size
                    val billed = invList.sumOf { it.invoice.total }
                    val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }

                    drawRow(listOf(mStr, count.toString(), "%,.2f".format(billed), "%,.2f".format(paid)), alignments, widths, false, currentY)
                    currentY += 20f
                    canvas.drawLine(40f, currentY, 555f, currentY, dividerPaint)

                    grandInvoices += count
                    grandBilled += billed
                    grandPaid += paid
                    rowCount++
                }

                if (currentY <= 750f) {
                    drawRow(listOf("TOTAL", grandInvoices.toString(), "%,.2f".format(grandBilled), "%,.2f".format(grandPaid)), alignments, widths, true, currentY)
                }
            }
            "Customer Report" -> {
                val groups = invoices.groupBy { it.invoice.clientName.trim() }.toList().sortedByDescending { it.second.sumOf { inv -> inv.invoice.total } }

                var grandBilled = 0.0
                var grandPaid = 0.0
                var grandOutstanding = 0.0

                for ((clientName, invList) in groups) {
                    if (currentY > 750f) break
                    val count = invList.size
                    val billed = invList.sumOf { it.invoice.total }
                    val paid = invList.filter { it.invoice.status.trim().equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
                    val outstanding = billed - paid

                    drawRow(listOf(clientName, count.toString(), "%,.2f".format(billed), "%,.2f".format(paid), "%,.2f".format(outstanding)), alignments, widths, false, currentY)
                    currentY += 20f
                    canvas.drawLine(40f, currentY, 555f, currentY, dividerPaint)

                    grandBilled += billed
                    grandPaid += paid
                    grandOutstanding += outstanding
                    rowCount++
                }

                if (currentY <= 750f) {
                    drawRow(listOf("TOTAL", "", "%,.2f".format(grandBilled), "%,.2f".format(grandPaid), "%,.2f".format(grandOutstanding)), alignments, widths, true, currentY)
                }
            }
            "Product Report" -> {
                val items = invoices.flatMap { it.items }
                val groups = items.groupBy { it.name.trim() }.toList().sortedByDescending { it.second.sumOf { item -> item.total } }

                var grandQty = 0.0
                var grandSales = 0.0
                var grandProfit = 0.0

                for ((prodName, itemList) in groups) {
                    if (currentY > 750f) break
                    val qty = itemList.sumOf { it.quantity }
                    val sales = itemList.sumOf { it.total }
                    val avgPrice = if (qty > 0) sales / qty else 0.0
                    val matchedProd = products.find { it.name.trim().equals(prodName, ignoreCase = true) }
                    val costPrice = matchedProd?.costPrice ?: 0.0
                    val estCost = qty * costPrice
                    val profit = sales - estCost

                    drawRow(listOf(prodName, "%,.1f".format(qty), "%,.2f".format(avgPrice), "%,.2f".format(sales), "%,.2f".format(profit)), alignments, widths, false, currentY)
                    currentY += 20f
                    canvas.drawLine(40f, currentY, 555f, currentY, dividerPaint)

                    grandQty += qty
                    grandSales += sales
                    grandProfit += profit
                    rowCount++
                }

                if (currentY <= 750f) {
                    drawRow(listOf("TOTAL", "%,.1f".format(grandQty), "", "%,.2f".format(grandSales), "%,.2f".format(grandProfit)), alignments, widths, true, currentY)
                }
            }
            "Payment Report" -> {
                val groups = invoices.groupBy { it.invoice.paymentMethod.trim() }
                val grandTotal = invoices.sumOf { it.invoice.total }.coerceAtLeast(1.0)

                var grandCount = 0
                var grandBilled = 0.0

                for ((method, invList) in groups) {
                    if (currentY > 750f) break
                    val count = invList.size
                    val total = invList.sumOf { it.invoice.total }
                    val share = (total / grandTotal) * 100

                    drawRow(listOf(method, count.toString(), "%,.2f".format(total), "%,.1f%%".format(share)), alignments, widths, false, currentY)
                    currentY += 20f
                    canvas.drawLine(40f, currentY, 555f, currentY, dividerPaint)

                    grandCount += count
                    grandBilled += total
                    rowCount++
                }

                if (currentY <= 750f) {
                    drawRow(listOf("TOTAL", grandCount.toString(), "%,.2f".format(grandBilled), "100.0%"), alignments, widths, true, currentY)
                }
            }
            "Inventory Report" -> {
                var totalStock = 0.0
                var totalCostValuation = 0.0
                var totalRetailValuation = 0.0

                for (prod in products.sortedBy { it.name }) {
                    if (currentY > 750f) break
                    val stock = prod.stock
                    val cost = prod.costPrice
                    val sell = prod.price
                    val costVal = stock * cost
                    val retailVal = stock * sell

                    drawRow(listOf(prod.name, prod.category, "%,.1f".format(stock), "%,.2f".format(cost), "%,.2f".format(retailVal)), alignments, widths, false, currentY)
                    currentY += 20f
                    canvas.drawLine(40f, currentY, 555f, currentY, dividerPaint)

                    totalStock += stock
                    totalCostValuation += costVal
                    totalRetailValuation += retailVal
                    rowCount++
                }

                if (currentY <= 750f) {
                    drawRow(listOf("TOTAL", "", "%,.1f".format(totalStock), "Cost Valuation:", "%,.2f".format(totalRetailValuation)), alignments, widths, true, currentY)
                    currentY += 20f
                    val valuationStr = "Estimated Cost Valuation: Rs. %,.2f".format(totalCostValuation)
                    footerPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(valuationStr, 555f, currentY + 12f, footerPaint)
                }
            }
        }

        // Draw page footer
        footerPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Page 1 of 1  •  Powered by AI Studio Build", 297f, 810f, footerPaint)

        pdfDocument.finishPage(page)

        val outputDir = context.cacheDir
        val file = File(outputDir, "${reportType.lowercase().replace(" ", "_")}_report.pdf")
        return try {
            val fileOutputStream = FileOutputStream(file)
            pdfDocument.writeTo(fileOutputStream)
            pdfDocument.close()
            fileOutputStream.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            pdfDocument.close()
            null
        }
    }
}
