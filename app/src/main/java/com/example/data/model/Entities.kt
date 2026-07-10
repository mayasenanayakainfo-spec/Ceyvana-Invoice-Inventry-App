package com.example.data.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val company: String = "",
    val phone: String,
    val whatsapp: String = "",
    val email: String,
    val address: String, // Billing Address
    val shippingAddress: String = "",
    val notes: String = ""
)

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sku: String = "",
    val barcode: String? = null,
    val name: String,
    val price: Double,
    val description: String = "",
    val taxPercent: Double = 0.0,
    val category: String = "Whole Spices",
    val unit: String = "pack",
    val costPrice: Double = 0.0,
    val sellingPrice: Double = price,
    val stock: Double = 0.0,
    val minStock: Double = 5.0,
    val imagePath: String? = null,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class StockMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val type: String, // IN / OUT / ADJUSTMENT
    val quantity: Double, // Quantity
    val reason: String, // Sale, Purchase, Adjustment, Return
    val reference: String = "", // Invoice number
    val date: String = "", // Date & Time
    val changeAmount: Double = if (type == "OUT") -quantity else quantity,
    val stockAfter: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val createdAt: String = date
)

@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNumber: String,
    val issueDate: Long,
    val dueDate: Long,
    val clientName: String,
    val clientEmail: String,
    val clientPhone: String,
    val clientAddress: String,
    val status: String, // "Draft", "Sent", "Paid", "Overdue"
    val notes: String,
    val discountPercent: Double = 0.0,
    val shippingCharge: Double = 0.0,
    val currency: String = "LKR",
    val paymentMethod: String = "Bank Transfer",
    val subtotal: Double = 0.0,
    val taxTotal: Double = 0.0,
    val total: Double = 0.0
)

@Entity(
    tableName = "invoice_items",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class InvoiceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val name: String,
    val quantity: Double,
    val price: Double,
    val taxPercent: Double = 0.0,
    val total: Double = 0.0
)

data class InvoiceWithItems(
    @Embedded val invoice: InvoiceEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "invoiceId"
    )
    val items: List<InvoiceItemEntity>
)

@Entity(tableName = "units")
data class UnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = ""
)

