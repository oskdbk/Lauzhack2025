package com.example.ticketapplication

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    // Lazy load to prevent crash if context isn't ready
    private val db by lazy { FirebaseFirestore.getInstance() }

    fun registerTicketInDb(ticket: OwnedTicket) {
        val ticketData = hashMapOf(
            "uid" to ticket.uid,
            "type" to ticket.type.id,
            "boughtAt" to ticket.boughtAt,
            "isValid" to true
        )

        db.collection("tickets").document(ticket.uid)
            .set(ticketData)
            .addOnSuccessListener {
                Log.d("Firebase", "Ticket successfully written: ${ticket.uid}")
            }
            .addOnFailureListener { e ->
                Log.w("Firebase", "Error writing ticket", e)
            }
    }

    fun validateTicketOnline(ticketData: String): Boolean {
        return try {
            // 1. Parse the NFC string to find the Ticket ID
            // We expect the payload to be the signed JSON token
            val obj = JSONObject(ticketData)
            val payloadBytes = Base64.decode(obj.getString("payload"), Base64.NO_WRAP)
            val payloadJson = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
            val ticketId = payloadJson.getString("ticketId")

            Log.d("Firebase", "Checking database for ID: $ticketId")

            // 2. Ask Firestore for the document synchronously (blocking)
            val docRef = db.collection("tickets").document(ticketId)
            val snapshot = Tasks.await(docRef.get())

            if (snapshot.exists()) {
                Log.d("Firebase", "Document found.")
                true
            } else {
                Log.d("Firebase", "No such document exists")
                false
            }
        } catch (e: Exception) {
            Log.e("Firebase", "Validation failed/Error parsing token", e)
            false
        }
    }
}

// ------------------- Models -------------------
data class TicketType(val id: String, val title: String, val durationMinutes: Int, val zones: String)
// Merged: Includes 'isUsed' from your first file
data class OwnedTicket(val uid: String, val type: TicketType, val boughtAt: Long, var isActive: Boolean = false, var isUsed: Boolean = false)

// ------------------- Device Keys (Hybrid Binding) -------------------
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

// ------------------- Helper Functions -------------------
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

// parse token and return payload JSONObject if signature/validity OK, else null
fun parseAndVerifyToken(token: String): JSONObject? {
    return try {
        val obj = JSONObject(token)
        val payloadB = Base64.decode(obj.getString("payload"), Base64.NO_WRAP)
        val sigB = Base64.decode(obj.getString("signature"), Base64.NO_WRAP)
        val pubB = Base64.decode(obj.getString("issuerPub"), Base64.NO_WRAP)

        val p = JSONObject(String(payloadB, StandardCharsets.UTF_8))
        if (System.currentTimeMillis() > p.getLong("validUntil")) return null

        if (!verifyPayload(pubB, payloadB, sigB)) return null
        p
    } catch (e: Exception) {
        Log.e("LausanneMock", "parse/verify err", e)
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
    var activeTicketUid: String? by mutableStateOf(null)
    var activeTicketPayload: String? by mutableStateOf(null)
    var activeTicketIssuedAt: Long? by mutableStateOf(null)
    var message: String by mutableStateOf("Welcome — demo mock ticket app")
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
}

@Composable
fun ScanningScreen(activity: Activity) {
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(activity) }

    // Merged: Using IsoDep (from File 2) instead of Ndef (from File 1)
    // This is required to talk to the HCE service on the other phone.
    DisposableEffect(Unit) {
        val readerCallback = NfcAdapter.ReaderCallback { tag ->
            val isoDep = IsoDep.get(tag)
            if (isoDep != null) {
                try {
                    isoDep.connect()
                    // Send SELECT command for AID F0010203040506
                    val selectCmd = byteArrayOf(
                        0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
                        0x07.toByte(),
                        0xF0.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte()
                    )
                    val response = isoDep.transceive(selectCmd)
                    val token = String(response, StandardCharsets.UTF_8)

                    Log.d("Controller", "Scanned token: $token")

                    if (token == "NO_TICKET") {
                        activity.runOnUiThread {
                            TicketStorage.message = "No active ticket on device."
                            TicketStorage.scanResult = "NO_TICKET"
                            TicketStorage.isScanning = false
                        }
                    } else {
                        // 1. Verify Crypto Locally
                        val parsed = parseAndVerifyToken(token)

                        // 2. Verify in Firebase
                        val dbValid = BackendApi.validateTicketOnline(token)

                        activity.runOnUiThread {
                            if (parsed != null && dbValid) {
                                TicketStorage.scanResult = "VALID ✅\n\nDB Verified: YES\nDetails:\n${parsed.toString(2)}"
                                TicketStorage.message = "Scan: VALID"
                            } else if (parsed != null && !dbValid) {
                                TicketStorage.scanResult = "INVALID ❌\n\nCrypto: OK\nDatabase: NOT FOUND (Fake ticket?)"
                                TicketStorage.message = "Scan: INVALID DB"
                            } else {
                                TicketStorage.scanResult = "INVALID ❌\n\nCrypto Signature Failed"
                                TicketStorage.message = "Scan: INVALID CRYPTO"
                            }
                            TicketStorage.isScanning = false
                        }
                    }
                    isoDep.close()
                } catch (e: Exception) {
                    Log.e("Controller", "NFC Error", e)
                    activity.runOnUiThread {
                        TicketStorage.message = "NFC Error or Connection Lost"
                        TicketStorage.isScanning = false
                    }
                }
            }
        }

        val options = Bundle()
        // IMPORTANT: Use FLAG_READER_SKIP_NDEF_CHECK for HCE/IsoDep
        nfcAdapter?.enableReaderMode(
            activity,
            readerCallback,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            options
        )

        onDispose {
            nfcAdapter?.disableReaderMode(activity)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Hold near other phone...", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            TicketStorage.isScanning = false
            TicketStorage.message = "Scan cancelled."
        }) {
            Text("Cancel")
        }
    }
}

// ------------------- UI -------------------
@Composable
fun TicketingScreen(activity: Activity) {
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
                confirmButton = {
                    Button(onClick = { TicketStorage.scanResult = null }) {
                        Text("Dismiss")
                    }
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Lausanne Mock Ticket Store", style = MaterialTheme.typography.titleLarge)
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
                                val uid = UUID.randomUUID().toString()
                                val ot = OwnedTicket(uid, t, System.currentTimeMillis())
                                TicketStorage.owned.add(ot)

                                // Merged: Save to Firebase
                                scope.launch(Dispatchers.IO) {
                                    BackendApi.registerTicketInDb(ot)
                                }

                                TicketStorage.message = "Bought ${t.title} (uid=$uid)"
                            }) { Text("Buy") }
                        }
                    }
                }
            }

            Divider()
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(10.dp))
            Text(TicketStorage.message)
            Spacer(Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f).height(40.dp),
                    onClick = { TicketStorage.message = "Issuer pubkey (base64): ${DemoIssuer.publicKeyBase64()}" }
                ) {
                    Text("Show issuer pubkey", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    modifier = Modifier.weight(1f).height(40.dp),
                    onClick = {
                        TicketStorage.isScanning = true
                        TicketStorage.message = "Ready to scan another device."
                    }
                ) {
                    Text("Verify (Scan)", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    modifier = Modifier.weight(1f).height(40.dp),
                    onClick = { TicketStorage.showingTickets = true }
                ) {
                    Text("Tickets", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun TicketsScreen(activity: Activity) {
    val owned = TicketStorage.owned
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { TicketStorage.showingTickets = false }) {
                Text("Cancel")
            }
            Spacer(Modifier.width(12.dp))
            Text("Owned tickets", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(12.dp))

        if (owned.isEmpty()) {
            Text("No purchased tickets yet.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(owned) { ot ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ot.type.title)
                                Text("Bought: ${dateString(ot.boughtAt)}", style = MaterialTheme.typography.bodySmall)
                            }

                            if (ot.isActive) {
                                Button(onClick = {}, enabled = false) { Text("Active") }
                            } else if (ot.isUsed) {
                                Button(onClick = {}, enabled = false) { Text("Used") }
                            } else {
                                Button(onClick = {
                                    scope.launch {
                                        // mark previous active as used
                                        val prev = owned.find { it.isActive }
                                        prev?.let { it.isActive = false; it.isUsed = true }

                                        // set this as active
                                        ot.isActive = true
                                        ot.isUsed = false

                                        // generate signed token
                                        val signed = activateTicket(ot)
                                        TicketStorage.activeSignedToken = signed
                                        TicketStorage.activeTicketUid = ot.uid

                                        // store pretty payload
                                        try {
                                            val parsed = parseAndVerifyToken(signed)
                                            TicketStorage.activeTicketPayload = parsed?.toString(2)
                                            TicketStorage.activeTicketIssuedAt = parsed?.optLong("issuedAt")
                                        } catch (_: Exception) {
                                            TicketStorage.activeTicketPayload = null
                                            TicketStorage.activeTicketIssuedAt = null
                                        }

                                        TicketStorage.message = "Activated ${ot.type.title} — present via NFC"
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

// ------------------- Ticket Helpers -------------------
suspend fun activateTicket(ot: OwnedTicket): String {
    DeviceKeys.ensureKeys()
    val now = System.currentTimeMillis()
    val validUntil = now + ot.type.durationMinutes * 60L * 1000L

    val payload = JSONObject().apply {
        put("ticketId", ot.uid)
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

fun dateString(ms: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(ms))
}