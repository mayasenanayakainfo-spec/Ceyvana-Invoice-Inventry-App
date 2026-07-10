package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.ClientEntity
import com.example.data.model.InvoiceEntity
import com.example.data.model.InvoiceItemEntity
import com.example.data.model.InvoiceWithItems
import com.example.data.model.ProductEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceDao {

    // --- Invoices ---
    @Transaction
    @Query("SELECT * FROM invoices ORDER BY id DESC")
    fun getAllInvoices(): Flow<List<InvoiceWithItems>>

    @Transaction
    @Query("SELECT * FROM invoices WHERE id = :id")
    fun getInvoiceById(id: Long): Flow<InvoiceWithItems?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: InvoiceEntity): Long

    @Update
    suspend fun updateInvoice(invoice: InvoiceEntity)

    @Delete
    suspend fun deleteInvoice(invoice: InvoiceEntity)

    // --- Invoice Items ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoiceItem(item: InvoiceItemEntity): Long

    @Query("DELETE FROM invoice_items WHERE invoiceId = :invoiceId")
    suspend fun deleteInvoiceItemsByInvoiceId(invoiceId: Long)

    // --- Combined Transactional Saving ---
    @Transaction
    suspend fun saveInvoiceWithItems(invoice: InvoiceEntity, items: List<InvoiceItemEntity>): Long {
        val invoiceId = if (invoice.id == 0L) {
            insertInvoice(invoice)
        } else {
            updateInvoice(invoice)
            deleteInvoiceItemsByInvoiceId(invoice.id)
            invoice.id
        }
        val allProducts = getAllProductsList()
        items.forEach { item ->
            insertInvoiceItem(item.copy(invoiceId = invoiceId))
            
            // Automatically reduce stock when an invoice is created
            if (invoice.id == 0L) {
                // Find matching product using smart name matching (exact, prefix, or contains)
                val product = allProducts.find { prod ->
                    val name1 = prod.name.trim().lowercase()
                    val name2 = item.name.trim().lowercase()
                    name1 == name2 || name2.startsWith(name1) || name1.startsWith(name2) || name2.contains(name1) || name1.contains(name2)
                }
                if (product != null) {
                    val updatedStock = product.stock - item.quantity
                    updateProductStock(product.id, updatedStock)
                    
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    val formattedDate = sdf.format(java.util.Date())
                    val movement = com.example.data.model.StockMovementEntity(
                        productId = product.id,
                        type = "OUT",
                        quantity = item.quantity,
                        reason = "Sale",
                        reference = invoice.invoiceNumber,
                        date = formattedDate,
                        changeAmount = -item.quantity,
                        stockAfter = updatedStock,
                        createdAt = formattedDate
                    )
                    insertStockMovement(movement)
                }
            }
        }
        return invoiceId
    }

    // --- Clients ---
    @Query("SELECT * FROM clients ORDER BY name ASC")
    fun getAllClients(): Flow<List<ClientEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClient(client: ClientEntity): Long

    @Delete
    suspend fun deleteClient(client: ClientEntity)

    // --- Products ---
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products")
    suspend fun getAllProductsList(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE name = :name LIMIT 1")
    suspend fun getProductByName(name: String): ProductEntity?

    @Query("UPDATE products SET stock = :newStock WHERE id = :productId")
    suspend fun updateProductStock(productId: Long, newStock: Double)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: ProductEntity): Long

    @Delete
    suspend fun deleteProduct(product: ProductEntity)

    // --- Stock Movements ---
    @Query("SELECT * FROM stock_movements ORDER BY timestamp DESC")
    fun getAllStockMovements(): Flow<List<com.example.data.model.StockMovementEntity>>

    @Query("SELECT * FROM stock_movements WHERE productId = :productId ORDER BY timestamp DESC")
    fun getStockMovementsByProduct(productId: Long): Flow<List<com.example.data.model.StockMovementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockMovement(movement: com.example.data.model.StockMovementEntity): Long

    // --- Units ---
    @Query("SELECT * FROM units ORDER BY name ASC")
    fun getAllUnits(): Flow<List<com.example.data.model.UnitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnit(unit: com.example.data.model.UnitEntity): Long

    @Delete
    suspend fun deleteUnit(unit: com.example.data.model.UnitEntity)
}
