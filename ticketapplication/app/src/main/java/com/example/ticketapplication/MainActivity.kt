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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

// Import Firebase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.tasks.Tasks

// ------------------- Firebase Helper -------------------
object BackendApi {
    private val db by lazy { FirebaseFirestore.getInstance() }

    fun registerTicketInDb(ticket: OwnedTicket) {
        val ticketData = hashMapOf(
            "uid" to ticket.uid,
            "deviceId" to ticket.deviceId,
            "type" to ticket.type.id,
            "boughtAt" to ticket.boughtAt,
            "isValid" to false // Starts inactive
        )

        db.collection("tickets").document(ticket.uid)
            .set(ticketData)
            .addOnSuccessListener {
                Log.d("Firebase", "Ticket registered (Inactive): ${ticket.uid}")
            }
    }

    fun updateTicketStatus(ticketUid: String, isValid: Boolean) {
        db.collection("tickets").document(ticketUid)
            .update("isValid", isValid)
            .addOnSuccessListener {
                Log.d("Firebase", "Ticket $ticketUid status updated to: $isValid")
            }
    }

    fun validateTicketOnline(ticketData: String): Boolean {
        return try {
            val obj = JSONObject(ticketData)
            val payloadBytes = Base64.decode(obj.getString("payload"), Base64.NO_WRAP)
            val payloadJson = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
            val scannedDeviceId = payloadJson.getString("ticketId")

            Log.d("Firebase", "Checking DB for Device: $scannedDeviceId")

            val query = db.collection("tickets")
                .whereEqualTo("deviceId", scannedDeviceId)
                .whereEqualTo("isValid", true)
                .get()

            val snapshot = Tasks.await(query)

            if (!snapshot.isEmpty) {
                Log.d("Firebase", "VALID: Found active ticket for device.")
                true
            } else {
                Log.d("Firebase", "INVALID: No active tickets found.")
                false
            }
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
data class TicketType(val id: String, val title: String, val durationMinutes: Int, val zones: String)
data class OwnedTicket(
    val uid: String,
    val deviceId: String,
    val type: TicketType,
    val boughtAt: Long,
    var isActive: Boolean = false,
    var isUsed: Boolean = false
)

// ------------------- Device Keys -------------------
object DeviceKeys {
    private const val ALIAS = "device_key_hybrid"

    fun ensureKeys() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val kpg = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
    }

    fun pubKeyBase64(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = ks.getCertificate(ALIAS)
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    fun getPrivate(): PrivateKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(ALIAS, null) as PrivateKey
    }
}

// ------------------- Demo Issuer -------------------
object DemoIssuer {
    private var keyPair: KeyPair? = null

    fun getKeyPair(): KeyPair {
        if (keyPair == null) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(256)
            keyPair = kpg.generateKeyPair()
        }
        return keyPair!!
    }

    fun publicKeyBase64(): String {
        return Base64.encodeToString(getKeyPair().public.encoded, Base64.NO_WRAP)
    }
}

// ------------------- Crypto Helpers -------------------
fun signPayload(privateKey: PrivateKey, payload: ByteArray): ByteArray {
    val sig = Signature.getInstance("SHA256withECDSA")
    sig.initSign(privateKey)
    sig.update(payload)
    return sig.sign()
}

fun verifyPayload(publicKeyBytes: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
    return try {
        val kf = KeyFactory.getInstance("EC")
        val pkSpec = X509EncodedKeySpec(publicKeyBytes)
        val pk = kf.generatePublic(pkSpec)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pk)
        sig.update(payload)
        sig.verify(signature)
    } catch (e: Exception) {
        false
    }
}

fun parseAndVerifyToken(token: String): JSONObject? {
    return try {
        val obj = JSONObject(token)
        val payloadB = Base64.decode(obj.getString("payload"), Base64.NO_WRAP)
        val sigB = Base64.decode(obj.getString("signature"), Base64.NO_WRAP)
        val pubB = Base64.decode(obj.getString("issuerPub"), Base64.NO_WRAP)

        val p = JSONObject(String(payloadB, StandardCharsets.UTF_8))
        if (!verifyPayload(pubB, payloadB, sigB)) return null
        p
    } catch (e: Exception) {
        null
    }
}

// ------------------- Storage -------------------
object TicketStorage {
    val availableTypes = listOf(
        TicketType("mobilis_1h", "Mobilis — 1 hour", 60, "Lausanne zones"),
        TicketType("mobilis_24h", "Mobilis — 24 hours", 1440, "Lausanne zones"),
        TicketType("day_pass", "Day Pass", 1440, "All zones"),
        TicketType("zone_pass", "Zone pass (2 zones)", 1440, "2 zones")
    )

    val owned = mutableStateListOf<OwnedTicket>()
    var activeSignedToken: String? by mutableStateOf(null)
    var message: String by mutableStateOf("Welcome")
    var isScanning by mutableStateOf(false)
    var scanResult: String? by mutableStateOf(null)
    var showingTickets by mutableStateOf(false)
}

// ------------------- MainActivity -------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcAdapter?.let { adapter ->
            val options = Bundle()
            adapter.enableReaderMode(
                this,
                { tag ->
                    val isoDep = IsoDep.get(tag)
                    if (isoDep != null) {
                        try {
                            isoDep.connect()
                            val selectCmd = byteArrayOf(
                                0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
                                0x05.toByte(),
                                0xF2.toByte(), 0x22.toByte(), 0x22.toByte(), 0x22.toByte(), 0x22.toByte()
                            )
                            val response = isoDep.transceive(selectCmd)
                            val token = String(response, StandardCharsets.UTF_8)

                            if (token == "NO_TICKET") {
                                runOnUiThread { Toast.makeText(this, "No Active Ticket", Toast.LENGTH_SHORT).show() }
                            } else {
                                val dbValid = BackendApi.validateTicketOnline(token)
                                val parsed = parseAndVerifyToken(token)

                                runOnUiThread {
                                    if (parsed != null && dbValid) {
                                        TicketStorage.scanResult = "VALID ✅\nDevice ID: ${parsed.getString("ticketId")}\nDB Verified."
                                        TicketStorage.message = "Scan: VALID"
                                    } else {
                                        TicketStorage.scanResult = "INVALID ❌\nNot found in DB."
                                        TicketStorage.message = "Scan: INVALID"
                                    }
                                    TicketStorage.isScanning = false
                                }
                            }
                            isoDep.close()
                        } catch (e: Exception) {
                            Log.e("Controller", "NFC Error", e)
                        }
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                options
            )
        }
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }
}

// ------------------- UI -------------------
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
                title = { Text("Scan Result") },
                text = { Text(TicketStorage.scanResult ?: "") },
                confirmButton = { Button(onClick = { TicketStorage.scanResult = null }) { Text("Dismiss") } }
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Lausanne Ticket Store", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(types) { t ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t.title)
                                Text(t.zones, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = {
                                val uid = getDeviceId(context)
                                val uniqueTicketId = UUID.randomUUID().toString()
                                val ot = OwnedTicket(uniqueTicketId, uid, t, System.currentTimeMillis())
                                TicketStorage.owned.add(ot)

                                scope.launch(Dispatchers.IO) {
                                    BackendApi.registerTicketInDb(ot)
                                }
                                TicketStorage.message = "Bought ${t.title}"
                            }) { Text("Buy") }
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            val totalTickets = TicketStorage.owned.size
            val activeTickets = TicketStorage.owned.count { it.isActive }
            Text("Wallet Status:", style = MaterialTheme.typography.titleMedium)
            Text("You have $totalTickets tickets ($activeTickets active).")

            Spacer(Modifier.height(10.dp))
            Text(TicketStorage.message, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f).height(50.dp),
                    onClick = {
                        TicketStorage.isScanning = true
                        TicketStorage.message = "Ready to scan..."
                    }
                ) { Text("Scan Others") }

                Button(
                    modifier = Modifier.weight(1f).height(50.dp),
                    onClick = { TicketStorage.showingTickets = true }
                ) { Text("My Tickets") }
            }
        }
    }
}

@Composable
fun TicketsScreen(activity: Activity) {
    val owned = TicketStorage.owned
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { TicketStorage.showingTickets = false }) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text("My Wallet", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))

        if (owned.isEmpty()) {
            Text("No tickets.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(owned) { index, ot ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ot.type.title)
                                Text("Ticket: ${ot.uid.take(8)}...", style = MaterialTheme.typography.bodySmall)
                            }

                            if (ot.isActive) {
                                Button(onClick = {}, enabled = false) { Text("Active") }
                            } else if (ot.isUsed) {
                                Button(onClick = {}, enabled = false) { Text("Used") }
                            } else {
                                Button(onClick = {
                                    scope.launch {
                                        // 1. Deactivate locally only (UI update)
                                        // We DO NOT call Firebase here, so old tickets stay valid in DB
                                        for (i in owned.indices) {
                                            if (owned[i].isActive) {
                                                owned[i] = owned[i].copy(isActive = false, isUsed = true)
                                            }
                                        }

                                        // 2. Activate the selected ticket
                                        owned[index] = ot.copy(isActive = true, isUsed = false)

                                        // 3. Update Firebase to valid
                                        BackendApi.updateTicketStatus(ot.uid, true)

                                        // 4. Prepare NFC
                                        val signed = activateTicket(owned[index])
                                        TicketStorage.activeSignedToken = signed
                                        TicketStorage.message = "Ticket Active!"
                                    }
                                }) { Text("Activate") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScanningScreen(activity: Activity) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Scanning...", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            TicketStorage.isScanning = false
            TicketStorage.message = "Scan cancelled."
        }) { Text("Cancel") }
    }
}

// ------------------- Ticket Helpers -------------------
suspend fun activateTicket(ot: OwnedTicket): String {
    DeviceKeys.ensureKeys()
    val now = System.currentTimeMillis()
    val validUntil = now + 3600000L

    val payload = JSONObject().apply {
        put("ticketId", ot.deviceId)
        put("type", ot.type.id)
        put("title", ot.type.title)
        put("zones", ot.type.zones)
        put("issuedAt", now)
        put("validUntil", validUntil)
        put("nonce", UUID.randomUUID().toString())
        put("devicePub", DeviceKeys.pubKeyBase64())
    }

    val payloadBytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
    val sig = signPayload(DemoIssuer.getKeyPair().private, payloadBytes)

    return JSONObject().apply {
        put("payload", Base64.encodeToString(payloadBytes, Base64.NO_WRAP))
        put("signature", Base64.encodeToString(sig, Base64.NO_WRAP))
        put("issuerPub", DemoIssuer.publicKeyBase64())
    }.toString()
}