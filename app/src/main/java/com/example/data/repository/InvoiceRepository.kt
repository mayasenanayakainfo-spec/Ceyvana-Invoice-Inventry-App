package com.example.data.repository

import com.example.data.local.InvoiceDao
import com.example.data.model.ClientEntity
import com.example.data.model.InvoiceEntity
import com.example.data.model.InvoiceItemEntity
import com.example.data.model.InvoiceWithItems
import com.example.data.model.ProductEntity
import kotlinx.coroutines.flow.Flow

class InvoiceRepository(private val invoiceDao: InvoiceDao) {

    val allInvoices: Flow<List<InvoiceWithItems>> = invoiceDao.getAllInvoices()
    val allClients: Flow<List<ClientEntity>> = invoiceDao.getAllClients()
    val allProducts: Flow<List<ProductEntity>> = invoiceDao.getAllProducts()

    fun getInvoiceById(id: Long): Flow<InvoiceWithItems?> {
        return invoiceDao.getInvoiceById(id)
    }

    suspend fun saveInvoice(invoice: InvoiceEntity, items: List<InvoiceItemEntity>): Long {
        return invoiceDao.saveInvoiceWithItems(invoice, items)
    }

    suspend fun deleteInvoice(invoice: InvoiceEntity) {
        invoiceDao.deleteInvoice(invoice)
    }

    suspend fun saveClient(client: ClientEntity): Long {
        return invoiceDao.insertClient(client)
    }

    suspend fun deleteClient(client: ClientEntity) {
        invoiceDao.deleteClient(client)
    }

    val allStockMovements: Flow<List<com.example.data.model.StockMovementEntity>> = invoiceDao.getAllStockMovements()

    fun getStockMovementsByProduct(productId: Long): Flow<List<com.example.data.model.StockMovementEntity>> {
        return invoiceDao.getStockMovementsByProduct(productId)
    }

    suspend fun saveStockMovement(movement: com.example.data.model.StockMovementEntity): Long {
        return invoiceDao.insertStockMovement(movement)
    }

    suspend fun updateProductStock(productId: Long, stock: Double) {
        invoiceDao.updateProductStock(productId, stock)
    }

    suspend fun saveProduct(product: ProductEntity, customMovementReason: String? = null): Long {
        val isNew = product.id == 0L
        val oldProd = if (!isNew) invoiceDao.getProductByName(product.name) else null
        val generatedId = invoiceDao.insertProduct(product)
        
        // If a new product is created, or stock level is adjusted, log a stock movement!
        if (isNew) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val formattedDate = sdf.format(java.util.Date())
            invoiceDao.insertStockMovement(
                com.example.data.model.StockMovementEntity(
                    productId = generatedId,
                    type = "IN",
                    quantity = product.stock,
                    reason = customMovementReason ?: "Adjustment",
                    reference = "Initial",
                    date = formattedDate,
                    changeAmount = product.stock,
                    stockAfter = product.stock,
                    createdAt = formattedDate
                )
            )
        } else if (oldProd != null && oldProd.stock != product.stock) {
            val change = product.stock - oldProd.stock
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val formattedDate = sdf.format(java.util.Date())
            val isAddition = change >= 0
            invoiceDao.insertStockMovement(
                com.example.data.model.StockMovementEntity(
                    productId = product.id,
                    type = if (isAddition) "IN" else "OUT",
                    quantity = if (isAddition) change else -change,
                    reason = customMovementReason ?: "Adjustment",
                    reference = "Manual",
                    date = formattedDate,
                    changeAmount = change,
                    stockAfter = product.stock,
                    createdAt = formattedDate
                )
            )
        }
        return generatedId
    }

    suspend fun deleteProduct(product: ProductEntity) {
        invoiceDao.deleteProduct(product)
    }

    val allUnits: Flow<List<com.example.data.model.UnitEntity>> = invoiceDao.getAllUnits()

    suspend fun saveUnit(unit: com.example.data.model.UnitEntity): Long {
        return invoiceDao.insertUnit(unit)
    }

    suspend fun deleteUnit(unit: com.example.data.model.UnitEntity) {
        invoiceDao.deleteUnit(unit)
    }
}
