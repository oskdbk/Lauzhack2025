////package com.example.ticketapplication
////
////import android.app.Activity
////import android.content.Context
////import android.nfc.NfcAdapter
////import android.nfc.tech.IsoDep
////import android.os.Bundle
////import android.provider.Settings
////import android.util.Base64
////import android.util.Log
////import android.widget.Toast
////import androidx.activity.ComponentActivity
////import androidx.activity.compose.setContent
////import androidx.compose.animation.AnimatedVisibility
////import androidx.compose.foundation.background
////import androidx.compose.foundation.clickable
////import androidx.compose.foundation.layout.*
////import androidx.compose.foundation.lazy.LazyColumn
////import androidx.compose.foundation.lazy.items
////import androidx.compose.foundation.lazy.itemsIndexed
////import androidx.compose.foundation.shape.RoundedCornerShape
////import androidx.compose.material.icons.Icons
////import androidx.compose.material.icons.filled.ArrowBack
////import androidx.compose.material.icons.filled.CheckCircle
////import androidx.compose.material.icons.filled.ConfirmationNumber
////import androidx.compose.material.icons.filled.ShoppingCart
////import androidx.compose.material.icons.filled.Search
////import androidx.compose.material3.*
////import androidx.compose.runtime.*
////import androidx.compose.ui.Alignment
////import androidx.compose.ui.Modifier
////import androidx.compose.ui.graphics.Color
////import androidx.compose.ui.platform.LocalContext
////import androidx.compose.ui.text.font.FontWeight
////import androidx.compose.ui.unit.dp
////import androidx.compose.ui.unit.sp
////import kotlinx.coroutines.Dispatchers
////import kotlinx.coroutines.launch
////import kotlinx.coroutines.tasks.await
////import org.json.JSONObject
////import java.nio.charset.StandardCharsets
////import java.security.*
////import java.security.spec.ECGenParameterSpec
////import java.security.spec.X509EncodedKeySpec
////import java.text.SimpleDateFormat
////import java.util.*
////import android.security.keystore.KeyGenParameterSpec
////import android.security.keystore.KeyProperties
////
////// Import Firebase
////import com.google.firebase.firestore.FirebaseFirestore
////import com.google.android.gms.tasks.Tasks
////
////// --- SBB Color Palette ---
////val SBB_Red = Color(0xFFEB0000)
////val SBB_Black = Color(0xFF2D327D)
////val SBB_Text = Color(0xFF222222)
////val SBB_Grey = Color(0xFFF2F2F2)
////val SBB_Light_Grey = Color(0xFFE5E5E5)
////
////// ------------------- Encryption Helper -------------------
////object CryptoManager {
////    // Hardcoded 32-byte key for Demo.
////    // AES/ECB is deterministic: encrypting the same DeviceID always yields the same String.
////    private const val SECRET_KEY = "12345678901234567890123456789012"
////    private const val ALGORITHM = "AES"
////
////    fun encrypt(data: String): String {
////        return try {
////            val key = SecretKeySpec(SECRET_KEY.toByteArray(), ALGORITHM)
////            val cipher = Cipher.getInstance(ALGORITHM)
////            cipher.init(Cipher.ENCRYPT_MODE, key)
////            val encryptedBytes = cipher.doFinal(data.toByteArray())
////            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
////        } catch (e: Exception) {
////            Log.e("Crypto", "Encryption error", e)
////            data
////        }
////    }
////}
////
////// ------------------- Firebase Helper -------------------
////object BackendApi {
////    private val db by lazy { FirebaseFirestore.getInstance() }
////
////    fun registerTicketInDb(ticket: OwnedTicket) {
////        // 1. Encrypt the Device ID before saving
////        val encryptedDeviceId = CryptoManager.encrypt(ticket.deviceId)
////
////        val ticketData = hashMapOf(
////            "uid" to ticket.uid,
////            "deviceId" to encryptedDeviceId, // Stored Encrypted
////            "type" to ticket.type.id,
////            "boughtAt" to ticket.boughtAt,
////            "isValid" to false // Starts inactive, requires activation
////        )
////
////        // Use random ticket UID as document ID so we can have multiple tickets
////        db.collection("tickets").document(ticket.uid)
////            .set(ticketData)
////            .addOnSuccessListener {
////                Log.d("Firebase", "Ticket registered: ${ticket.uid}")
////            }
////    }
////
////    fun updateTicketStatus(ticketUid: String, isValid: Boolean) {
////        db.collection("tickets").document(ticketUid)
////            .update("isValid", isValid)
////            .addOnSuccessListener {
////                Log.d("Firebase", "Ticket $ticketUid status updated to: $isValid")
////            }
////    }
////
////    fun validateTicketOnline(rawNfcData: String, validTypes: List<String>): Boolean {
////        return try {
////            val obj = JSONObject(rawNfcData)
////            // We decode the payload to get the RAW Device ID
////            val payloadBytes = Base64.decode(obj.getString("payload"), Base64.NO_WRAP)
////            val payloadJson = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
////            val rawDeviceId = payloadJson.getString("ticketId")
////
////            // 2. ENCRYPT the Raw ID to match what is in the Database
////            val encryptedIdToSearch = CryptoManager.encrypt(rawDeviceId)
////
////            Log.d("Firebase", "Checking DB for Encrypted Device: $encryptedIdToSearch")
////
////            // 3. Query: Match Encrypted Device ID + Valid + Allowed Types
////            // Note: Firestore cannot do "IN" queries efficiently on multiple fields easily in all cases,
////            // so we fetch all valid tickets for this device and filter in code for types.
////            val query = db.collection("tickets")
////                .whereEqualTo("deviceId", encryptedIdToSearch)
////                .whereEqualTo("isValid", true)
////                .get()
////
////            val snapshot = Tasks.await(query)
////
////            if (snapshot.isEmpty) {
////                Log.d("Firebase", "INVALID: No active tickets found.")
////                return false
////            }
////
////            // Check if ANY of the user's active tickets match the Controller's allowed zones
////            for (doc in snapshot.documents) {
////                val type = doc.getString("type")
////                if (type != null && validTypes.contains(type)) {
////                    // Ensure ticket hasn't expired based on time (Optional security check)
////                    val boughtAt = doc.getLong("boughtAt") ?: 0L
////                    // Simplistic check: Assume 24h max validity for demo safety
////                    if (System.currentTimeMillis() - boughtAt < 86400000) {
////                        Log.d("Firebase", "VALID: Found matching ticket: $type")
////                        return true
////                    }
////                }
////            }
////
////            Log.d("Firebase", "INVALID: Active ticket found, but wrong zone/type.")
////            return false
////
////        } catch (e: Exception) {
////            Log.e("Firebase", "Validation failed", e)
////            false
////        }
////    }
////}
////
////// ------------------- Helper: Get Unique Device ID -------------------
////fun getDeviceId(context: Context): String {
////    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
////}
////
////// ------------------- Models -------------------
////data class TicketType(val id: String, val title: String, val durationMinutes: Int, val zones: String, val price: String)
////data class OwnedTicket(
////    val uid: String,
////    val deviceId: String,
////    val type: TicketType,
////    val boughtAt: Long,
////    var isActive: Boolean = false,
////    var isUsed: Boolean = false
////)
////
////enum class ValidationMode(val title: String, val allowedTypes: List<String>) {
////    LAUSANNE_AREA("Lausanne Area", listOf("mobilis_1h", "mobilis_24h", "day_pass", "zone_pass")),
////    LAUSANNE_GENEVA("Lausanne ↔ Geneva", listOf("lausanne_geneva", "day_pass"))
////}
////
////// ------------------- Storage -------------------
////object TicketStorage {
////    val availableTypes = listOf(
////        TicketType("mobilis_1h", "Mobilis — 1 Hour", 60, "Lausanne (11, 12)", "CHF 3.00"),
////        TicketType("mobilis_24h", "Mobilis — 24 Hours", 1440, "Lausanne (11, 12)", "CHF 6.00"),
////        TicketType("lausanne_geneva", "Lausanne ↔ Geneva", 60, "InterCity Direct", "CHF 24.00"),
////        TicketType("day_pass", "Day Pass", 1440, "All Zones", "CHF 12.00"),
////        TicketType("zone_pass", "Zone Upgrade", 120, "+2 Zones", "CHF 4.20")
////    )
////
////    val owned = mutableStateListOf<OwnedTicket>()
////    var activeSignedToken: String? by mutableStateOf(null)
////    var message: String by mutableStateOf("Welcome to SBB Mobile")
////    var isScanning by mutableStateOf(false)
////    var scanResult: String? by mutableStateOf(null)
////
////    var selectedValidationMode: ValidationMode? by mutableStateOf(null)
////    var showingTickets by mutableStateOf(false)
////}
////
////// ------------------- MainActivity -------------------
////class MainActivity : ComponentActivity() {
////    override fun onCreate(savedInstanceState: Bundle?) {
////        super.onCreate(savedInstanceState)
////
////        // Initialize the NFC Payload ONCE.
////        // The phone always broadcasts its Identity (Device ID), regardless of which ticket is active.
////        // The database determines if that ID is valid.
////        val deviceId = getDeviceId(this)
////        TicketStorage.activeSignedToken = generateDeviceIdentityToken(deviceId)
////
////        setContent {
////            MaterialTheme(
////                colorScheme = lightColorScheme(
////                    primary = SBB_Red,
////                    background = SBB_Grey,
////                    surface = Color.White,
////                    onPrimary = Color.White,
////                    onSurface = SBB_Text
////                )
////            ) {
////                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
////                    TicketingScreen(this)
////                }
////            }
////        }
////    }
////
////    override fun onResume() {
////        super.onResume()
////        // Do not automatically enable reader mode here. Start/stop explicitly via UI.
////        if (inspectorEnabled) startInspector()
////    }
////
////    override fun onPause() {
////        super.onPause()
////        // Keep reader disabled when paused
////        if (inspectorEnabled) stopInspector()
////    }
////
////    // Public methods to start/stop inspector mode from UI
////    fun startInspector() {
////        nfcAdapter?.let { adapter ->
////            val options = Bundle()
////            adapter.enableReaderMode(
////                this,
////                readerCallback,
////                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
////                options
////            )
////            inspectorEnabled = true
////            Log.d("MainActivity", "Inspector started")
////            // user-visible feedback
////            runOnUiThread { Toast.makeText(this, "Inspector started (hold near card)", Toast.LENGTH_SHORT).show() }
////        }
////    }
////
////    fun stopInspector() {
////        nfcAdapter?.disableReaderMode(this)
////        inspectorEnabled = false
////        Log.d("MainActivity", "Inspector stopped")
////        // user-visible feedback
////        runOnUiThread { Toast.makeText(this, "Inspector stopped", Toast.LENGTH_SHORT).show() }
////    }
////}
////
////// ------------------- UI Components -------------------
////
////@Composable
////fun SBBHeader() {
////    Column(
////        modifier = Modifier
////            .fillMaxWidth()
////            .background(SBB_Red)
////            .padding(16.dp)
////    ) {
////        Text(
////            text = "SBB CFF FFS",
////            color = Color.White,
////            fontSize = 24.sp,
////            fontWeight = FontWeight.Bold
////        )
////        Text(
////            text = "Mobile Ticket Demo",
////            color = Color.White.copy(alpha = 0.8f),
////            fontSize = 14.sp
////        )
////    }
////}
////
////@Composable
////fun TicketCard(
////    title: String,
////    subtitle: String,
////    price: String? = null,
////    buttonText: String,
////    onClick: () -> Unit,
////    isActive: Boolean = false,
////    isUsed: Boolean = false
////) {
////    Card(
////        modifier = Modifier
////            .fillMaxWidth()
////            .padding(vertical = 6.dp),
////        colors = CardDefaults.cardColors(containerColor = Color.White),
////        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
////        shape = RoundedCornerShape(8.dp)
////    ) {
////        Row(
////            modifier = Modifier
////                .padding(16.dp)
////                .fillMaxWidth(),
////            verticalAlignment = Alignment.CenterVertically,
////            horizontalArrangement = Arrangement.SpaceBetween
////        ) {
////            Column(modifier = Modifier.weight(1f)) {
////                Text(
////                    text = title,
////                    style = MaterialTheme.typography.titleMedium,
////                    color = SBB_Text,
////                    fontWeight = FontWeight.Bold
////                )
////                Spacer(modifier = Modifier.height(4.dp))
////                Text(
////                    text = subtitle,
////                    style = MaterialTheme.typography.bodySmall,
////                    color = Color.Gray
////                )
////                if (price != null) {
////                    Spacer(modifier = Modifier.height(4.dp))
////                    Text(
////                        text = price,
////                        style = MaterialTheme.typography.bodyMedium,
////                        color = SBB_Red,
////                        fontWeight = FontWeight.Bold
////                    )
////                }
////            }
////
////            Button(
////                onClick = onClick,
////                enabled = !isUsed, // Enabled as long as not expired
////                colors = ButtonDefaults.buttonColors(
////                    // If Active: Green. If Inactive: Red.
////                    containerColor = if (isActive) Color(0xFF4CAF50) else SBB_Red,
////                    disabledContainerColor = Color.Gray
////                ),
////                shape = RoundedCornerShape(20.dp),
////                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
////            ) {
////                Text(
////                    // If Active: "Active". If Inactive: "Activate"
////                    text = if (isActive) "Active" else if (isUsed) "Expired" else buttonText,
////                    color = Color.White,
////                    fontWeight = FontWeight.Bold
////                )
////            }
////        }
////    }
////}
////
////@Composable
////fun TicketingScreen(activity: Activity) {
////    val context = LocalContext.current
////
////    if (TicketStorage.isScanning) {
////        ScanningScreen(activity)
////    } else if (TicketStorage.showingTickets) {
////        TicketsScreen(activity)
////    } else {
////        val scope = rememberCoroutineScope()
////        val types = remember { TicketStorage.availableTypes }
////
////        if (TicketStorage.scanResult != null) {
////            AlertDialog(
////                onDismissRequest = { TicketStorage.scanResult = null },
////                title = {
////                    Row(verticalAlignment = Alignment.CenterVertically) {
////                        Icon(
////                            imageVector = if(TicketStorage.scanResult == "VALID") Icons.Default.CheckCircle else Icons.Default.ConfirmationNumber,
////                            contentDescription = null,
////                            tint = if(TicketStorage.scanResult == "VALID") Color(0xFF4CAF50) else SBB_Red
////                        )
////                        Spacer(modifier = Modifier.width(8.dp))
////                        Text(if(TicketStorage.scanResult == "VALID") "Valid Ticket" else "Invalid Ticket")
////                    }
////                },
////                text = { Text(TicketStorage.message, fontSize = 16.sp) },
////                confirmButton = {
////                    TextButton(onClick = { TicketStorage.scanResult = null }) {
////                        Text("OK", color = SBB_Red, fontWeight = FontWeight.Bold)
////                    }
////                },
////                containerColor = Color.White
////            )
////        }
////
////        Column(modifier = Modifier.fillMaxSize()) {
////            SBBHeader()
////
////            Column(modifier = Modifier.padding(16.dp)) {
////                Text(
////                    text = "Buy Tickets",
////                    style = MaterialTheme.typography.titleLarge,
////                    color = SBB_Text,
////                    fontWeight = FontWeight.Bold,
////                    modifier = Modifier.padding(bottom = 12.dp)
////                )
////
////                LazyColumn(modifier = Modifier.weight(1f)) {
////                    items(types) { t ->
////                        TicketCard(
////                            title = t.title,
////                            subtitle = t.zones,
////                            price = t.price,
////                            buttonText = "Buy",
////                            onClick = {
////                                val uid = getDeviceId(context)
////                                // Unique ticket ID for database tracking
////                                val uniqueTicketId = UUID.randomUUID().toString()
////                                val ot = OwnedTicket(uniqueTicketId, uid, t, System.currentTimeMillis())
////                                TicketStorage.owned.add(ot)
////
////                                scope.launch(Dispatchers.IO) {
////                                    BackendApi.registerTicketInDb(ot)
////                                }
////                                TicketStorage.message = "Purchase Successful"
////                                Toast.makeText(context, "Purchased ${t.title}", Toast.LENGTH_SHORT).show()
////                            }
////                        )
////                    }
////                }
////
////                Spacer(modifier = Modifier.height(16.dp))
////
////                Card(
////                    modifier = Modifier.fillMaxWidth(),
////                    colors = CardDefaults.cardColors(containerColor = SBB_Black),
////                    shape = RoundedCornerShape(8.dp)
////                ) {
////                    Column(modifier = Modifier.padding(16.dp)) {
////                        val totalTickets = TicketStorage.owned.size
////                        val activeTickets = TicketStorage.owned.count { it.isActive }
////
////                        Text("Your Wallet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
////                        Spacer(modifier = Modifier.height(4.dp))
////                        Text(
////                            "$totalTickets tickets • $activeTickets active",
////                            color = Color.White.copy(alpha = 0.8f)
////                        )
////                    }
////                }
////
////                Spacer(Modifier.height(16.dp))
////
////                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
////                    Button(
////                        modifier = Modifier.weight(1f).height(56.dp),
////                        onClick = {
////                            TicketStorage.isScanning = true
////                            TicketStorage.message = "Select ticket type to inspect"
////                        },
////                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
////                        shape = RoundedCornerShape(8.dp),
////                        elevation = ButtonDefaults.buttonElevation(2.dp)
////                    ) {
////                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
////                            Icon(Icons.Default.Search, contentDescription = null, tint = SBB_Red)
////                            Text("Inspector", color = SBB_Red, fontSize = 12.sp)
////                        }
////                    }
////
////                    Button(
////                        modifier = Modifier.weight(1f).height(56.dp),
////                        onClick = { TicketStorage.showingTickets = true },
////                        colors = ButtonDefaults.buttonColors(containerColor = SBB_Red),
////                        shape = RoundedCornerShape(8.dp),
////                        elevation = ButtonDefaults.buttonElevation(2.dp)
////                    ) {
////                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
////                            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.White)
////                            Text("My Tickets", color = Color.White, fontSize = 12.sp)
////                        }
////                    }
////                }
////            }
////        }
////    }
////}
////
////@Composable
////fun TicketsScreen(activity: Activity) {
////    val owned = TicketStorage.owned
////    val scope = rememberCoroutineScope()
////
////    Column(modifier = Modifier.fillMaxSize().background(SBB_Grey)) {
////        Row(
////            modifier = Modifier
////                .fillMaxWidth()
////                .background(Color.White)
////                .padding(16.dp),
////            verticalAlignment = Alignment.CenterVertically
////        ) {
////            IconButton(onClick = { TicketStorage.showingTickets = false }) {
////                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = SBB_Red)
////            }
////            Text(
////                text = "My Tickets",
////                fontSize = 20.sp,
////                fontWeight = FontWeight.Bold,
////                color = SBB_Text
////            )
////        }
////
////        if (owned.isEmpty()) {
////            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
////                Text("No tickets purchased yet.", color = Color.Gray)
////            }
////        } else {
////            LazyColumn(
////                modifier = Modifier
////                    .fillMaxSize()
////                    .padding(16.dp)
////            ) {
////                itemsIndexed(owned) { index, ot ->
////                    val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ot.boughtAt))
////                    val isExpired = System.currentTimeMillis() > (ot.boughtAt + ot.type.durationMinutes * 60000L)
////
////                    TicketCard(
////                        title = ot.type.title,
////                        subtitle = "Purchased: $dateStr\nID: ${ot.uid.take(8)}...",
////                        buttonText = if(isExpired) "Expired" else if (ot.isActive) "Active" else "Activate",
////                        isActive = ot.isActive,
////                        isUsed = isExpired,
////                        onClick = {
////                            if (!isExpired) {
////                                scope.launch {
////                                    // Update UI Local State ONLY
////                                    // We do NOT change Firebase state of other tickets.
////                                    // This allows multiple valid tickets to exist in DB.
////
////                                    // Toggle Active State
////                                    val newState = !ot.isActive
////
////                                    // Update local item
////                                    owned[index] = ot.copy(isActive = newState)
////
////                                    // Update Firebase for THIS ticket
////                                    BackendApi.updateTicketStatus(ot.uid, newState)
////
////                                    TicketStorage.message = if (newState) "Ticket Activated" else "Ticket Deactivated"
////                                }
////                            }
////                        }
////                    )
////                }
////            }
////        }
////    }
////}
////
////@Composable
////fun ScanningScreen(activity: Activity) {
////    val modes = ValidationMode.values()
////
////    Column(modifier = Modifier.fillMaxSize().background(SBB_Grey)) {
////        if (TicketStorage.selectedValidationMode == null) {
////            Column(modifier = Modifier.padding(16.dp)) {
////                Text(
////                    "Inspection Mode",
////                    style = MaterialTheme.typography.headlineMedium,
////                    color = SBB_Text,
////                    fontWeight = FontWeight.Bold
////                )
////                Text(
////                    "Select area to verify:",
////                    modifier = Modifier.padding(vertical = 16.dp),
////                    color = Color.Gray
////                )
////
////                LazyColumn(modifier = Modifier.weight(1f)) {
////                    items(modes) { mode ->
////                        Card(
////                            modifier = Modifier
////                                .fillMaxWidth()
////                                .padding(vertical = 4.dp)
////                                .clickable { TicketStorage.selectedValidationMode = mode },
////                            colors = CardDefaults.cardColors(containerColor = Color.White),
////                            shape = RoundedCornerShape(4.dp)
////                        ) {
////                            Row(
////                                modifier = Modifier.padding(16.dp),
////                                verticalAlignment = Alignment.CenterVertically
////                            ) {
////                                Icon(Icons.Default.Search, contentDescription = null, tint = SBB_Red)
////                                Spacer(Modifier.width(16.dp))
////                                Column {
////                                    Text(mode.title, fontWeight = FontWeight.SemiBold)
////                                    Text("Valid for: ${mode.allowedTypes.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
////                                }
////                            }
////                        }
////                    }
////                }
////                Button(
////                    onClick = { TicketStorage.isScanning = false },
////                    modifier = Modifier.fillMaxWidth(),
////                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
////                ) {
////                    Text("Cancel Inspection")
////                }
////            }
////        } else {
////            Column(
////                modifier = Modifier.fillMaxSize(),
////                horizontalAlignment = Alignment.CenterHorizontally,
////                verticalArrangement = Arrangement.Center
////            ) {
////                Text(
////                    "Inspecting for",
////                    style = MaterialTheme.typography.titleMedium,
////                    color = Color.Gray
////                )
////                Spacer(Modifier.height(8.dp))
////                Text(
////                    TicketStorage.selectedValidationMode!!.title,
////                    style = MaterialTheme.typography.headlineMedium,
////                    color = SBB_Red,
////                    fontWeight = FontWeight.Bold
////                )
////
////                Spacer(Modifier.height(40.dp))
////                CircularProgressIndicator(
////                    modifier = Modifier.size(80.dp),
////                    color = SBB_Red,
////                    strokeWidth = 6.dp
////                )
////                Spacer(Modifier.height(40.dp))
////                Text(
////                    "Hold scanner near passenger device",
////                    style = MaterialTheme.typography.bodyMedium,
////                    color = SBB_Text
////                )
////
////                Spacer(Modifier.height(60.dp))
////                Button(
////                    onClick = {
////                        TicketStorage.selectedValidationMode = null
////                        TicketStorage.isScanning = false
////                    },
////                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
////                ) { Text("Cancel") }
////            }
////        }
////    }
////}
////
////// ------------------- Global Helper for Device ID Token -------------------
////fun generateDeviceIdentityToken(deviceId: String): String {
////    val payload = JSONObject().apply {
////        put("ticketId", deviceId) // The Raw ID
////        put("timestamp", System.currentTimeMillis())
////    }
////    return payload.toString()
////


package com.example.ticketapplication

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

// Import Firebase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.tasks.Tasks

// --- SBB Color Palette ---
val SBB_Red = Color(0xFFEB0000)
val SBB_Black = Color(0xFF2D327D)
val SBB_Text = Color(0xFF222222)
val SBB_Grey = Color(0xFFF2F2F2)
val SBB_Light_Grey = Color(0xFFE5E5E5)

// ------------------- Encryption Helper -------------------
object CryptoManager {
    // Hardcoded 32-byte key for Demo.
    // AES/ECB is deterministic: encrypting the same DeviceID always yields the same String.
    private const val SECRET_KEY = "12345678901234567890123456789012"
    private const val ALGORITHM = "AES"

    fun encrypt(data: String): String {
        return try {
            val key = SecretKeySpec(SECRET_KEY.toByteArray(), ALGORITHM)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedBytes = cipher.doFinal(data.toByteArray())
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("Crypto", "Encryption error", e)
            data
        }
    }
}

// ------------------- Firebase Helper -------------------
object BackendApi {
    private val db by lazy { FirebaseFirestore.getInstance() }

    fun registerTicketInDb(ticket: OwnedTicket) {
        // 1. Encrypt the Device ID before saving
        val encryptedDeviceId = CryptoManager.encrypt(ticket.deviceId)

        val ticketData = hashMapOf(
            "uid" to ticket.uid,
            "deviceId" to encryptedDeviceId, // Stored Encrypted
            "type" to ticket.type.id,
            "boughtAt" to ticket.boughtAt,
            "isValid" to false // Starts inactive, requires activation
        )

        // Use random ticket UID as document ID so we can have multiple tickets
        db.collection("tickets").document(ticket.uid)
            .set(ticketData)
            .addOnSuccessListener {
                Log.d("Firebase", "Ticket registered: ${ticket.uid}")
            }
    }

    fun updateTicketStatus(ticketUid: String, isValid: Boolean) {
        db.collection("tickets").document(ticketUid)
            .update("isValid", isValid)
            .addOnSuccessListener {
                Log.d("Firebase", "Ticket $ticketUid status updated to: $isValid")
            }
    }

    fun validateTicketOnline(rawNfcData: String, validTypes: List<String>): Boolean {
        return try {
            val obj = JSONObject(rawNfcData)
            // We decode the payload to get the RAW Device ID
            val payloadBytes = Base64.decode(obj.getString("payload"), Base64.NO_WRAP)
            val payloadJson = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
            val rawDeviceId = payloadJson.getString("ticketId")

            // 2. ENCRYPT the Raw ID to match what is in the Database
            val encryptedIdToSearch = CryptoManager.encrypt(rawDeviceId)

            Log.d("Firebase", "Checking DB for Encrypted Device: $encryptedIdToSearch")

            // 3. Query: Match Encrypted Device ID + Valid + Allowed Types
            // Note: Firestore cannot do "IN" queries efficiently on multiple fields easily in all cases,
            // so we fetch all valid tickets for this device and filter in code for types.
            val query = db.collection("tickets")
                .whereEqualTo("deviceId", encryptedIdToSearch)
                .whereEqualTo("isValid", true)
                .get()

            val snapshot = Tasks.await(query)

            if (snapshot.isEmpty) {
                Log.d("Firebase", "INVALID: No active tickets found.")
                return false
            }

            // Check if ANY of the user's active tickets match the Controller's allowed zones
            for (doc in snapshot.documents) {
                val type = doc.getString("type")
                if (type != null && validTypes.contains(type)) {
                    // Ensure ticket hasn't expired based on time (Optional security check)
                    val boughtAt = doc.getLong("boughtAt") ?: 0L
                    // Simplistic check: Assume 24h max validity for demo safety
                    if (System.currentTimeMillis() - boughtAt < 86400000) {
                        Log.d("Firebase", "VALID: Found matching ticket: $type")
                        return true
                    }
                }
            }

            Log.d("Firebase", "INVALID: Active ticket found, but wrong zone/type.")
            return false

        } catch (e: Exception) {
            Log.e("Firebase", "Validation failed", e)
            false
        }
    }
}

// ------------------- Helper: Get Unique Device ID -------------------
fun getDeviceId(context: Context): String {
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}

// ------------------- Models -------------------
data class TicketType(val id: String, val title: String, val durationMinutes: Int, val zones: String, val price: String)
data class OwnedTicket(
    val uid: String,
    val deviceId: String,
    val type: TicketType,
    val boughtAt: Long,
    var isActive: Boolean = false,
    var isUsed: Boolean = false
)

enum class ValidationMode(val title: String, val allowedTypes: List<String>) {
    LAUSANNE_AREA("Lausanne Area", listOf("mobilis_1h", "mobilis_24h", "day_pass", "zone_pass")),
    LAUSANNE_GENEVA("Lausanne ↔ Geneva", listOf("lausanne_geneva", "day_pass"))
}

// ------------------- Storage -------------------
object TicketStorage {
    val availableTypes = listOf(
        TicketType("mobilis_1h", "Mobilis — 1 Hour", 60, "Lausanne (11, 12)", "CHF 3.00"),
        TicketType("mobilis_24h", "Mobilis — 24 Hours", 1440, "Lausanne (11, 12)", "CHF 6.00"),
        TicketType("lausanne_geneva", "Lausanne ↔ Geneva", 60, "InterCity Direct", "CHF 24.00"),
        TicketType("day_pass", "Day Pass", 1440, "All Zones", "CHF 12.00"),
        TicketType("zone_pass", "Zone Upgrade", 120, "+2 Zones", "CHF 4.20")
    )

    val owned = mutableStateListOf<OwnedTicket>()
    var activeSignedToken: String? by mutableStateOf(null)
    var message: String by mutableStateOf("Welcome to SBB Mobile")
    var isScanning by mutableStateOf(false)
    var scanResult: String? by mutableStateOf(null)
    // Display text prepared by the reader callback after scanning a ticket
    var scannedTicketDisplay: String? by mutableStateOf(null)

    var selectedValidationMode: ValidationMode? by mutableStateOf(null)
    var showingTickets by mutableStateOf(false)
}

// ------------------- MainActivity -------------------
class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    // track whether reader (inspector) mode is active
    private var inspectorEnabled = false

    // Reader callback logic extracted so we can enable/disable it explicitly
    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            try {
                isoDep.connect()

                // 1. Send SELECT command to custom AID (F0010203040506)
                val selectCmd = byteArrayOf(
                    0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
                    0x07.toByte(),
                    0xF0.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
                )
                val response = isoDep.transceive(selectCmd)

                // Strip trailing status word 0x90 0x00 if present
                var responseBytes = response
                if (responseBytes.size >= 2 && responseBytes[responseBytes.size - 2] == 0x90.toByte() && responseBytes[responseBytes.size - 1] == 0x00.toByte()) {
                    responseBytes = responseBytes.copyOf(responseBytes.size - 2)
                }
                val responseStr = String(responseBytes, StandardCharsets.UTF_8)

                Log.d("Controller", "Received from NFC: $responseStr")

                if (responseStr == "NO_TICKET") {
                    runOnUiThread { Toast.makeText(this, "No Active Ticket Found", Toast.LENGTH_SHORT).show() }
                } else {
                    // 2. Verify Online using Firebase
                    val isValid = BackendApi.validateTicketOnline(responseStr, listOf("mobilis_1h", "mobilis_24h", "day_pass", "zone_pass"))

                    // Parse payload and prepare a display string with ticket details
                    try {
                        val obj = JSONObject(responseStr)
                        val payloadBytes = Base64.decode(obj.getString("payload"), Base64.NO_WRAP)
                        val payloadJson = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))

                        val sb = StringBuilder()
                        sb.append("Ticket scanned:\n")
                        sb.append("ID: ").append(payloadJson.optString("ticketId")).append("\n")
                        sb.append("Title: ").append(payloadJson.optString("title")).append("\n")
                        sb.append("Type: ").append(payloadJson.optString("type")).append("\n")
                        sb.append("Zones: ").append(payloadJson.optString("zones")).append("\n")
                        sb.append("IssuedAt: ").append(payloadJson.optLong("issuedAt")).append("\n")
                        sb.append("ValidUntil: ").append(payloadJson.optLong("validUntil")).append("\n")
                        sb.append("Verified online: ").append(if (isValid) "YES" else "NO")

                        // Store for UI consumption (Compose will show a dialog)
                        TicketStorage.scannedTicketDisplay = sb.toString()
                    } catch (e: Exception) {
                        Log.e("Controller", "Failed to parse scanned payload", e)
                        runOnUiThread { Toast.makeText(this, "Scanned (but parse failed)", Toast.LENGTH_SHORT).show() }
                    }

                    // Also give immediate feedback
                    runOnUiThread {
                        if (isValid) {
                            Toast.makeText(this, "✅ VALID TICKET (Found in Firebase)", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "❌ INVALID (Not in DB)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                isoDep.close()
            } catch (e: Exception) {
                Log.e("Controller", "NFC communication failed", e)
                runOnUiThread { Toast.makeText(this, "NFC Error", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TicketingScreen(this)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Do not automatically enable reader mode here. Start/stop explicitly via UI.
        if (inspectorEnabled) startInspector()
    }

    override fun onPause() {
        super.onPause()
        // Keep reader disabled when paused
        if (inspectorEnabled) stopInspector()
    }

    // Public methods to start/stop inspector mode from UI
    fun startInspector() {
        nfcAdapter?.let { adapter ->
            val options = Bundle()
            adapter.enableReaderMode(
                this,
                readerCallback,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                options
            )
            inspectorEnabled = true
            Log.d("MainActivity", "Inspector started")
            // user-visible feedback
            runOnUiThread { Toast.makeText(this, "Inspector started (hold near card)", Toast.LENGTH_SHORT).show() }
        }
    }

    fun stopInspector() {
        nfcAdapter?.disableReaderMode(this)
        inspectorEnabled = false
        Log.d("MainActivity", "Inspector stopped")
        // user-visible feedback
        runOnUiThread { Toast.makeText(this, "Inspector stopped", Toast.LENGTH_SHORT).show() }
    }
}

// ------------------- UI Components -------------------

@Composable
fun SBBHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SBB_Red)
            .padding(16.dp)
    ) {
        Text(
            text = "SBB CFF FFS",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Mobile Ticket Demo",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp
        )
    }
}

@Composable
fun TicketCard(
    title: String,
    subtitle: String,
    price: String? = null,
    buttonText: String,
    onClick: () -> Unit,
    isActive: Boolean = false,
    isUsed: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SBB_Text,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                if (price != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = price,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SBB_Red,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Button(
                onClick = onClick,
                enabled = !isUsed, // Enabled as long as not expired
                colors = ButtonDefaults.buttonColors(
                    // If Active: Green. If Inactive: Red.
                    containerColor = if (isActive) Color(0xFF4CAF50) else SBB_Red,
                    disabledContainerColor = Color.Gray
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    // If Active: "Active". If Inactive: "Activate"
                    text = if (isActive) "Active" else if (isUsed) "Expired" else buttonText,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun TicketingScreen(activity: Activity) {
    val context = LocalContext.current

    if (TicketStorage.isScanning) {
        ScanningScreen(activity)
    } else if (TicketStorage.showingTickets) {
        TicketsScreen(activity)
    } else {
        val scope = rememberCoroutineScope()
        val types = remember { TicketStorage.availableTypes }

        if (TicketStorage.scanResult != null) {
            AlertDialog(
                onDismissRequest = { TicketStorage.scanResult = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if(TicketStorage.scanResult == "VALID") Icons.Default.CheckCircle else Icons.Default.ConfirmationNumber,
                            contentDescription = null,
                            tint = if(TicketStorage.scanResult == "VALID") Color(0xFF4CAF50) else SBB_Red
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if(TicketStorage.scanResult == "VALID") "Valid Ticket" else "Invalid Ticket")
                    }
                },
                text = { Text(TicketStorage.message, fontSize = 16.sp) },
                confirmButton = {
                    TextButton(onClick = { TicketStorage.scanResult = null }) {
                        Text("OK", color = SBB_Red, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color.White
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            SBBHeader()

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Buy Tickets",
                    style = MaterialTheme.typography.titleLarge,
                    color = SBB_Text,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(types) { t ->
                        TicketCard(
                            title = t.title,
                            subtitle = t.zones,
                            price = t.price,
                            buttonText = "Buy",
                            onClick = {
                                val uid = getDeviceId(context)
                                // Unique ticket ID for database tracking
                                val uniqueTicketId = UUID.randomUUID().toString()
                                val ot = OwnedTicket(uniqueTicketId, uid, t, System.currentTimeMillis())
                                TicketStorage.owned.add(ot)

                                scope.launch(Dispatchers.IO) {
                                    BackendApi.registerTicketInDb(ot)
                                }
                                TicketStorage.message = "Purchase Successful"
                                Toast.makeText(context, "Purchased ${t.title}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SBB_Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val totalTickets = TicketStorage.owned.size
                        val activeTickets = TicketStorage.owned.count { it.isActive }

                        Text("Your Wallet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "$totalTickets tickets • $activeTickets active",
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        modifier = Modifier.weight(1f).height(56.dp),
                        onClick = {
                            TicketStorage.isScanning = true
                            TicketStorage.message = "Select ticket type to inspect"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        elevation = ButtonDefaults.buttonElevation(2.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = SBB_Red)
                            Text("Inspector", color = SBB_Red, fontSize = 12.sp)
                        }
                    }

                    Button(
                        modifier = Modifier.weight(1f).height(56.dp),
                        onClick = { TicketStorage.showingTickets = true },
                        colors = ButtonDefaults.buttonColors(containerColor = SBB_Red),
                        shape = RoundedCornerShape(8.dp),
                        elevation = ButtonDefaults.buttonElevation(2.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.White)
                            Text("My Tickets", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TicketsScreen(activity: Activity) {
    val owned = TicketStorage.owned
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(SBB_Grey)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { TicketStorage.showingTickets = false }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = SBB_Red)
            }
            Text(
                text = "My Tickets",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = SBB_Text
            )
        }

        if (owned.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tickets purchased yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                itemsIndexed(owned) { index, ot ->
                    val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ot.boughtAt))
                    val isExpired = System.currentTimeMillis() > (ot.boughtAt + ot.type.durationMinutes * 60000L)

                    TicketCard(
                        title = ot.type.title,
                        subtitle = "Purchased: $dateStr\nID: ${ot.uid.take(8)}...",
                        buttonText = if(isExpired) "Expired" else if (ot.isActive) "Active" else "Activate",
                        isActive = ot.isActive,
                        isUsed = isExpired,
                        onClick = {
                            if (!isExpired) {
                                scope.launch {
                                    // Update UI Local State ONLY
                                    // We do NOT change Firebase state of other tickets.
                                    // This allows multiple valid tickets to exist in DB.

                                    // Toggle Active State
                                    val newState = !ot.isActive

                                    // Update local item
                                    owned[index] = ot.copy(isActive = newState)

                                    // Update Firebase for THIS ticket
                                    BackendApi.updateTicketStatus(ot.uid, newState)

                                    TicketStorage.message = if (newState) "Ticket Activated" else "Ticket Deactivated"
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ScanningScreen(activity: Activity) {
    val modes = ValidationMode.values()

    Column(modifier = Modifier.fillMaxSize().background(SBB_Grey)) {
        if (TicketStorage.selectedValidationMode == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Inspection Mode",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SBB_Text,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Select area to verify:",
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = Color.Gray
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(modes) { mode ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { TicketStorage.selectedValidationMode = mode },
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = SBB_Red)
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(mode.title, fontWeight = FontWeight.SemiBold)
                                    Text("Valid for: ${mode.allowedTypes.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { TicketStorage.isScanning = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text("Cancel Inspection")
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Inspecting for",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    TicketStorage.selectedValidationMode!!.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = SBB_Red,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(40.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp),
                    color = SBB_Red,
                    strokeWidth = 6.dp
                )
                Spacer(Modifier.height(40.dp))
                Text(
                    "Hold scanner near passenger device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SBB_Text
                )

                Spacer(Modifier.height(60.dp))
                Button(
                    onClick = {
                        TicketStorage.selectedValidationMode = null
                        TicketStorage.isScanning = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) { Text("Cancel") }
            }
        }
    }
}

// ------------------- Global Helper for Device ID Token -------------------
fun generateDeviceIdentityToken(deviceId: String): String {
    val payload = JSONObject().apply {
        put("ticketId", deviceId) // The Raw ID
        put("timestamp", System.currentTimeMillis())
    }
    return payload.toString()
}