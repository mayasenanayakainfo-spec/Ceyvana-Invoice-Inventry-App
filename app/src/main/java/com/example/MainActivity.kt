package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.ClientEntity
import com.example.data.model.InvoiceEntity
import com.example.data.model.InvoiceItemEntity
import com.example.data.model.InvoiceWithItems
import com.example.data.model.ProductEntity
import com.example.data.repository.InvoiceRepository
import com.example.ui.theme.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintManager
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PageRange
import android.os.CancellationSignal

import com.example.services.SettingsManager
import com.example.services.InvoicePdfService
import com.example.services.IntegrationServices
import com.example.services.StorageService
import com.example.utils.Company

// --- Navigation Screens ---
sealed class Screen {
    object Splash : Screen()
    object Home : Screen()
    object Dashboard : Screen()
    object ClientList : Screen()
    object ProductList : Screen()
    object Settings : Screen()
    data class AddEditInvoice(val invoiceId: Long?) : Screen()
    data class InvoiceDetail(val invoiceId: Long) : Screen()
    object InvoiceHistory : Screen()
    data class PdfPreview(val invoiceId: Long) : Screen()
    object Reports : Screen()
}

// --- ViewModel ---
class InvoiceViewModel(private val repository: InvoiceRepository) : ViewModel() {

    val invoices = repository.allInvoices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val clients = repository.allClients.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val products = repository.allProducts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val stockMovements = repository.allStockMovements.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val units = repository.allUnits.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _currentScreen = MutableStateFlow<Screen>(Screen.Splash)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    init {
        // Pre-populate database with beautiful realistic demo data if it is empty
        viewModelScope.launch {
            combine(repository.allInvoices, repository.allClients, repository.allProducts) { inv, cli, prod ->
                Triple(inv, cli, prod)
            }.first().let { (inv, cli, prod) ->
                if (cli.isEmpty() && prod.isEmpty() && inv.isEmpty()) {
                    populateDemoData()
                }
            }
        }
        viewModelScope.launch {
            repository.allUnits.first().let { list ->
                if (list.isEmpty()) {
                    val defaultUnits = listOf("Pack", "Bottle", "Box", "Piece", "Kilogram", "Gram", "Liter")
                    defaultUnits.forEach { u ->
                        repository.saveUnit(com.example.data.model.UnitEntity(name = u, description = "Common unit: $u"))
                    }
                }
            }
        }
    }

    fun saveUnit(unitName: String, description: String = "") {
        viewModelScope.launch {
            repository.saveUnit(com.example.data.model.UnitEntity(name = unitName, description = description))
        }
    }

    fun deleteUnit(unit: com.example.data.model.UnitEntity) {
        viewModelScope.launch {
            repository.deleteUnit(unit)
        }
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun saveInvoice(invoice: InvoiceEntity, items: List<InvoiceItemEntity>, onComplete: (Long) -> Unit) {
        viewModelScope.launch {
            val invoiceId = repository.saveInvoice(invoice, items)
            onComplete(invoiceId)
        }
    }

    fun duplicateInvoice(invoiceWithItems: InvoiceWithItems, onComplete: (Long) -> Unit) {
        viewModelScope.launch {
            val allInvs = invoices.value
            val existingNumbers = allInvs.map { it.invoice.invoiceNumber }.toSet()
            var copyNumber = "${invoiceWithItems.invoice.invoiceNumber}-COPY"
            var counter = 1
            while (existingNumbers.contains(copyNumber)) {
                copyNumber = "${invoiceWithItems.invoice.invoiceNumber}-COPY$counter"
                counter++
            }
            val newInvoice = invoiceWithItems.invoice.copy(
                id = 0L,
                invoiceNumber = copyNumber,
                issueDate = System.currentTimeMillis(),
                status = "Draft"
            )
            val newItems = invoiceWithItems.items.map {
                it.copy(id = 0L, invoiceId = 0L)
            }
            val newId = repository.saveInvoice(newInvoice, newItems)
            onComplete(newId)
        }
    }

    fun deleteInvoice(invoice: InvoiceEntity) {
        viewModelScope.launch {
            repository.deleteInvoice(invoice)
            _currentScreen.value = Screen.Dashboard
        }
    }

    fun updateInvoiceStatus(invoiceWithItems: InvoiceWithItems, newStatus: String) {
        viewModelScope.launch {
            val updatedInvoice = invoiceWithItems.invoice.copy(status = newStatus)
            repository.saveInvoice(updatedInvoice, invoiceWithItems.items)
        }
    }

    fun saveClient(client: ClientEntity) {
        viewModelScope.launch {
            repository.saveClient(client)
        }
    }

    fun deleteClient(client: ClientEntity) {
        viewModelScope.launch {
            repository.deleteClient(client)
        }
    }

    fun saveProduct(product: ProductEntity, customMovementReason: String? = null) {
        viewModelScope.launch {
            repository.saveProduct(product, customMovementReason)
        }
    }

    fun deleteProduct(product: ProductEntity) {
        viewModelScope.launch {
            repository.deleteProduct(product)
        }
    }

    private suspend fun populateDemoData() {
        val client1Id = repository.saveClient(
            ClientEntity(
                name = "John Silva",
                company = "Lanka Exports",
                phone = "+94 77 123 4567",
                whatsapp = "+94 77 123 4567",
                email = "john@lankaexports.lk",
                address = "12 Main Street, Colombo 03, Sri Lanka",
                shippingAddress = "Port Authority Warehouse 4, Colombo, Sri Lanka",
                notes = "Premium cinnamon bulk importer"
            )
        )
        val client2Id = repository.saveClient(
            ClientEntity(
                name = "ABC Supermarket",
                company = "ABC Supermarket Group",
                phone = "+94 11 234 5678",
                whatsapp = "+94 71 234 5678",
                email = "billing@abcsupermarket.lk",
                address = "45 Galle Road, Colombo 04, Sri Lanka",
                shippingAddress = "Central Distribution Center, Yakkala, Sri Lanka",
                notes = "Prefers quick delivery on Friday mornings"
            )
        )
        val client3Id = repository.saveClient(
            ClientEntity(
                name = "Tokyo Spice Imports",
                company = "Tokyo Spice Imports Inc.",
                phone = "+81 3 5555 1234",
                whatsapp = "+81 80 5555 1234",
                email = "import@tokyospice.jp",
                address = "1-2-3 Ginza, Chuo-ku, Tokyo, Japan",
                shippingAddress = "Yokohama Port Terminal Terminal B, Japan",
                notes = "High quality organic cardamom and clove buyer"
            )
        )

        val prod1Id = repository.saveProduct(
            ProductEntity(
                sku = "SKU-BP-500",
                barcode = "4790011223344",
                name = "Black Pepper",
                price = 1250.0,
                description = "Premium grade whole black pepper pods from Matale",
                taxPercent = 0.0,
                category = "Whole Spices",
                unit = "Packs",
                costPrice = 900.0,
                sellingPrice = 1250.0,
                stock = 120.0,
                minStock = 10.0,
                createdAt = "2026-07-01",
                updatedAt = "2026-07-10"
            )
        )
        val prod2Id = repository.saveProduct(
            ProductEntity(
                sku = "SKU-CS-ALBA",
                barcode = "4790011223351",
                name = "Cinnamon",
                price = 950.0,
                description = "High quality Ceylon Alba grade cinnamon sticks",
                taxPercent = 0.0,
                category = "Whole Spices",
                unit = "Packs",
                costPrice = 700.0,
                sellingPrice = 950.0,
                stock = 80.0,
                minStock = 10.0,
                createdAt = "2026-07-01",
                updatedAt = "2026-07-10"
            )
        )
        val prod3Id = repository.saveProduct(
            ProductEntity(
                sku = "SKU-CL-ORG",
                barcode = "4790011223368",
                name = "Cloves",
                price = 1800.0,
                description = "Dried hand-picked organic whole cloves",
                taxPercent = 0.0,
                category = "Whole Spices",
                unit = "Packs",
                costPrice = 1300.0,
                sellingPrice = 1800.0,
                stock = 60.0,
                minStock = 10.0,
                createdAt = "2026-07-01",
                updatedAt = "2026-07-10"
            )
        )
        val prod4Id = repository.saveProduct(
            ProductEntity(
                sku = "SKU-CD-TRUE",
                barcode = "4790011223375",
                name = "Cardamom",
                price = 4800.0,
                description = "True Ceylon green cardamom pods, excellent aroma",
                taxPercent = 0.0,
                category = "Whole Spices",
                unit = "kg",
                costPrice = 3500.0,
                sellingPrice = 4800.0,
                stock = 2.0, // Low Stock!
                minStock = 4.0,
                createdAt = "2026-07-01",
                updatedAt = "2026-07-10"
            )
        )
        val prod5Id = repository.saveProduct(
            ProductEntity(
                sku = "SKU-NM-EXP",
                barcode = "4790011223382",
                name = "Nutmeg",
                price = 1500.0,
                description = "Whole organic nutmeg seeds, export grade",
                taxPercent = 0.0,
                category = "Whole Spices",
                unit = "pack",
                costPrice = 1000.0,
                sellingPrice = 1500.0,
                stock = 8.0,
                minStock = 3.0,
                createdAt = "2026-07-01",
                updatedAt = "2026-07-10"
            )
        )
        val prod6Id = repository.saveProduct(
            ProductEntity(
                sku = "SKU-MC-RED",
                barcode = "4790011223399",
                name = "Mace",
                price = 3200.0,
                description = "Whole dried orange-red mace blades (nutmeg aril)",
                taxPercent = 0.0,
                category = "Whole Spices",
                unit = "pack",
                costPrice = 2200.0,
                sellingPrice = 3200.0,
                stock = 20.0,
                minStock = 5.0,
                createdAt = "2026-07-01",
                updatedAt = "2026-07-10"
            )
        )
        val prod7Id = repository.saveProduct(
            ProductEntity(
                sku = "SKU-TM-POW",
                barcode = "4790011223405",
                name = "Turmeric Powder",
                price = 850.0,
                description = "Pure organic ground turmeric root with high curcumin",
                taxPercent = 0.0,
                category = "Ground Spices",
                unit = "pack",
                costPrice = 550.0,
                sellingPrice = 850.0,
                stock = 25.0,
                minStock = 10.0,
                createdAt = "2026-07-01",
                updatedAt = "2026-07-10"
            )
        )
        val prod8Id = repository.saveProduct(
            ProductEntity(
                sku = "SKU-CY-MIX",
                barcode = "4790011223412",
                name = "Curry Powder",
                price = 650.0,
                description = "Traditional roasted Sri Lankan spice curry blend",
                taxPercent = 0.0,
                category = "Spice Mixes",
                unit = "pack",
                costPrice = 400.0,
                sellingPrice = 650.0,
                stock = 4.0, // Low Stock!
                minStock = 10.0,
                createdAt = "2026-07-01",
                updatedAt = "2026-07-10"
            )
        )

        // Generate dummy invoices
        val now = System.currentTimeMillis()
        val dayInMs = 24 * 60 * 60 * 1000L

        // Paid Invoice
        val inv1 = InvoiceEntity(
            invoiceNumber = "CV-2026-00001",
            issueDate = now - 15 * dayInMs,
            dueDate = now - 5 * dayInMs,
            clientName = "John Silva",
            clientEmail = "john@silva.com",
            clientPhone = "+94 77 123 4567",
            clientAddress = "12 Main Street, Colombo 03, Sri Lanka",
            status = "Paid",
            notes = "Thank you for choosing Ceyvana Solutions! Please keep this receipt for your records.",
            discountPercent = 5.0,
            shippingCharge = 500.0,
            currency = "LKR",
            paymentMethod = "Bank Transfer",
            subtotal = 48500.0,
            taxTotal = 0.0,
            total = 46575.0
        )
        val items1 = listOf(
            InvoiceItemEntity(invoiceId = 0, name = "Cardamom", quantity = 5.0, price = 4800.0, taxPercent = 0.0, total = 24000.0),
            InvoiceItemEntity(invoiceId = 0, name = "Cinnamon Sticks", quantity = 10.0, price = 2450.0, taxPercent = 0.0, total = 24500.0)
        )
        repository.saveInvoice(inv1, items1)

        // Sent (Pending) Invoice
        val inv2 = InvoiceEntity(
            invoiceNumber = "CV-2026-00002",
            issueDate = now - 2 * dayInMs,
            dueDate = now + 12 * dayInMs,
            clientName = "ABC Stores",
            clientEmail = "billing@abcstores.lk",
            clientPhone = "+94 11 234 5678",
            clientAddress = "45 Galle Road, Colombo 04, Sri Lanka",
            status = "Sent",
            notes = "Net 14 payment terms. Please submit payments via bank transfer.",
            discountPercent = 0.0,
            shippingCharge = 350.0,
            currency = "LKR",
            paymentMethod = "Bank Transfer",
            subtotal = 18500.0,
            taxTotal = 0.0,
            total = 18850.0
        )
        val items2 = listOf(
            InvoiceItemEntity(invoiceId = 0, name = "Black Pepper 500g", quantity = 10.0, price = 1850.0, taxPercent = 0.0, total = 18500.0)
        )
        repository.saveInvoice(inv2, items2)

        // Overdue Invoice
        val inv3 = InvoiceEntity(
            invoiceNumber = "CV-2026-00003",
            issueDate = now - 40 * dayInMs,
            dueDate = now - 20 * dayInMs,
            clientName = "XYZ Traders",
            clientEmail = "contact@xyztraders.com",
            clientPhone = "+94 11 987 6543",
            clientAddress = "789 Kandy Road, Yakkala, Sri Lanka",
            status = "Overdue",
            notes = "Overdue invoices are subject to 1.5% late fee per month.",
            discountPercent = 10.0,
            shippingCharge = 450.0,
            currency = "USD",
            paymentMethod = "Cash",
            subtotal = 125.0,
            taxTotal = 0.0,
            total = 112.50
        )
        val items3 = listOf(
            InvoiceItemEntity(invoiceId = 0, name = "Cloves", quantity = 8.0, price = 6.50, taxPercent = 0.0, total = 52.0),
            InvoiceItemEntity(invoiceId = 0, name = "Nutmeg", quantity = 10.0, price = 5.00, taxPercent = 0.0, total = 50.0)
        )
        repository.saveInvoice(inv3, items3)
    }
}

// --- Factory ---
class InvoiceViewModelFactory(private val repository: InvoiceRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InvoiceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InvoiceViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// --- MainActivity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(applicationContext)
        val repository = InvoiceRepository(database.invoiceDao())
        val factory = InvoiceViewModelFactory(repository)

        setContent {
            val context = LocalContext.current
            val settingsManager = remember { SettingsManager(context) }
            var isDarkTheme by remember { mutableStateOf(settingsManager.isDarkMode) }

            MyApplicationTheme(darkTheme = isDarkTheme) {
                val viewModel: InvoiceViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                InvoiceAppMain(
                    viewModel = viewModel,
                    settingsManager = settingsManager,
                    isDarkTheme = isDarkTheme,
                    onThemeChange = { dark ->
                        settingsManager.isDarkMode = dark
                        isDarkTheme = dark
                    }
                )
            }
        }
    }
}

@Composable
fun InvoiceAppMain(
    viewModel: InvoiceViewModel,
    settingsManager: SettingsManager,
    isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            if (currentScreen is Screen.Home || currentScreen is Screen.Dashboard || currentScreen is Screen.ClientList || currentScreen is Screen.ProductList || currentScreen is Screen.Settings) {
                InvoiceBottomNavigation(
                    currentScreen = currentScreen,
                    onNavigate = { viewModel.navigateTo(it) }
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "screen_navigation"
            ) { screen ->
                when (screen) {
                    is Screen.Splash -> SplashScreen(
                        onSplashFinished = { viewModel.navigateTo(Screen.Home) }
                    )
                    is Screen.Home -> HomeScreen(
                        viewModel = viewModel,
                        settingsManager = settingsManager
                    )
                    is Screen.Dashboard -> DashboardScreen(
                        viewModel = viewModel,
                        onAddInvoice = { viewModel.navigateTo(Screen.AddEditInvoice(null)) },
                        onViewInvoice = { id -> viewModel.navigateTo(Screen.InvoiceDetail(id)) }
                    )
                    is Screen.ClientList -> ClientListScreen(
                        viewModel = viewModel,
                        onBack = { viewModel.navigateTo(Screen.Home) }
                    )
                    is Screen.ProductList -> ProductListScreen(
                        viewModel = viewModel,
                        onBack = { viewModel.navigateTo(Screen.Home) }
                    )
                    is Screen.Settings -> SettingsScreen(
                        settingsManager = settingsManager,
                        isDarkTheme = isDarkTheme,
                        onThemeChange = onThemeChange,
                        onBack = { viewModel.navigateTo(Screen.Home) }
                    )
                    is Screen.AddEditInvoice -> AddEditInvoiceScreen(
                        viewModel = viewModel,
                        invoiceId = screen.invoiceId,
                        onBack = { viewModel.navigateTo(Screen.Dashboard) }
                    )
                    is Screen.InvoiceDetail -> InvoiceDetailScreen(
                        viewModel = viewModel,
                        invoiceId = screen.invoiceId,
                        settingsManager = settingsManager,
                        onBack = { viewModel.navigateTo(Screen.Dashboard) },
                        onEdit = { viewModel.navigateTo(Screen.AddEditInvoice(screen.invoiceId)) }
                    )
                    is Screen.InvoiceHistory -> InvoiceHistoryScreen(
                        viewModel = viewModel,
                        onBack = { viewModel.navigateTo(Screen.Home) },
                        onViewInvoice = { id -> viewModel.navigateTo(Screen.InvoiceDetail(id)) }
                    )
                    is Screen.PdfPreview -> PdfPreviewScreen(
                        viewModel = viewModel,
                        invoiceId = screen.invoiceId,
                        onBack = { viewModel.navigateTo(Screen.InvoiceDetail(screen.invoiceId)) }
                    )
                    is Screen.Reports -> ReportsScreen(
                        viewModel = viewModel,
                        onBack = { viewModel.navigateTo(Screen.Home) }
                    )
                }
            }
        }
    }
}

// --- Bottom Navigation ---
@Composable
fun InvoiceBottomNavigation(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar(
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        NavigationBarItem(
            selected = currentScreen is Screen.Home,
            onClick = { onNavigate(Screen.Home) },
            icon = { Icon(Icons.Outlined.Home, contentDescription = "Home") },
            label = { Text("Home", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.testTag("nav_home")
        )
        NavigationBarItem(
            selected = currentScreen is Screen.Dashboard,
            onClick = { onNavigate(Screen.Dashboard) },
            icon = { Icon(Icons.Outlined.Description, contentDescription = "Invoices") },
            label = { Text("Invoices", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.testTag("nav_invoices")
        )
        NavigationBarItem(
            selected = currentScreen is Screen.ClientList,
            onClick = { onNavigate(Screen.ClientList) },
            icon = { Icon(Icons.Outlined.People, contentDescription = "Clients") },
            label = { Text("Clients", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.testTag("nav_clients")
        )
        NavigationBarItem(
            selected = currentScreen is Screen.ProductList,
            onClick = { onNavigate(Screen.ProductList) },
            icon = { Icon(Icons.Outlined.Inventory2, contentDescription = "Products") },
            label = { Text("Catalog", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.testTag("nav_products")
        )
        NavigationBarItem(
            selected = currentScreen is Screen.Settings,
            onClick = { onNavigate(Screen.Settings) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
            label = { Text("Settings", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.testTag("nav_settings")
        )
    }
}

// --- Dashboard Screen ---
@Composable
fun DashboardScreen(
    viewModel: InvoiceViewModel,
    onAddInvoice: () -> Unit,
    onViewInvoice: (Long) -> Unit
) {
    val invoices by viewModel.invoices.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    var selectedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredInvoices = remember(invoices, selectedFilter, searchQuery) {
        invoices.filter {
            val matchesFilter = selectedFilter == "All" || it.invoice.status.equals(selectedFilter, ignoreCase = true)
            val matchesQuery = it.invoice.invoiceNumber.contains(searchQuery, ignoreCase = true) ||
                    it.invoice.clientName.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    // Calculations for metrics
    val totalInvoiced = invoices.sumOf { it.invoice.total }
    val totalPaid = invoices.filter { it.invoice.status.equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
    val totalPending = invoices.filter { !it.invoice.status.equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
    val activeCount = invoices.filter { !it.invoice.status.equals("Paid", ignoreCase = true) }.size

    // Step 8: Calculate Today's Sales, Monthly Sales, and Total Invoices from SQLite data
    val todayCal = Calendar.getInstance()
    val todayYear = todayCal.get(Calendar.YEAR)
    val todayDayOfYear = todayCal.get(Calendar.DAY_OF_YEAR)
    val todayMonth = todayCal.get(Calendar.MONTH)

    val todaySales = invoices.filter {
        val cal = Calendar.getInstance().apply { timeInMillis = it.invoice.issueDate }
        cal.get(Calendar.YEAR) == todayYear && cal.get(Calendar.DAY_OF_YEAR) == todayDayOfYear
    }.sumOf { it.invoice.total }

    val monthlySales = invoices.filter {
        val cal = Calendar.getInstance().apply { timeInMillis = it.invoice.issueDate }
        cal.get(Calendar.YEAR) == todayYear && cal.get(Calendar.MONTH) == todayMonth
    }.sumOf { it.invoice.total }

    val totalInvoicesCount = invoices.size

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddInvoice,
                icon = { Icon(Icons.Filled.Add, "Create Invoice") },
                text = { Text("New Invoice") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_create_invoice")
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Sleek Top Brand Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "C",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Ceyvana",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = { /* Search is integrated below */ },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    // Profile Avatar
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary)
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "JD",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondary
                        )
                    }
                }
            }

            // Sleek Outstanding Metrics Hero Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(SleekDarkBg)
                    .padding(24.dp)
            ) {
                // Soft translucent circle highlight in top right
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 30.dp, y = (-30).dp)
                        .clip(CircleShape)
                        .background(SleekPrimary.copy(alpha = 0.25f))
                )

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Total Outstanding",
                        color = SleekSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Rs. %,.2f".format(totalPending),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic,
                            fontSize = 32.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = "NEXT PAYOUT",
                                color = AppAccent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val payoutSdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                            val calendar = Calendar.getInstance()
                            calendar.add(Calendar.DAY_OF_YEAR, 7) // 7 days from now
                            Text(
                                text = payoutSdf.format(calendar.time),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Growth percentage chip matching the HTML mockup
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "+12.4%",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Sales Summary row (Step 8)
            Text(
                text = "Sales Summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    title = "Today's Sales",
                    value = "Rs. %,.2f".format(todaySales),
                    icon = Icons.Filled.Today,
                    color = AppPrimary
                )
                MetricCard(
                    title = "Monthly Sales",
                    value = "Rs. %,.2f".format(monthlySales),
                    icon = Icons.Filled.DateRange,
                    color = AppSecondary
                )
                MetricCard(
                    title = "Total Invoices",
                    value = "$totalInvoicesCount",
                    icon = Icons.Filled.Description,
                    color = AppAccent
                )
            }

            // Overview row
            Text(
                text = "Overview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(title = "Total Invoiced", value = "Rs. %,.2f".format(totalInvoiced), icon = Icons.Filled.TrendingUp, color = MaterialTheme.colorScheme.primary)
                MetricCard(title = "Paid Received", value = "Rs. %,.2f".format(totalPaid), icon = Icons.Filled.CheckCircle, color = StatusPaid)
                MetricCard(title = "Pending Balance", value = "Rs. %,.2f".format(totalPending), icon = Icons.Filled.Pending, color = StatusSent)
                MetricCard(title = "Active Bills", value = "$activeCount", icon = Icons.Filled.ReceiptLong, color = StatusOverdue)
            }

            // Inventory Dashboard section
            Text(
                text = "Inventory Dashboard",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
            )

            val totalProductsCount = products.size
            val totalStockValue = products.sumOf { (it.sellingPrice ?: it.price ?: 0.0) * it.stock }
            val lowStockCount = products.filter { it.stock <= it.minStock && it.stock > 0 }.size
            val outOfStockCount = products.filter { it.stock <= 0 }.size

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    title = "Total Products",
                    value = "$totalProductsCount",
                    icon = Icons.Filled.Inventory,
                    color = MaterialTheme.colorScheme.primary
                )
                MetricCard(
                    title = "Stock Value",
                    value = "Rs. %,.0f".format(totalStockValue),
                    icon = Icons.Filled.MonetizationOn,
                    color = Color(0xFF2E7D32)
                )
                MetricCard(
                    title = "Low Stock",
                    value = "$lowStockCount",
                    icon = Icons.Filled.Warning,
                    color = Color(0xFFE65100)
                )
                MetricCard(
                    title = "Out of Stock",
                    value = "$outOfStockCount",
                    icon = Icons.Filled.Cancel,
                    color = Color(0xFFC62828)
                )
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("dashboard_search_input"),
                placeholder = { Text("Search by invoice # or client...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Filter status chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Draft", "Sent", "Paid", "Overdue").forEach { status ->
                    val isSelected = selectedFilter == status
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = status },
                        label = { Text(status) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            // Invoices listing
            if (filteredInvoices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = "No Invoices",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No invoices found matching selection.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    filteredInvoices.forEach { invoiceWithItems ->
                        InvoiceListItem(
                            invoiceWithItems = invoiceWithItems,
                            onClick = { onViewInvoice(invoiceWithItems.invoice.id) }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(80.dp)) // Offset FAB
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .width(160.dp)
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun InvoiceListItem(
    invoiceWithItems: InvoiceWithItems,
    onClick: () -> Unit
) {
    val invoice = invoiceWithItems.invoice
    val items = invoiceWithItems.items
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    val statusColor = when (invoice.status.lowercase()) {
        "paid" -> StatusPaid
        "sent" -> StatusSent
        "overdue" -> StatusOverdue
        else -> StatusDraft
    }

    val statusIcon = when (invoice.status.lowercase()) {
        "paid" -> Icons.Outlined.Check
        "sent" -> Icons.Outlined.Info
        "overdue" -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Schedule
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, SleekBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("invoice_item_card_${invoice.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon Box
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = invoice.status,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Invoice number & Client Name
            Column(modifier = Modifier.weight(1.0f)) {
                Text(
                    text = if (invoice.invoiceNumber.startsWith("#")) invoice.invoiceNumber else "#${invoice.invoiceNumber}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = invoice.clientName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Due: ${sdf.format(Date(invoice.dueDate))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            // Price & Status Label
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatCurrency(invoice.total, invoice.currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = invoice.status.uppercase(),
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun StatusPill(status: String) {
    val color = when (status.lowercase()) {
        "paid" -> StatusPaid
        "sent" -> StatusSent
        "overdue" -> StatusOverdue
        else -> StatusDraft
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = status.uppercase(),
            color = color,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.5.sp
        )
    }
}

// --- Invoice Detail & Printable View ---
@Composable
fun InvoiceDetailScreen(
    viewModel: InvoiceViewModel,
    invoiceId: Long,
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    val invoices by viewModel.invoices.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    val invoiceWithItems = remember(invoices, invoiceId) {
        invoices.find { it.invoice.id == invoiceId }
    }

    if (invoiceWithItems == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val invoice = invoiceWithItems.invoice
    val items = invoiceWithItems.items
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Invoice") },
            text = { Text("Are you sure you want to delete this invoice?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteInvoice(invoice)
                        Toast.makeText(context, "Invoice Deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Invoice ${invoice.invoiceNumber}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.duplicateInvoice(invoiceWithItems) { newId ->
                            viewModel.navigateTo(Screen.InvoiceDetail(newId))
                            Toast.makeText(context, "Invoice Duplicated as Draft!", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate")
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = {
                        showDeleteDialog = true
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Quick Status Modifier Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mark as:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                listOf("Paid", "Sent", "Draft", "Overdue").forEach { s ->
                    val isCurrent = invoice.status.equals(s, ignoreCase = true)
                    Button(
                        onClick = {
                            viewModel.updateInvoiceStatus(invoiceWithItems, s)
                            Toast.makeText(context, "Status set to $s", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(s, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // PDF/Paper Layout Canvas
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // Title Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                "CEYVANA INVOICE",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = AppPrimary
                            )
                            Text(
                                "Billed Locally & Securely",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "INVOICE",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = AppPrimary
                            )
                            Text(
                                invoice.invoiceNumber,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = AppSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = Color.LightGray, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Billing & Date grids
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1.0f)) {
                            Text("CLIENT (BILL TO):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(invoice.clientName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                            Text(invoice.clientEmail, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                            Text(invoice.clientPhone, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                            Text(invoice.clientAddress, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray, maxLines = 3)
                        }
                        Column(modifier = Modifier.weight(1.0f), horizontalAlignment = Alignment.End) {
                            Text("INVOICE DETAILS:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row {
                                Text("Issue Date: ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Text(sdf.format(Date(invoice.issueDate)), style = MaterialTheme.typography.bodySmall, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                            Row {
                                Text("Due Date: ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Text(sdf.format(Date(invoice.dueDate)), style = MaterialTheme.typography.bodySmall, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                            Row {
                                Text("Payment: ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Text(invoice.paymentMethod, style = MaterialTheme.typography.bodySmall, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                            Row {
                                Text("Status: ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Text(invoice.status.uppercase(), style = MaterialTheme.typography.bodySmall, color = when(invoice.status.lowercase()) {
                                    "paid" -> StatusPaid
                                    "sent" -> StatusSent
                                    "overdue" -> StatusOverdue
                                    "draft" -> StatusDraft
                                    else -> Color.Gray
                                }, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Line Items Table Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF1F5F9))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("DESCRIPTION", modifier = Modifier.weight(2.5f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                        Text("QTY", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.DarkGray, textAlign = TextAlign.Center)
                        Text("PRICE", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.DarkGray, textAlign = TextAlign.End)
                        Text("TAX", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.DarkGray, textAlign = TextAlign.End)
                        Text("TOTAL", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.DarkGray, textAlign = TextAlign.End)
                    }

                    // Line Items rows
                    items.forEach { item ->
                        val matchedProduct = products.find { prod ->
                            prod.name.trim().equals(item.name.trim(), ignoreCase = true)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(2.5f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (matchedProduct != null) {
                                    ProductImageThumbnail(
                                        imagePath = matchedProduct.imagePath,
                                        category = matchedProduct.category,
                                        name = matchedProduct.name,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = Color.Black)
                            }
                            Text(item.quantity.toString(), modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodyMedium, color = Color.Black, textAlign = TextAlign.Center)
                            Text(formatCurrency(item.price, invoice.currency), modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.bodyMedium, color = Color.Black, textAlign = TextAlign.End)
                            Text("${item.taxPercent}%", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodyMedium, color = Color.Black, textAlign = TextAlign.End)
                            Text(formatCurrency(item.total, invoice.currency), modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium, color = Color.Black, textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                        }
                        Divider(color = Color(0xFFF1F5F9), thickness = 1.dp)
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Summary Block
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left terms note
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("NOTES / TERMS:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (invoice.notes.isNotBlank()) invoice.notes else "No terms or notes provided.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray
                            )
                        }
                        // Right breakdown totals
                        Column(modifier = Modifier.weight(0.8f), horizontalAlignment = Alignment.End) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                  Text("Subtotal:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                  Text(formatCurrency(invoice.subtotal, invoice.currency), style = MaterialTheme.typography.bodySmall, color = Color.Black)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                  Text("Tax Amount:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                  Text(formatCurrency(invoice.taxTotal, invoice.currency), style = MaterialTheme.typography.bodySmall, color = Color.Black)
                            }
                            if (invoice.discountPercent > 0.0) {
                                  Spacer(modifier = Modifier.height(4.dp))
                                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                      Text("Discount (${invoice.discountPercent}%):", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                      Text("-" + formatCurrency(invoice.subtotal * (invoice.discountPercent / 100.0), invoice.currency), style = MaterialTheme.typography.bodySmall, color = StatusOverdue)
                                  }
                            }
                            if (invoice.shippingCharge > 0.0) {
                                  Spacer(modifier = Modifier.height(4.dp))
                                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                      Text("Shipping:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                      Text(formatCurrency(invoice.shippingCharge, invoice.currency), style = MaterialTheme.typography.bodySmall, color = Color.Black)
                                  }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Divider(color = Color.Black, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                  Text("GRAND TOTAL:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                                  Text(formatCurrency(invoice.total, invoice.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = AppPrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(40.dp))

                    // Signature block
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
                            Spacer(modifier = Modifier.height(20.dp))
                            Divider(color = Color.LightGray, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Authorized By", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
                            Spacer(modifier = Modifier.height(20.dp))
                            Divider(color = Color.LightGray, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Client Signature", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "INTEGRATION & SHARING CHANNELS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // PDF Share and WhatsApp Share Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.navigateTo(Screen.PdfPreview(invoice.id))
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = "PDF")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Print/Share PDF", fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val pdfFile = InvoicePdfService.generateInvoicePdf(context, invoiceWithItems)
                        IntegrationServices.sendInvoiceToWhatsApp(context, invoiceWithItems, pdfFile)
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)), // WhatsApp Green
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "WhatsApp")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("WhatsApp Send", fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Email and Google Sheets Sync Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val pdfFile = InvoicePdfService.generateInvoicePdf(context, invoiceWithItems)
                        IntegrationServices.sendInvoiceEmail(context, invoiceWithItems, pdfFile)
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Email, contentDescription = "Email")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Email Invoice", fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        IntegrationServices.syncToGoogleSheets(
                            context,
                            invoiceWithItems,
                            settingsManager.googleSheetsId
                        ) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (settingsManager.isGoogleSheetsEnabled) Color(0xFF107C41) else Color.Gray // Sheet Green
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = settingsManager.isGoogleSheetsEnabled
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = "Google Sheets")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sheets Sync", fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Text share action (retained as classic text plain copy)
            OutlinedButton(
                onClick = {
                    val rawReceiptText = buildString {
                        appendLine("========= CEYVANA INVOICE =========")
                        appendLine("Invoice No: ${invoice.invoiceNumber}")
                        appendLine("Client: ${invoice.clientName}")
                        appendLine("Date: ${sdf.format(Date(invoice.issueDate))}")
                        appendLine("Due Date: ${sdf.format(Date(invoice.dueDate))}")
                        appendLine("-----------------------------------")
                        items.forEach { item ->
                            appendLine("${item.name} x${item.quantity} -> ${formatCurrency(item.total, invoice.currency)}")
                        }
                        appendLine("-----------------------------------")
                        appendLine("Subtotal: ${formatCurrency(invoice.subtotal, invoice.currency)}")
                        appendLine("Tax Total: ${formatCurrency(invoice.taxTotal, invoice.currency)}")
                        if (invoice.discountPercent > 0.0) {
                            appendLine("Discount: -%.1f%%".format(invoice.discountPercent))
                        }
                        if (invoice.shippingCharge > 0.0) {
                            appendLine("Shipping: ${formatCurrency(invoice.shippingCharge, invoice.currency)}")
                        }
                        appendLine("GRAND TOTAL: ${formatCurrency(invoice.total, invoice.currency)}")
                        appendLine("Status: ${invoice.status}")
                        appendLine("===================================")
                    }
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Ceyvana Invoice ${invoice.invoiceNumber}")
                        putExtra(android.content.Intent.EXTRA_TEXT, rawReceiptText)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share Invoice Details"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(Icons.Filled.Share, contentDescription = "Share text")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Plain Text Receipt")
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// --- Add/Edit Invoice Screen ---
@Composable
fun AddEditInvoiceScreen(
    viewModel: InvoiceViewModel,
    invoiceId: Long?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val invoices by viewModel.invoices.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()

    val editTarget = remember(invoices, invoiceId) {
        if (invoiceId != null) invoices.find { it.invoice.id == invoiceId } else null
    }

    // Determine the next sequential invoice number based on existing invoices (e.g. CV-2026-00001)
    val nextInvoiceNumber = remember(invoices) {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val prefix = "CV-$currentYear-"
        val pattern = Regex("CV-$currentYear-(\\d+)")
        val maxNumber = invoices.mapNotNull {
            val match = pattern.find(it.invoice.invoiceNumber)
            match?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        "$prefix%05d".format(maxNumber + 1)
    }

    var hasUserEditedInvoiceNumber by remember { mutableStateOf(false) }
    var invoiceNumber by remember { mutableStateOf(editTarget?.invoice?.invoiceNumber ?: "") }

    LaunchedEffect(nextInvoiceNumber, editTarget) {
        if (editTarget == null && !hasUserEditedInvoiceNumber) {
            invoiceNumber = nextInvoiceNumber
        }
    }

    // Form states
    var issueDate by remember { mutableStateOf(editTarget?.invoice?.issueDate ?: System.currentTimeMillis()) }
    var dueDate by remember { mutableStateOf(editTarget?.invoice?.dueDate ?: (System.currentTimeMillis() + 14 * 24 * 60 * 60 * 1000L)) }
    var notes by remember { mutableStateOf(editTarget?.invoice?.notes ?: "") }
    var discountPercent by remember { mutableStateOf(editTarget?.invoice?.discountPercent?.toString() ?: "0") }
    var status by remember { mutableStateOf(editTarget?.invoice?.status ?: "Draft") }
    var shippingCharge by remember { mutableStateOf(editTarget?.invoice?.shippingCharge?.toString() ?: "0") }
    var currency by remember { mutableStateOf(editTarget?.invoice?.currency ?: "LKR") }
    var paymentMethod by remember { mutableStateOf(editTarget?.invoice?.paymentMethod ?: "Bank Transfer") }

    // Client States
    var selectedClientName by remember { mutableStateOf(editTarget?.invoice?.clientName ?: "") }
    var selectedClientEmail by remember { mutableStateOf(editTarget?.invoice?.clientEmail ?: "") }
    var selectedClientPhone by remember { mutableStateOf(editTarget?.invoice?.clientPhone ?: "") }
    var selectedClientAddress by remember { mutableStateOf(editTarget?.invoice?.clientAddress ?: "") }

    // Line Items State
    val lineItems = remember {
        mutableStateListOf<InvoiceItemEntity>()
    }

    // In-memory interactive line items (corresponds to Step 5-7)
    val items = remember {
        mutableStateListOf<ItemRow>().apply {
            if (editTarget != null) {
                editTarget.items.forEach { item ->
                    add(ItemRow().apply {
                        description = item.name
                        qty = if (item.quantity % 1.0 == 0.0) "%.0f".format(item.quantity) else item.quantity.toString()
                        price = "%.2f".format(item.price)
                    })
                }
            } else {
                add(ItemRow())
            }
        }
    }

    fun addItem() {
        items.add(ItemRow())
    }

    fun removeItem(index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
        }
    }

    // Modal Add Dialogs
    var showClientDialog by remember { mutableStateOf(false) }
    var showProductDialog by remember { mutableStateOf(false) }

    // Dropdown States
    var clientDropdownExpanded by remember { mutableStateOf(false) }

    // Dynamic calculations
    val subtotal = lineItems.sumOf { it.price * it.quantity } + items.sumOf { (it.price.toDoubleOrNull() ?: 0.0) * (it.qty.toDoubleOrNull() ?: 0.0) }
    val taxTotal = lineItems.sumOf { (it.price * it.quantity) * (it.taxPercent / 100.0) }
    val discountVal = (discountPercent.toDoubleOrNull() ?: 0.0)
    val shippingVal = (shippingCharge.toDoubleOrNull() ?: 0.0)
    val total = (subtotal + taxTotal) * (1.0 - (discountVal / 100.0)) + shippingVal

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(if (editTarget == null) "New Invoice" else "Edit Invoice") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // General Info Header Section
            OutlinedTextField(
                value = invoiceNumber,
                onValueChange = { 
                    invoiceNumber = it
                    hasUserEditedInvoiceNumber = true
                },
                label = { Text("Invoice Number") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_invoice_number"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Issue Date / Due Date Buttons (Simple selectors for now, simulated text updates)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        // Fast select helper
                        issueDate = System.currentTimeMillis()
                        Toast.makeText(context, "Issue date set to today", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1.0f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Issued Today", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        // Fast select helper: Net 14
                        dueDate = issueDate + 14 * 24 * 60 * 60 * 1000L
                        Toast.makeText(context, "Due date set to Net 14", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1.0f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Event, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Net 14 Days", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Currency & Payment Method Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Currency Selector Column
                Column(modifier = Modifier.weight(1f)) {
                    Text("Currency", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("LKR", "USD").forEach { curr ->
                            val isSelected = currency == curr
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { currency = curr }
                            ) {
                                Text(
                                    text = curr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Payment Method Selector Column
                Column(modifier = Modifier.weight(1f)) {
                    Text("Payment Method", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    var payExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { payExpanded = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(paymentMethod, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(
                            expanded = payExpanded,
                            onDismissRequest = { payExpanded = false }
                        ) {
                            listOf("Bank Transfer", "Cash", "Cheque", "Online Payment", "Letter of Credit").forEach { method ->
                                DropdownMenuItem(
                                    text = { Text(method) },
                                    onClick = {
                                        paymentMethod = method
                                        payExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Client Block Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Client Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selectedClientName.isNotBlank() && clients.none { it.name.trim().equals(selectedClientName.trim(), ignoreCase = true) }) {
                        TextButton(
                            onClick = {
                                viewModel.saveClient(
                                    ClientEntity(
                                        name = selectedClientName.trim(),
                                        email = selectedClientEmail.trim(),
                                        phone = selectedClientPhone.trim(),
                                        address = selectedClientAddress.trim()
                                    )
                                )
                                Toast.makeText(context, "Saved to Directory!", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Client", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Box {
                        Button(
                            onClick = { clientDropdownExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp).testTag("btn_select_client")
                        ) {
                            Text("Select Existing", style = MaterialTheme.typography.bodySmall)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }

                    DropdownMenu(
                        expanded = clientDropdownExpanded,
                        onDismissRequest = { clientDropdownExpanded = false }
                    ) {
                        if (clients.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No saved clients. Tap Add below.") },
                                onClick = { clientDropdownExpanded = false }
                            )
                        } else {
                            clients.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    onClick = {
                                        selectedClientName = c.name
                                        selectedClientEmail = c.email
                                        selectedClientPhone = c.phone
                                        selectedClientAddress = c.address
                                        clientDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = selectedClientName,
                        onValueChange = { selectedClientName = it },
                        label = { Text("Customer Name") },
                        modifier = Modifier.fillMaxWidth().testTag("input_client_name"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = selectedClientPhone,
                        onValueChange = { selectedClientPhone = it },
                        label = { Text("Phone") },
                        modifier = Modifier.fillMaxWidth().testTag("input_client_phone"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = selectedClientAddress,
                        onValueChange = { selectedClientAddress = it },
                        label = { Text("Address") },
                        modifier = Modifier.fillMaxWidth().testTag("input_client_address"),
                        maxLines = 2
                    )
                    OutlinedTextField(
                        value = selectedClientEmail,
                        onValueChange = { selectedClientEmail = it },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth().testTag("input_client_email"),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Line Items header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Line Items", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showProductDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp).testTag("btn_add_catalog_item")
                    ) {
                        Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Catalog", style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = { addItem() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp).testTag("btn_add_item")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Item", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Barcode Scan/Search Field
            var barcodeInput by remember { mutableStateOf("") }
            val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

            OutlinedTextField(
                value = barcodeInput,
                onValueChange = { newValue ->
                    barcodeInput = newValue
                    val matched = products.find { prod ->
                        prod.barcode?.trim() == newValue.trim() || prod.sku.trim().equals(newValue.trim(), ignoreCase = true)
                    }
                    if (matched != null) {
                        val convertedPrice = if (currency == "USD") matched.price / 300.0 else matched.price
                        val existingEmpty = items.find { it.description.isBlank() }
                        if (existingEmpty != null) {
                            existingEmpty.description = matched.name
                            existingEmpty.price = "%.2f".format(convertedPrice)
                            existingEmpty.qty = "1"
                        } else {
                            items.add(ItemRow().apply {
                                description = matched.name
                                qty = "1"
                                price = "%.2f".format(convertedPrice)
                            })
                        }
                        barcodeInput = ""
                        keyboardController?.hide()
                        Toast.makeText(context, "Added: ${matched.name}", Toast.LENGTH_SHORT).show()
                    }
                },
                placeholder = { Text("Scan/Type Barcode or SKU to add instantly...", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = {
                    if (barcodeInput.isNotEmpty()) {
                        IconButton(onClick = { barcodeInput = "" }) {
                            Icon(Icons.Filled.Clear, null)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("barcode_quick_input"),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (lineItems.isEmpty() && items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No items added yet. Tap Add Item or Catalog above.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            } else {
                if (lineItems.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lineItems.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val matchedProduct = products.find { prod ->
                                    prod.name.trim().equals(item.name.trim(), ignoreCase = true)
                                }
                                if (matchedProduct != null) {
                                    ProductImageThumbnail(
                                        imagePath = matchedProduct.imagePath,
                                        category = matchedProduct.category,
                                        name = matchedProduct.name,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Column(modifier = Modifier.weight(1.0f)) {
                                    Text(item.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Text("Qty: ${item.quantity}", style = MaterialTheme.typography.bodySmall)
                                        Text("Price: $%.2f".format(item.price), style = MaterialTheme.typography.bodySmall)
                                        if (item.taxPercent > 0.0) {
                                            Text("Tax: ${item.taxPercent}%", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                Text(
                                    "$%.2f".format(item.total),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                                IconButton(onClick = { lineItems.removeAt(index) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                if (items.isNotEmpty()) {
                    if (lineItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Text("Custom Item Rows", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items.forEachIndexed { index, item ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("Row #${index + 1}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                            val matchedProduct = products.find { prod ->
                                                prod.name.trim().equals(item.description.trim(), ignoreCase = true)
                                            }
                                            if (matchedProduct != null) {
                                                ProductImageThumbnail(
                                                    imagePath = matchedProduct.imagePath,
                                                    category = matchedProduct.category,
                                                    name = matchedProduct.name,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { removeItem(index) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Remove Row", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    OutlinedTextField(
                                        value = item.description,
                                        onValueChange = { item.description = it },
                                        label = { Text("Description") },
                                        modifier = Modifier.fillMaxWidth().testTag("input_row_desc_$index"),
                                        singleLine = true
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = item.qty,
                                            onValueChange = { item.qty = it },
                                            label = { Text("Qty") },
                                            modifier = Modifier.weight(1.0f).testTag("input_row_qty_$index"),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = item.price,
                                            onValueChange = { item.price = it },
                                            label = { Text("Price") },
                                            modifier = Modifier.weight(1.2f).testTag("input_row_price_$index"),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true
                                        )
                                        Box(
                                            modifier = Modifier
                                                .weight(1.0f)
                                                .height(56.dp)
                                                .align(Alignment.CenterVertically),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            val rowQty = item.qty.toDoubleOrNull() ?: 1.0
                                            val rowPrice = item.price.toDoubleOrNull() ?: 0.0
                                            Text(
                                                "$%.2f".format(rowQty * rowPrice),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Financial Breakdown and Discount Form
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Subtotal", style = MaterialTheme.typography.bodyLarge)
                        Text(formatCurrency(subtotal, currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Taxes", style = MaterialTheme.typography.bodyLarge)
                        Text(formatCurrency(taxTotal, currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Discount %", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1.0f))
                        OutlinedTextField(
                            value = discountPercent,
                            onValueChange = { discountPercent = it },
                            modifier = Modifier
                                .width(80.dp)
                                .height(54.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Shipping Charge", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1.0f))
                        OutlinedTextField(
                            value = shippingCharge,
                            onValueChange = { shippingCharge = it },
                            modifier = Modifier
                                .width(120.dp)
                                .height(54.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            placeholder = { Text("0") }
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("total_row"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Total",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            formatCurrency(total, currency),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("total_value")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Terms and note
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Terms / Conditions / Note") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status select Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Status:", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                listOf("Draft", "Sent", "Paid", "Overdue").forEach { s ->
                    val isSelected = status == s
                    Button(
                        onClick = { status = s },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(s, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            // Actions Block
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .weight(1.0f)
                        .height(54.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        if (selectedClientName.isBlank()) {
                            Toast.makeText(context, "Please provide a Client Name", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (lineItems.isEmpty() && items.isEmpty()) {
                            Toast.makeText(context, "Please add at least one line item", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val allItems = lineItems.toList() + items.map {
                            InvoiceItemEntity(
                                invoiceId = editTarget?.invoice?.id ?: 0L,
                                name = it.description.ifBlank { "Custom Item" },
                                quantity = it.qty.toDoubleOrNull() ?: 1.0,
                                price = it.price.toDoubleOrNull() ?: 0.0,
                                taxPercent = 0.0,
                                total = (it.qty.toDoubleOrNull() ?: 1.0) * (it.price.toDoubleOrNull() ?: 0.0)
                            )
                        }
                        val invoiceToSave = InvoiceEntity(
                            id = editTarget?.invoice?.id ?: 0L,
                            invoiceNumber = invoiceNumber,
                            issueDate = issueDate,
                            dueDate = dueDate,
                            clientName = selectedClientName,
                            clientEmail = selectedClientEmail,
                            clientPhone = selectedClientPhone,
                            clientAddress = selectedClientAddress,
                            status = status,
                            notes = notes,
                            discountPercent = discountPercent.toDoubleOrNull() ?: 0.0,
                            shippingCharge = shippingCharge.toDoubleOrNull() ?: 0.0,
                            currency = currency,
                            paymentMethod = paymentMethod,
                            subtotal = subtotal,
                            taxTotal = taxTotal,
                            total = total
                        )
                        viewModel.saveInvoice(invoiceToSave, allItems) { generatedId ->
                            val finalInvoice = if (invoiceToSave.id == 0L) invoiceToSave.copy(id = generatedId) else invoiceToSave
                            val finalItems = allItems.map { if (it.invoiceId == 0L) it.copy(invoiceId = generatedId) else it }
                            val savedInvoiceWithItems = com.example.data.model.InvoiceWithItems(finalInvoice, finalItems)

                            // 1. Generate PDF
                            try {
                                com.example.services.InvoicePdfService.generateInvoicePdf(context, savedInvoiceWithItems)
                            } catch (e: java.lang.Exception) {
                                e.printStackTrace()
                            }

                            // 2. Sync to Google Sheets if enabled
                            val settingsManager = com.example.services.SettingsManager(context)
                            if (settingsManager.isGoogleSheetsEnabled) {
                                com.example.services.IntegrationServices.syncToGoogleSheets(
                                    context,
                                    savedInvoiceWithItems,
                                    settingsManager.googleSheetsId
                                ) { success, msg ->
                                    if (success) {
                                        Toast.makeText(context, "Invoice Saved, PDF Generated & synced successfully: $msg", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Saved & PDF Generated, but Sheets sync failed: $msg", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, "Invoice Saved Successfully!", Toast.LENGTH_SHORT).show()
                            }

                            viewModel.navigateTo(Screen.InvoiceDetail(generatedId))
                        }
                    },
                    modifier = Modifier
                        .weight(1.0f)
                        .height(54.dp)
                        .testTag("btn_save_invoice"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Invoice")
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    // Modal dialog to add catalog item directly to line items
    if (showProductDialog) {
        Dialog(onDismissRequest = { showProductDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Catalog Items", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (products.isEmpty()) {
                        Text("No catalog services/products. Configure catalog in the Bottom tab first.")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 240.dp)
                        ) {
                            items(products) { prod ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val convertedPrice = if (currency == "USD") prod.price / 300.0 else prod.price
                                            items.add(
                                                ItemRow().apply {
                                                    description = prod.name
                                                    qty = "1"
                                                    price = "%.2f".format(convertedPrice)
                                                }
                                            )
                                            showProductDialog = false
                                        }
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1.0f)) {
                                        Text(prod.name, fontWeight = FontWeight.Bold)
                                        Text("$%.2f - Tax: ${prod.taxPercent}%".format(prod.price), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(Icons.Filled.Add, contentDescription = "Add")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Custom item creator if they don't want a catalog item
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Or Add Custom Item", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    var customName by remember { mutableStateOf("") }
                    var customQty by remember { mutableStateOf("1") }
                    var customPrice by remember { mutableStateOf("") }
                    var customTax by remember { mutableStateOf("0") }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Item Description") },
                        modifier = Modifier.fillMaxWidth().testTag("input_custom_name"),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customQty,
                            onValueChange = { customQty = it },
                            label = { Text("Qty") },
                            modifier = Modifier.weight(1.0f).testTag("input_custom_qty"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = customPrice,
                            onValueChange = { customPrice = it },
                            label = { Text("Price") },
                            modifier = Modifier.weight(1.0f).testTag("input_custom_price"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = customTax,
                            onValueChange = { customTax = it },
                            label = { Text("Tax %") },
                            modifier = Modifier.weight(1.0f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showProductDialog = false }) {
                            Text("Dismiss")
                        }
                        Button(
                            onClick = {
                                val priceVal = customPrice.toDoubleOrNull() ?: 0.0
                                val qtyVal = customQty.toDoubleOrNull() ?: 1.0
                                val taxVal = customTax.toDoubleOrNull() ?: 0.0
                                if (customName.isBlank() || priceVal <= 0.0) {
                                    Toast.makeText(context, "Please enter name and valid price", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                items.add(
                                    ItemRow().apply {
                                        description = customName
                                        qty = customQty
                                        price = "%.2f".format(priceVal)
                                    }
                                )
                                showProductDialog = false
                            },
                            modifier = Modifier.testTag("btn_save_custom_item")
                        ) {
                            Text("Add Custom")
                        }
                    }
                }
            }
        }
    }
}

// --- Client List Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientListScreen(
    viewModel: InvoiceViewModel,
    onBack: () -> Unit
) {
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingClient by remember { mutableStateOf<ClientEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customers Directory", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    editingClient = null
                    showAddDialog = true 
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_add_client")
            ) {
                Icon(Icons.Filled.Add, "Add Client")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Text(
                "Client Directory",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Reuse client accounts and contact info across multiple invoices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (clients.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.People, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No clients saved yet.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(clients) { client ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, SleekBorder),
                            modifier = Modifier.fillMaxWidth().testTag("client_card_${client.id}")
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1.0f)) {
                                        Text(client.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        if (client.company.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.Business, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(client.company, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                    Row {
                                        IconButton(onClick = { 
                                            editingClient = client
                                            showAddDialog = true
                                        }) {
                                            Icon(Icons.Filled.Edit, "Edit", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        IconButton(onClick = { viewModel.deleteClient(client) }) {
                                            Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = SleekBorder.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(8.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (client.phone.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Phone, contentDescription = "Phone", modifier = Modifier.size(16.dp), tint = Color.Gray)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(client.phone, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    if (client.whatsapp.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Message, contentDescription = "WhatsApp", modifier = Modifier.size(16.dp), tint = Color(0xFF25D366))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(client.whatsapp, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    if (client.email.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Email, contentDescription = "Email", modifier = Modifier.size(16.dp), tint = Color.Gray)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(client.email, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Filled.LocationOn, contentDescription = "Billing Address", modifier = Modifier.size(16.dp), tint = Color.Gray)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text("Billing Address:", style = MaterialTheme.typography.bodySmall, color = Color.Gray, fontWeight = FontWeight.Bold)
                                            Text(client.address, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }

                                    if (client.shippingAddress.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.Top) {
                                            Icon(Icons.Filled.LocalShipping, contentDescription = "Shipping Address", modifier = Modifier.size(16.dp), tint = Color.Gray)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text("Shipping Address:", style = MaterialTheme.typography.bodySmall, color = Color.Gray, fontWeight = FontWeight.Bold)
                                                Text(client.shippingAddress, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }

                                    if (client.notes.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Icon(Icons.Filled.Info, contentDescription = "Notes", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(client.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        Dialog(onDismissRequest = { showAddDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(16.dp)
            ) {
                var name by remember { mutableStateOf(editingClient?.name ?: "") }
                var company by remember { mutableStateOf(editingClient?.company ?: "") }
                var phone by remember { mutableStateOf(editingClient?.phone ?: "") }
                var whatsapp by remember { mutableStateOf(editingClient?.whatsapp ?: "") }
                var email by remember { mutableStateOf(editingClient?.email ?: "") }
                var address by remember { mutableStateOf(editingClient?.address ?: "") }
                var shippingAddress by remember { mutableStateOf(editingClient?.shippingAddress ?: "") }
                var notes by remember { mutableStateOf(editingClient?.notes ?: "") }

                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (editingClient == null) "New Customer Profile" else "Edit Customer Profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Customer Name*") },
                        modifier = Modifier.fillMaxWidth().testTag("client_name_input")
                    )

                    OutlinedTextField(
                        value = company,
                        onValueChange = { company = it },
                        label = { Text("Company (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone*") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = whatsapp,
                        onValueChange = { whatsapp = it },
                        label = { Text("WhatsApp Number") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email Address") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Billing Address*") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = shippingAddress,
                        onValueChange = { shippingAddress = it },
                        label = { Text("Shipping Address (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showAddDialog = false }) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                if (name.isNotBlank() && phone.isNotBlank() && address.isNotBlank()) {
                                    val newClient = ClientEntity(
                                        id = editingClient?.id ?: 0L,
                                        name = name.trim(),
                                        company = company.trim(),
                                        phone = phone.trim(),
                                        whatsapp = whatsapp.trim(),
                                        email = email.trim(),
                                        address = address.trim(),
                                        shippingAddress = shippingAddress.trim(),
                                        notes = notes.trim()
                                    )
                                    viewModel.saveClient(newClient)
                                    showAddDialog = false
                                }
                            },
                            modifier = Modifier.testTag("client_save_button")
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

// --- Product List Screen ---
@Composable
fun ProductImageThumbnail(
    imagePath: String?,
    category: String,
    name: String,
    modifier: Modifier = Modifier
) {
    val initials = name.split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .let { if (it.isEmpty()) "SP" else it }

    val baseColor = when (imagePath?.lowercase() ?: name.lowercase()) {
        "pepper", "black pepper" -> Color(0xFF3E2723)
        "cinnamon" -> Color(0xFF8D6E63)
        "cloves" -> Color(0xFF5D4037)
        "cardamom" -> Color(0xFF2E7D32)
        "nutmeg" -> Color(0xFFD84315)
        "turmeric" -> Color(0xFFF9A825)
        else -> {
            when (category.lowercase()) {
                "whole spices" -> Color(0xFF8D6E63)
                "ground spices" -> Color(0xFFD84315)
                "herbs" -> Color(0xFF2E7D32)
                "spice mixes" -> Color(0xFFEF6C00)
                "tea" -> Color(0xFF00796B)
                else -> Color(0xFF546E7A)
            }
        }
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(baseColor.copy(alpha = 0.12f))
            .border(1.5.dp, baseColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = baseColor
        )
    }
}

fun generateSkuPrefix(productName: String): String {
    val cleaned = productName.trim().uppercase()
    if (cleaned.isBlank()) return "CV"
    
    if (cleaned.contains("BLACK PEPPER") || cleaned.contains("PEPPER")) return "BP"
    if (cleaned.contains("CINNAMON")) return "CN"
    if (cleaned.contains("CLOVE")) return "CL"
    
    return "CV"
}

fun generateNextSku(prefix: String, existingProducts: List<ProductEntity>): String {
    val regex = "^${prefix}-(\\d{5})$".toRegex()
    var maxNum = 0
    for (p in existingProducts) {
        val skuCode = p.sku.trim().uppercase()
        val match = regex.find(skuCode)
        if (match != null) {
            val num = match.groupValues[1].toIntOrNull() ?: 0
            if (num > maxNum) {
                maxNum = num
            }
        }
    }
    val nextNum = maxNum + 1
    return String.format(java.util.Locale.US, "%s-%05d", prefix, nextNum)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductFormDialog(
    prod: ProductEntity?,
    existingProducts: List<ProductEntity> = emptyList(),
    unitsList: List<String> = listOf("Pack", "Bottle", "Box", "Piece", "Kilogram", "Gram", "Liter"),
    onDismiss: () -> Unit,
    onSave: (ProductEntity) -> Unit
) {
    val isEdit = prod != null
    var name by remember { mutableStateOf(prod?.name ?: "") }
    var sku by remember { mutableStateOf(prod?.sku ?: "") }
    var isSkuManuallyEdited by remember { mutableStateOf(prod?.sku?.isNotBlank() == true) }
    var barcode by remember { mutableStateOf(prod?.barcode ?: "") }
    var category by remember { mutableStateOf(prod?.category ?: "Whole Spices") }
    var unit by remember { mutableStateOf(prod?.unit ?: "Packs") }
    var costPrice by remember { mutableStateOf(prod?.costPrice?.toString() ?: "") }
    var sellingPrice by remember { mutableStateOf(prod?.sellingPrice?.toString() ?: prod?.price?.toString() ?: "") }
    var stock by remember { mutableStateOf(prod?.stock?.toString() ?: "0") }
    var minStock by remember { mutableStateOf(prod?.minStock?.toString() ?: "5") }
    var taxPercent by remember { mutableStateOf(prod?.taxPercent?.toString() ?: "0") }
    var description by remember { mutableStateOf(prod?.description ?: "") }
    var imagePath by remember { mutableStateOf(prod?.imagePath ?: "") }

    val galleryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            imagePath = uri.toString()
        }
    }

    val spiceCategories = listOf("Whole Spices", "Ground Spices", "Herbs", "Spice Mixes", "Tea", "Packaging", "Export Products")

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(8.dp)
        ) {
            LazyColumn(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        text = if (isEdit) "Edit Product" else "New Product",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { newValue ->
                            name = newValue
                            if (!isEdit && !isSkuManuallyEdited) {
                                val prefix = generateSkuPrefix(newValue)
                                sku = generateNextSku(prefix, existingProducts)
                            }
                        },
                        label = { Text("Product Name *") },
                        modifier = Modifier.fillMaxWidth().testTag("product_name_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = sku,
                            onValueChange = { newValue ->
                                sku = newValue
                                isSkuManuallyEdited = true
                            },
                            label = { Text("SKU Code") },
                            modifier = Modifier.weight(1.0f).testTag("product_sku_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = barcode ?: "",
                            onValueChange = { barcode = it },
                            label = { Text("Barcode") },
                            modifier = Modifier.weight(1.0f),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Category", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(spiceCategories) { cat ->
                                val selected = category == cat
                                FilterChip(
                                    selected = selected,
                                    onClick = { category = cat },
                                    label = { Text(cat) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Unit of Measure", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(unitsList) { u ->
                                val selected = unit.equals(u, ignoreCase = true)
                                FilterChip(
                                    selected = selected,
                                    onClick = { unit = u },
                                    label = { Text(u) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text("Selected/Custom Unit") },
                            modifier = Modifier.weight(1.0f).testTag("product_unit_input"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = taxPercent,
                            onValueChange = { taxPercent = it },
                            label = { Text("Tax %") },
                            modifier = Modifier.weight(1.0f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = costPrice,
                            onValueChange = { costPrice = it },
                            label = { Text("Cost Price") },
                            modifier = Modifier.weight(1.0f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = sellingPrice,
                            onValueChange = { sellingPrice = it },
                            label = { Text("Selling Price *") },
                            modifier = Modifier.weight(1.0f).testTag("product_price_input"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = stock,
                            onValueChange = { stock = it },
                            label = { Text("Current Stock") },
                            modifier = Modifier.weight(1.0f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = minStock,
                            onValueChange = { minStock = it },
                            label = { Text("Min Stock Alert") },
                            modifier = Modifier.weight(1.0f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Product Image", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ProductImageThumbnail(
                                imagePath = imagePath,
                                category = category,
                                name = name,
                                modifier = Modifier.size(64.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = imagePath,
                                    onValueChange = { imagePath = it },
                                    label = { Text("Image (e.g. pepper, cinnamon)") },
                                    modifier = Modifier.fillMaxWidth().testTag("product_image_input"),
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { galleryLauncher.launch("image/*") },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp).testTag("btn_select_gallery")
                                    ) {
                                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Gallery", style = MaterialTheme.typography.bodySmall)
                                    }
                                    
                                    Text("Presets:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val presets = listOf("pepper", "cinnamon", "cloves", "cardamom", "nutmeg", "turmeric")
                                    items(presets) { preset ->
                                        val isSelected = imagePath.lowercase() == preset
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                                .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                                .clickable { imagePath = preset }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = preset.replaceFirstChar { it.uppercase() },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Extended Specification / Desc") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val sPrice = sellingPrice.toDoubleOrNull() ?: 0.0
                                val cPrice = costPrice.toDoubleOrNull() ?: 0.0
                                val curStock = stock.toDoubleOrNull() ?: 0.0
                                val limitStock = minStock.toDoubleOrNull() ?: 5.0
                                val tax = taxPercent.toDoubleOrNull() ?: 0.0

                                if (name.isBlank()) {
                                    return@Button
                                }
                                if (sPrice <= 0.0) {
                                    return@Button
                                }

                                val finalSku = sku.ifBlank {
                                    "SKU-" + name.filter { it.isLetterOrDigit() }.take(4).uppercase() + "-" + (100..999).random()
                                }

                                val finalProduct = ProductEntity(
                                    id = prod?.id ?: 0L,
                                    sku = finalSku,
                                    barcode = barcode.ifBlank { null },
                                    name = name,
                                    price = sPrice,
                                    description = description,
                                    taxPercent = tax,
                                    category = category,
                                    unit = unit.ifBlank { "Packs" },
                                    costPrice = cPrice,
                                    sellingPrice = sPrice,
                                    stock = curStock,
                                    minStock = limitStock,
                                    imagePath = imagePath.ifBlank { null },
                                    createdAt = prod?.createdAt ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()),
                                    updatedAt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                                )
                                onSave(finalProduct)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("product_save_button")
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductListScreen(
    viewModel: InvoiceViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val products by viewModel.products.collectAsStateWithLifecycle()
    val stockMovements by viewModel.stockMovements.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val settingsManager = remember { com.example.services.SettingsManager(context) }
    val currencySym = settingsManager.currencySymbol

    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    var showAddDialog by remember { mutableStateOf(false) }
    var productToEdit by remember { mutableStateOf<ProductEntity?>(null) }
    var productToAdjustStock by remember { mutableStateOf<ProductEntity?>(null) }

    val categoriesList = listOf("All", "Whole Spices", "Ground Spices", "Herbs", "Spice Mixes", "Tea", "Packaging", "Export Products", "Low Stock ⚠️")

    val filteredProducts = remember(products, searchQuery, selectedCategory) {
        products.filter { prod ->
            val matchesQuery = prod.name.contains(searchQuery, ignoreCase = true) ||
                    prod.sku.contains(searchQuery, ignoreCase = true) ||
                    (prod.barcode ?: "").contains(searchQuery, ignoreCase = true) ||
                    prod.category.contains(searchQuery, ignoreCase = true) ||
                    prod.description.contains(searchQuery, ignoreCase = true)

            val matchesCategory = when (selectedCategory) {
                "All" -> true
                "Low Stock ⚠️" -> prod.stock <= prod.minStock
                else -> prod.category.equals(selectedCategory, ignoreCase = true)
            }
            matchesQuery && matchesCategory
        }
    }

    val filteredMovements = remember(stockMovements, searchQuery) {
        stockMovements.filter { mov ->
            val prodName = products.find { it.id == mov.productId }?.name ?: "Unknown Product"
            prodName.contains(searchQuery, ignoreCase = true) || mov.reason.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Products", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Products & Stock", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Movement History", fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                ExtendedFloatingActionButton(
                    text = { Text("Add Product", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("fab_add_product")
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(if (selectedTab == 0) "Search Product" else "Search movements, logs...") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedTab == 0) {
                // Category Selector
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(categoriesList) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (filteredProducts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Inventory2, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No matching catalog items.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredProducts) { prod ->
                            val isLowStock = prod.stock <= prod.minStock
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, if (isLowStock) Color(0xFFD32F2F).copy(alpha = 0.6f) else SleekBorder),
                                modifier = Modifier.fillMaxWidth().testTag("product_card_${prod.id}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ProductImageThumbnail(
                                            imagePath = prod.imagePath,
                                            category = prod.category,
                                            name = prod.name,
                                            modifier = Modifier.size(52.dp)
                                        )
                                        Column(modifier = Modifier.weight(1.0f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(prod.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(prod.sku.ifBlank { "NO SKU" }, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${prod.category} • Unit: ${prod.unit}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { productToAdjustStock = prod }) {
                                                Icon(Icons.Filled.ArrowUpward, "Adjust Stock", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(onClick = { productToEdit = prod }) {
                                                Icon(Icons.Filled.Edit, "Edit", tint = MaterialTheme.colorScheme.secondary)
                                            }
                                            IconButton(onClick = { viewModel.deleteProduct(prod) }) {
                                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }

                                    if (prod.description.isNotBlank()) {
                                        Text(prod.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    }

                                    HorizontalDivider(color = SleekBorder, thickness = 0.5.dp)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Selling Price", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                            Text("$currencySym %,.2f".format(prod.sellingPrice), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Column {
                                            Text("Cost Price", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                            Text("$currencySym %,.2f".format(prod.costPrice), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("Stock On Hand", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                            Text("${prod.stock} ${prod.unit}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (isLowStock) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface)
                                        }
                                    }

                                    if (isLowStock) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFE57373).copy(alpha = 0.15f))
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Filled.Warning, null, tint = Color(0xFFD32F2F), modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Low Stock Warning: threshold is ${prod.minStock} ${prod.unit}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFD32F2F),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Stock Movements Tab
                if (filteredMovements.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No stock movements recorded.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredMovements) { mov ->
                            val associatedProduct = products.find { it.id == mov.productId }
                            val productName = associatedProduct?.name ?: "Product #${mov.productId}"
                            val productUnit = associatedProduct?.unit ?: "units"

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, SleekBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1.0f)
                                    ) {
                                        val isAddition = mov.changeAmount >= 0
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isAddition) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isAddition) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                                contentDescription = if (isAddition) "Addition" else "Reduction",
                                                tint = if (isAddition) Color(0xFF2E7D32) else Color(0xFFC62828),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(productName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                                val badgeColor = when (mov.type) {
                                                    "IN" -> Color(0xFFE8F5E9)
                                                    "OUT" -> Color(0xFFFFEBEE)
                                                    else -> Color(0xFFE3F2FD)
                                                }
                                                val badgeTextColor = when (mov.type) {
                                                    "IN" -> Color(0xFF2E7D32)
                                                    "OUT" -> Color(0xFFC62828)
                                                    else -> Color(0xFF1565C0)
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(badgeColor)
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = mov.type,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = badgeTextColor
                                                     )
                                                }
                                            }
                                            Text(
                                                text = if (mov.reference.isNotBlank()) "${mov.reason} • Ref: ${mov.reference}" else mov.reason,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                            Text(
                                                text = mov.date.ifBlank { mov.createdAt.ifBlank { "Recent" } },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        val isAddition = mov.changeAmount >= 0
                                        Text(
                                            text = "${if (isAddition) "+" else ""}${mov.changeAmount} $productUnit",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isAddition) Color(0xFF2E7D32) else Color(0xFFC62828)
                                        )
                                        Text(
                                            text = "Balance: ${mov.stockAfter} $productUnit",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ProductFormDialog(
            prod = null,
            existingProducts = products,
            unitsList = units.map { it.name },
            onDismiss = { showAddDialog = false },
            onSave = { prod ->
                viewModel.saveProduct(prod)
                if (prod.unit.isNotBlank() && !units.any { it.name.equals(prod.unit, ignoreCase = true) }) {
                    viewModel.saveUnit(prod.unit)
                }
                showAddDialog = false
            }
        )
    }

    if (productToEdit != null) {
        ProductFormDialog(
            prod = productToEdit,
            existingProducts = products,
            unitsList = units.map { it.name },
            onDismiss = { productToEdit = null },
            onSave = { prod ->
                viewModel.saveProduct(prod)
                if (prod.unit.isNotBlank() && !units.any { it.name.equals(prod.unit, ignoreCase = true) }) {
                    viewModel.saveUnit(prod.unit)
                }
                productToEdit = null
            }
        )
    }

    if (productToAdjustStock != null) {
        val prod = productToAdjustStock!!
        Dialog(onDismissRequest = { productToAdjustStock = null }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(16.dp)
            ) {
                var adjustType by remember { mutableStateOf("Add") }
                var adjustAmount by remember { mutableStateOf("") }
                var adjustReason by remember { mutableStateOf("Stock Check / Update") }

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Adjust Stock: ${prod.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Current stock: ${prod.stock} ${prod.unit}", style = MaterialTheme.typography.bodyMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { adjustType = "Add" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (adjustType == "Add") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (adjustType == "Add") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.weight(1.0f)
                        ) {
                            Text("Add Stock (+)")
                        }
                        Button(
                            onClick = { adjustType = "Reduce" },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (adjustType == "Reduce") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (adjustType == "Reduce") MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.weight(1.0f)
                        ) {
                            Text("Reduce Stock (-)")
                        }
                    }

                    OutlinedTextField(
                        value = adjustAmount,
                        onValueChange = { adjustAmount = it },
                        label = { Text("Quantity (${prod.unit})") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = adjustReason,
                        onValueChange = { adjustReason = it },
                        label = { Text("Reason / Note") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { productToAdjustStock = null }) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val amt = adjustAmount.toDoubleOrNull() ?: 0.0
                                if (amt > 0.0) {
                                    val signedAmt = if (adjustType == "Reduce") -amt else amt
                                    val newStockLevel = prod.stock + signedAmt

                                    viewModel.saveProduct(
                                        product = prod.copy(
                                            stock = newStockLevel,
                                            updatedAt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                                        ),
                                        customMovementReason = adjustReason.ifBlank { "Adjustment" }
                                    )
                                    productToAdjustStock = null
                                } else {
                                    Toast.makeText(context, "Enter a valid quantity", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("Apply")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    
    // Manage fields inside local state
    var companyName by remember { mutableStateOf(settingsManager.companyName) }
    var companyOwner by remember { mutableStateOf(settingsManager.companyOwner) }
    var companyEmail by remember { mutableStateOf(settingsManager.companyEmail) }
    var companyPhone by remember { mutableStateOf(settingsManager.companyPhone) }
    var companyPhone2 by remember { mutableStateOf(settingsManager.companyPhone2) }
    var companyWhatsapp by remember { mutableStateOf(settingsManager.companyWhatsapp) }
    var companyAddress by remember { mutableStateOf(settingsManager.companyAddress) }
    var currencySymbol by remember { mutableStateOf(settingsManager.currencySymbol) }
    var googleSheetsId by remember { mutableStateOf(settingsManager.googleSheetsId) }
    var googleScriptUrl by remember { mutableStateOf(settingsManager.googleScriptUrl) }
    var gsheetsEnabled by remember { mutableStateOf(settingsManager.isGoogleSheetsEnabled) }
    var companyLogo by remember { mutableStateOf(settingsManager.companyLogo) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Integrations", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // Header Hero Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Settings Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                CircleShape
                            )
                            .padding(10.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            "Company Profile",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Configure billing credentials, default currency, and external spreadsheets.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // 1. Business Credentials Section
            Text("BUSINESS DETAILS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = companyName,
                onValueChange = { companyName = it },
                label = { Text("Company Name") },
                leadingIcon = { Icon(Icons.Filled.Business, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("setting_company_name"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = companyOwner,
                onValueChange = { companyOwner = it },
                label = { Text("Company Owner / Slogan") },
                leadingIcon = { Icon(Icons.Filled.Badge, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("setting_company_owner"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = companyEmail,
                onValueChange = { companyEmail = it },
                label = { Text("Billing Email") },
                leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("setting_company_email"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = companyPhone,
                onValueChange = { companyPhone = it },
                label = { Text("Primary Phone") },
                leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("setting_company_phone"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = companyPhone2,
                onValueChange = { companyPhone2 = it },
                label = { Text("Secondary Phone") },
                leadingIcon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("setting_company_phone_2"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = companyWhatsapp,
                onValueChange = { companyWhatsapp = it },
                label = { Text("WhatsApp Contact") },
                leadingIcon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("setting_company_whatsapp"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = companyAddress,
                onValueChange = { companyAddress = it },
                label = { Text("Billing Address") },
                leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("setting_company_address"),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = companyLogo,
                onValueChange = { companyLogo = it },
                label = { Text("Company Logo URL or Local Path") },
                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("setting_company_logo"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("e.g. logo.png or custom URL") }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 2. Preferences
            Text("PREFERENCES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Select Default Currency Prefix:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("$", "€", "£", "¥", "Rs.").forEach { sym ->
                            val isSelected = currencySymbol == sym
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { currencySymbol = sym }
                            ) {
                                Text(
                                    text = sym,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)))
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Dark Theme Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("Switch app appearance to low-light background", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Switch(
                            checked = isDarkTheme,
                            onCheckedChange = { onThemeChange(it) },
                            modifier = Modifier.testTag("setting_theme_switch")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Integrations (Google Sheets)
            Text("GOOGLE SHEETS INTEGRATION", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic Spreadsheet Sync", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("Pushes new invoices to Google Sheets immediately", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Switch(
                            checked = gsheetsEnabled,
                            onCheckedChange = { gsheetsEnabled = it },
                            modifier = Modifier.testTag("setting_gsheets_switch")
                        )
                    }

                    if (gsheetsEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = googleSheetsId,
                            onValueChange = { googleSheetsId = it },
                            label = { Text("Google Spreadsheet ID") },
                            leadingIcon = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().testTag("setting_gsheets_id"),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = googleScriptUrl,
                            onValueChange = { googleScriptUrl = it },
                            label = { Text("Google Apps Script Web App URL") },
                            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().testTag("setting_google_script_url"),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            placeholder = { Text("https://script.google.com/macros/s/.../exec") }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Note: Enter the deployed Web App URL from Step 3 to back up invoices live to your Google Sheet.",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Info,
                                        contentDescription = "Security recommendation Info",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Secure Google Sheets Sync",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "To protect business credentials, we avoid storing Service Account JSON keys or Sheets API keys inside the compiled Android application.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Android App  ──►  Google Apps Script (Web App)  ──►  Google Sheet",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Text("• ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("No raw API credentials stored inside the APK.", style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Text("• ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("Google Apps Script acts as a secure serverless relay to append rows to your target sheet.", style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Text("• ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("Ensures high security, cross-platform ease of maintenance, and zero-cost cloud orchestration.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Save Action Button
            Button(
                onClick = {
                    settingsManager.companyName = companyName
                    settingsManager.companyOwner = companyOwner
                    settingsManager.companyEmail = companyEmail
                    settingsManager.companyPhone = companyPhone
                    settingsManager.companyPhone2 = companyPhone2
                    settingsManager.companyWhatsapp = companyWhatsapp
                    settingsManager.companyAddress = companyAddress
                    settingsManager.currencySymbol = currencySymbol
                    settingsManager.googleSheetsId = googleSheetsId
                    settingsManager.googleScriptUrl = googleScriptUrl
                    settingsManager.isGoogleSheetsEnabled = gsheetsEnabled
                    settingsManager.companyLogo = companyLogo

                    Toast.makeText(context, "Settings saved successfully!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("settings_save_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = "Save")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Settings Profile", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: InvoiceViewModel,
    settingsManager: SettingsManager
) {
    val context = LocalContext.current
    val invoices by viewModel.invoices.collectAsStateWithLifecycle()
    val clientsList by viewModel.clients.collectAsStateWithLifecycle()

    // Calculations for metrics
    val totalPending = invoices.filter { !it.invoice.status.equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }

    // Calculate Today's Sales, Monthly Sales from SQLite data
    val todayCal = Calendar.getInstance()
    val todayYear = todayCal.get(Calendar.YEAR)
    val todayDayOfYear = todayCal.get(Calendar.DAY_OF_YEAR)
    val todayMonth = todayCal.get(Calendar.MONTH)

    val todayInvoices = invoices.filter {
        val cal = Calendar.getInstance().apply { timeInMillis = it.invoice.issueDate }
        cal.get(Calendar.YEAR) == todayYear && cal.get(Calendar.DAY_OF_YEAR) == todayDayOfYear
    }
    val todaySales = todayInvoices.sumOf { it.invoice.total }
    val invoicesTodayCount = todayInvoices.size

    val monthlySales = invoices.filter {
        val cal = Calendar.getInstance().apply { timeInMillis = it.invoice.issueDate }
        cal.get(Calendar.YEAR) == todayYear && cal.get(Calendar.MONTH) == todayMonth
    }.sumOf { it.invoice.total }

    val pendingSyncCount = invoices.filter { it.invoice.status.equals("Draft", ignoreCase = true) }.size

    val totalCustomersCount = clientsList.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Spa,
                            contentDescription = "Ceyvana Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ceyvana Premium Spices",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Hero Brand Image and Card overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                // Banner image
                Image(
                    painter = painterResource(id = R.drawable.img_invoice_banner_1783481270227),
                    contentDescription = "Ceyvana Spices Banner",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Overlaid soft shadow gradient to make text stand out
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                            )
                        )
                )
            }

            // Brand details Card overlapping cleanly below banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .offset(y = (-30).dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Logo Image
                    Image(
                        painter = painterResource(id = R.drawable.img_ceyvana_logo_1783697111340),
                        contentDescription = "Ceyvana Logo",
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = Company.NAME,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = Company.OWNER,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    Spacer(modifier = Modifier.height(16.dp))

                    // Quick metadata details row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ContactChip(
                            icon = Icons.Default.Chat,
                            label = "WhatsApp",
                            color = Color(0xFF25D366),
                            onClick = {
                                Toast.makeText(context, "Support: ${Company.WHATSAPP}", Toast.LENGTH_SHORT).show()
                            }
                        )
                        ContactChip(
                            icon = Icons.Default.Phone,
                            label = "Call Us",
                            color = MaterialTheme.colorScheme.secondary,
                            onClick = {
                                Toast.makeText(context, "Direct Line: ${Company.PHONE1}", Toast.LENGTH_SHORT).show()
                            }
                        )
                        ContactChip(
                            icon = Icons.Default.Email,
                            label = "Email",
                            color = MaterialTheme.colorScheme.tertiary,
                            onClick = {
                                Toast.makeText(context, "Email: ${Company.EMAIL}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // Quick Sales Metrics Section Title
            Text(
                text = "SALES & PERFORMANCE OVERVIEW",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .offset(y = (-15).dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // KPI Metrics Row/Grid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .offset(y = (-15).dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricGridCard(
                        title = "Today's Sales",
                        value = "Rs. %,.0f".format(todaySales),
                        icon = Icons.Filled.Today,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricGridCard(
                        title = "Invoices Today",
                        value = "$invoicesTodayCount",
                        icon = Icons.Filled.Receipt,
                        color = Color(0xFFEAB308),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricGridCard(
                        title = "Monthly Sales",
                        value = "Rs. %,.0f".format(monthlySales),
                        icon = Icons.Filled.DateRange,
                        color = Color(0xFF107C41),
                        modifier = Modifier.weight(1f)
                    )
                    MetricGridCard(
                        title = "Pending Sync",
                        value = "$pendingSyncCount",
                        icon = Icons.Filled.Sync,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Billing Operations Hub
            Text(
                text = "BILLING & OPERATIONS HUB",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .offset(y = (-15).dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Navigation Options
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .offset(y = (-15).dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Action 1: Create New Invoice
                DashboardActionRow(
                    title = "+ New Invoice",
                    subtitle = "Generate a professional invoice PDF",
                    icon = Icons.Default.AddCircle,
                    iconColor = MaterialTheme.colorScheme.primary,
                    onClick = { viewModel.navigateTo(Screen.AddEditInvoice(null)) },
                    testTag = "home_new_invoice_button"
                )

                // Action 2: Customers List
                DashboardActionRow(
                    title = "Customers",
                    subtitle = "Manage clients directory and details",
                    icon = Icons.Filled.People,
                    iconColor = MaterialTheme.colorScheme.tertiary,
                    onClick = { viewModel.navigateTo(Screen.ClientList) },
                    testTag = "home_customers_button"
                )

                // Action 3: Invoice History
                DashboardActionRow(
                    title = "Invoice History",
                    subtitle = "Track and filter past billing logs",
                    icon = Icons.Default.History,
                    iconColor = MaterialTheme.colorScheme.secondary,
                    onClick = { viewModel.navigateTo(Screen.InvoiceHistory) },
                    testTag = "home_history_button"
                )

                // Action 4: Reports
                DashboardActionRow(
                    title = "Reports",
                    subtitle = "View detailed business performance charts",
                    icon = Icons.Filled.BarChart,
                    iconColor = Color(0xFF107C41),
                    onClick = { viewModel.navigateTo(Screen.Reports) },
                    testTag = "home_reports_button"
                )

                // Action 5: Settings
                DashboardActionRow(
                    title = "Settings",
                    subtitle = "Customize company profile and sheets",
                    icon = Icons.Default.Settings,
                    iconColor = Color.Gray,
                    onClick = { viewModel.navigateTo(Screen.Settings) },
                    testTag = "home_settings_button"
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun MetricGridCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DashboardActionRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "Navigate",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        onSplashFinished()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Company Logo
            Image(
                painter = painterResource(id = R.drawable.img_app_icon_1783481283102_1783485421993),
                contentDescription = "Ceyvana Logo",
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Ceyvana",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Premium Spices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(48.dp))
            // Loading Animation
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: InvoiceViewModel,
    onBack: () -> Unit
) {
    val invoices by viewModel.invoices.collectAsStateWithLifecycle()
    var selectedFilter by remember { mutableStateOf("All") }

    val filteredInvoices = remember(invoices, selectedFilter) {
        val now = System.currentTimeMillis()
        val dayInMs = 24 * 60 * 60 * 1000L
        when (selectedFilter) {
            "Today" -> invoices.filter { it.invoice.issueDate >= now - dayInMs }
            "This Week" -> invoices.filter { it.invoice.issueDate >= now - 7 * dayInMs }
            "This Month" -> invoices.filter { it.invoice.issueDate >= now - 30 * dayInMs }
            "This Year" -> invoices.filter { it.invoice.issueDate >= now - 365 * dayInMs }
            else -> invoices
        }
    }

    // Calculations
    val totalRevenue = filteredInvoices.filter { it.invoice.status.equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
    val totalOutstanding = filteredInvoices.filter { !it.invoice.status.equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
    val totalBilled = filteredInvoices.sumOf { it.invoice.total }

    val statusCounts = filteredInvoices.groupBy { it.invoice.status }.mapValues { it.value.size }
    val paidCount = statusCounts["Paid"] ?: 0
    val sentCount = statusCounts["Sent"] ?: 0
    val draftCount = statusCounts["Draft"] ?: 0
    val overdueCount = statusCounts["Overdue"] ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Business Reports", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Time period Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("All", "Today", "Week", "Month", "Year").forEach { filter ->
                    val isSelected = (filter == "All" && selectedFilter == "All") ||
                                     (filter == "Today" && selectedFilter == "Today") ||
                                     (filter == "Week" && selectedFilter == "This Week") ||
                                     (filter == "Month" && selectedFilter == "This Month") ||
                                     (filter == "Year" && selectedFilter == "This Year")
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable {
                                selectedFilter = when (filter) {
                                    "Week" -> "This Week"
                                    "Month" -> "This Month"
                                    "Year" -> "This Year"
                                    else -> filter
                                }
                            }
                    ) {
                        Text(
                            text = filter,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Total Billing Value",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "Rs. %,.2f".format(totalBilled),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "REVENUE RECEIVED",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "Rs. %,.2f".format(totalRevenue),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF107C41)
                            )
                        }
                        Column {
                            Text(
                                text = "OUTSTANDING BALANCE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "Rs. %,.2f".format(totalOutstanding),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Invoices Status Breakdown Chart/Progress Indicators
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Invoices Status Breakdown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val totalCount = filteredInvoices.size.toFloat().coerceAtLeast(1f)

                    ReportProgressBar(
                        label = "Paid (${paidCount})",
                        percentage = paidCount / totalCount,
                        color = Color(0xFF107C41),
                        amount = filteredInvoices.filter { it.invoice.status.equals("Paid", ignoreCase = true) }.sumOf { it.invoice.total }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ReportProgressBar(
                        label = "Sent (${sentCount})",
                        percentage = sentCount / totalCount,
                        color = MaterialTheme.colorScheme.primary,
                        amount = filteredInvoices.filter { it.invoice.status.equals("Sent", ignoreCase = true) }.sumOf { it.invoice.total }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ReportProgressBar(
                        label = "Overdue (${overdueCount})",
                        percentage = overdueCount / totalCount,
                        color = MaterialTheme.colorScheme.error,
                        amount = filteredInvoices.filter { it.invoice.status.equals("Overdue", ignoreCase = true) }.sumOf { it.invoice.total }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ReportProgressBar(
                        label = "Draft (${draftCount})",
                        percentage = draftCount / totalCount,
                        color = Color.Gray,
                        amount = filteredInvoices.filter { it.invoice.status.equals("Draft", ignoreCase = true) }.sumOf { it.invoice.total }
                    )
                }
            }

            // Client Statistics List
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Top Clients by Revenue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val clientsWithTotals = filteredInvoices.groupBy { it.invoice.clientName }
                        .mapValues { entry -> entry.value.sumOf { it.invoice.total } }
                        .toList()
                        .sortedByDescending { it.second }
                        .take(5)

                    if (clientsWithTotals.isEmpty()) {
                        Text(
                            text = "No client billing data available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        clientsWithTotals.forEachIndexed { index, (clientName, totalValue) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(
                                        text = clientName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "Rs. %,.2f".format(totalValue),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (index < clientsWithTotals.size - 1) {
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }

            // Top Products selling
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Top Products Cataloged",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Group invoice item entities by description/name
                    val productTotals = filteredInvoices.flatMap { it.items }
                        .groupBy { it.name }
                        .mapValues { entry -> entry.value.sumOf { it.total } }
                        .toList()
                        .sortedByDescending { it.second }
                        .take(5)

                    if (productTotals.isEmpty()) {
                        Text(
                            text = "No product sales data available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        productTotals.forEachIndexed { index, (prodName, totalValue) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Inventory2,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = prodName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "Rs. %,.2f".format(totalValue),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            if (index < productTotals.size - 1) {
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun ReportProgressBar(
    label: String,
    percentage: Float,
    color: Color,
    amount: Double
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Rs. %,.2f".format(amount),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = percentage,
            color = color,
            trackColor = color.copy(alpha = 0.15f),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun ContactChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(8.dp)),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// Helper class for local interactive line items in adding/editing invoice
class ItemRow {
    var description by mutableStateOf("")
    var qty by mutableStateOf("1")
    var price by mutableStateOf("0")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceHistoryScreen(
    viewModel: InvoiceViewModel,
    onBack: () -> Unit,
    onViewInvoice: (Long) -> Unit
) {
    val invoices by viewModel.invoices.collectAsStateWithLifecycle()
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredInvoices = remember(invoices, searchQuery) {
        if (searchQuery.isBlank()) {
            invoices
        } else {
            val query = searchQuery.trim().lowercase()
            invoices.filter { item ->
                item.invoice.invoiceNumber.lowercase().contains(query) ||
                item.invoice.clientName.lowercase().contains(query) ||
                item.invoice.clientPhone.lowercase().contains(query)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invoice History", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("history_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    if (invoices.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                IntegrationServices.shareInvoicesCsv(context, invoices)
                            },
                            modifier = Modifier.testTag("history_export_csv_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "Export CSV"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (invoices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = "No Invoices",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No invoices found.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search invoice...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search"
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("history_search_input"),
                    shape = RoundedCornerShape(28.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                if (filteredInvoices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "No results",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No matching invoices found.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .testTag("history_list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredInvoices) { invoiceWithItems ->
                            val invoice = invoiceWithItems.invoice
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onViewInvoice(invoice.id) }
                                    .testTag("history_item_card_${invoice.id}"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(1.dp, SleekBorder),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = invoice.invoiceNumber,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.testTag("history_item_number_${invoice.id}")
                                        )
                                        Text(
                                            text = formatCurrency(invoice.total, invoice.currency),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.testTag("history_item_total_${invoice.id}")
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = invoice.clientName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                            modifier = Modifier.testTag("history_item_client_${invoice.id}")
                                        )
                                        Text(
                                            text = sdf.format(Date(invoice.issueDate)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            modifier = Modifier.testTag("history_item_date_${invoice.id}")
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

// --- PDF Renderer and Printing Helpers ---
suspend fun renderPdfPages(file: File): List<android.graphics.Bitmap> = withContext(Dispatchers.IO) {
    val bitmaps = mutableListOf<android.graphics.Bitmap>()
    try {
        val input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(input)
        val pageCount = renderer.pageCount
        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            val scale = 2f
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmaps.add(bitmap)
            page.close()
        }
        renderer.close()
        input.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    bitmaps
}

fun printPdf(context: android.content.Context, pdfFile: File, jobName: String) {
    val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as? PrintManager
    if (printManager == null) {
        Toast.makeText(context, "Printing not supported on this device", Toast.LENGTH_SHORT).show()
        return
    }
    
    val printAdapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(jobName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            var input: FileInputStream? = null
            var output: FileOutputStream? = null
            try {
                input = FileInputStream(pdfFile)
                output = FileOutputStream(destination?.fileDescriptor)
                val buf = ByteArray(1024)
                var bytesRead: Int
                while (input.read(buf).also { bytesRead = it } > 0) {
                    output.write(buf, 0, bytesRead)
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.message)
            } finally {
                input?.close()
                output?.close()
            }
        }
    }
    printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewScreen(
    viewModel: InvoiceViewModel,
    invoiceId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val invoices by viewModel.invoices.collectAsStateWithLifecycle()
    val invoiceWithItems = remember(invoices, invoiceId) {
        invoices.find { it.invoice.id == invoiceId }
    }

    var pages by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var pdfFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(invoiceWithItems) {
        if (invoiceWithItems != null) {
            isLoading = true
            hasError = false
            try {
                val generatedFile = withContext(Dispatchers.IO) {
                    InvoicePdfService.generateInvoicePdf(context, invoiceWithItems)
                }
                if (generatedFile != null && generatedFile.exists()) {
                    pdfFile = generatedFile
                    val rendered = renderPdfPages(generatedFile)
                    if (rendered.isNotEmpty()) {
                        pages = rendered
                    } else {
                        hasError = true
                    }
                } else {
                    hasError = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                hasError = true
            } finally {
                isLoading = false
            }
        } else {
            isLoading = false
            hasError = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = invoiceWithItems?.invoice?.invoiceNumber?.let { "Invoice #$it" } ?: "PDF Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (pages.isNotEmpty()) {
                            Text(
                                text = "${pages.size} ${if (pages.size == 1) "page" else "pages"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("pdf_preview_back")) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (pdfFile != null && invoiceWithItems != null) {
                        IconButton(
                            onClick = {
                                printPdf(context, pdfFile!!, "Invoice-${invoiceWithItems!!.invoice.invoiceNumber}")
                            },
                            modifier = Modifier.testTag("pdf_preview_print")
                        ) {
                            Icon(Icons.Filled.Print, contentDescription = "Print PDF")
                        }
                        IconButton(
                            onClick = {
                                IntegrationServices.shareInvoicePdf(context, pdfFile!!, invoiceWithItems!!.invoice.invoiceNumber)
                            },
                            modifier = Modifier.testTag("pdf_preview_share")
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Share PDF")
                        }
                        IconButton(
                            onClick = {
                                IntegrationServices.sendInvoiceEmail(context, invoiceWithItems!!, pdfFile)
                            },
                            modifier = Modifier.testTag("pdf_preview_email")
                        ) {
                            Icon(Icons.Filled.Email, contentDescription = "Email PDF")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFF1F5F9)),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Rendering Invoice PDF...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else if (hasError || pdfFile == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Failed to render or generate PDF",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Make sure the invoice is properly saved and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onBack) {
                        Text("Go Back")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("pdf_pages_list"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(pages) { pageBitmap ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(8.dp, shape = RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Image(
                                bitmap = pageBitmap.asImageBitmap(),
                                contentDescription = "PDF Page",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatCurrency(amount: Double, currency: String): String {
    return if (currency == "USD") {
        "USD %,.2f".format(amount)
    } else {
        "Rs. %,.2f".format(amount)
    }
}

