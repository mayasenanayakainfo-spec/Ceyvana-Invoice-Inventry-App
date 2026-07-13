package com.example.services

import com.example.data.model.InvoiceWithItems
import com.example.data.model.ProductEntity
import java.util.Calendar

data class DashboardMetrics(
    val todaySales: Double,
    val monthlySales: Double,
    val totalInvoices: Int,
    val activeCustomers: Int
)

object ReportsService {
    /**
     * Calculates high-level dashboard metrics for the reports screen.
     */
    fun calculateDashboardMetrics(
        invoices: List<InvoiceWithItems>
    ): DashboardMetrics {
        val now = System.currentTimeMillis()
        
        // Start of today
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfToday = calendar.timeInMillis
        
        // Start of current month
        val monthCalendar = Calendar.getInstance()
        monthCalendar.timeInMillis = now
        monthCalendar.set(Calendar.DAY_OF_MONTH, 1)
        monthCalendar.set(Calendar.HOUR_OF_DAY, 0)
        monthCalendar.set(Calendar.MINUTE, 0)
        monthCalendar.set(Calendar.SECOND, 0)
        monthCalendar.set(Calendar.MILLISECOND, 0)
        val startOfMonth = monthCalendar.timeInMillis

        // Today's Sales (from paid or total? usually total invoices issued today, but we can do total sales)
        val todayInvoices = invoices.filter { it.invoice.issueDate >= startOfToday }
        val todaySales = todayInvoices.sumOf { it.invoice.total }

        // Monthly Sales (issued this month)
        val monthInvoices = invoices.filter { it.invoice.issueDate >= startOfMonth }
        val monthSales = monthInvoices.sumOf { it.invoice.total }

        // Total Invoices
        val totalInvoicesCount = invoices.size

        // Active Customers (distinct customer names across all invoices)
        val activeCustomersCount = invoices.map { it.invoice.clientName }
            .filter { it.isNotBlank() }
            .distinct()
            .size

        return DashboardMetrics(
            todaySales = todaySales,
            monthlySales = monthSales,
            totalInvoices = totalInvoicesCount,
            activeCustomers = activeCustomersCount
        )
    }
}
