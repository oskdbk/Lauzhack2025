package com.example.ticketapplication

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
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
            "type" to ticket.type.id,
            "boughtAt" to ticket.boughtAt,
            "isValid" to true
        )

        db.collection("tickets").document(ticket.uid)
            .set(ticketData)
            .addOnSuccessListener {
                Log.d("Firebase", "Ticket successfully written!")
            }
            .addOnFailureListener { e ->
                Log.w("Firebase", "Error writing ticket", e)
            }
    }

    fun validateTicketOnline(ticketData: String): Boolean {
        return try {
            // 1. Parse the NFC string to find the Ticket ID
            val obj = JSONObject(ticketData)
            val payloadBytes = Base64.decode(obj.getString("payload"), Base64.NO_WRAP)
            val payloadJson = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
            val ticketId = payloadJson.getString("ticketId")

            Log.d("Firebase", "Checking database for ID: $ticketId")

            // 2. Ask Firestore for the document
            val docRef = db.collection("tickets").document(ticketId)
            val snapshot = Tasks.await(docRef.get())

            // 3. COMPARE DATA: Check if ID exists AND if ticket is marked valid
            if (snapshot.exists()) {
                val isValidInDb = snapshot.getBoolean("isValid") ?: false
                Log.d("Firebase", "Document found. Valid? $isValidInDb")
                return isValidInDb
            } else {
                Log.d("Firebase", "No such document exists (Fake ID)")
                return false
            }
        } catch (e: Exception) {
            Log.e("Firebase", "Validation failed", e)
            false
        }
    }
}

// ------------------- Models -------------------
data class TicketType(val id: String, val title: String, val durationMinutes: Int, val zones: String)
data class OwnedTicket(val uid: String, val type: TicketType, val boughtAt: Long, var isActive: Boolean = false)

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
}

// ------------------- MainActivity -------------------
class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

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
        nfcAdapter?.let { adapter ->
            val options = Bundle()
            // Enable Reader Mode to scan OTHER phones (Controllers)
            adapter.enableReaderMode(
                this,
                { tag ->
                    // This block runs on a background thread when a tag is found
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
                            val responseStr = String(response, StandardCharsets.UTF_8)

                            Log.d("Controller", "Received from NFC: $responseStr")

                            if (responseStr == "NO_TICKET") {
                                runOnUiThread { Toast.makeText(this, "No Active Ticket Found", Toast.LENGTH_SHORT).show() }
                            } else {
                                // 2. Verify Online using Firebase
                                val isValid = BackendApi.validateTicketOnline(responseStr)

                                runOnUiThread {
                                    if (isValid) {
                                        Toast.makeText(this, "✅ VALID TICKET (Found in Firebase)", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(this, "❌ INVALID (Not in DB or Invalid)", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            isoDep.close()
                        } catch (e: Exception) {
                            Log.e("Controller", "NFC communication failed", e)
                            runOnUiThread { Toast.makeText(this, "NFC Error", Toast.LENGTH_SHORT).show() }
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
        nfcAdapter?.disableReaderMode(this)
    }
}


// ------------------- UI -------------------
@Composable
fun TicketingScreen(activity: Activity) {
    val scope = rememberCoroutineScope()
    val types = remember { TicketStorage.availableTypes }
    var message by remember { mutableStateOf("Welcome — Firebase Demo") }
    val owned = TicketStorage.owned

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
                            val uid = UUID.randomUUID().toString()
                            val ot = OwnedTicket(uid, t, System.currentTimeMillis())
                            owned.add(ot)
                            message = "Buying..."

                            // SAVE TO FIREBASE
                            BackendApi.registerTicketInDb(ot)

                            message = "Bought ${t.title}"
                        }) { Text("Buy") }
                    }
                }
            }
        }

        Divider()
        Text("My Wallet:")
        Column(modifier = Modifier.fillMaxWidth()) {
            for (ot in owned) {
                Row(modifier = Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ot.type.title)
                        Text(if (ot.isActive) "ACTIVE (Ready to Scan)" else "Inactive",
                            color = if(ot.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    Button(onClick = {
                        scope.launch {
                            val signed = activateTicket(ot)
                            TicketStorage.activeSignedToken = signed
                            ot.isActive = true
                            // Reset others
                            owned.forEach { if (it != ot) it.isActive = false }
                            message = "Activated! Tap this phone to a reader."
                        }
                    }, enabled = !ot.isActive) { Text("Activate") }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(message)
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