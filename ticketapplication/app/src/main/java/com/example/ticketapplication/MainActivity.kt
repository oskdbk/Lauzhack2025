
package com.example.ticketapplication

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

// --- SBB Color Palette ---
val SBB_Red = Color(0xFFEB0000)
val SBB_Black = Color(0xFF2D327D)
val SBB_Text = Color(0xFF222222)
val SBB_Grey = Color(0xFFF2F2F2)

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
    var showingTickets by mutableStateOf(false)
}

// ------------------- HCE Constants -------------------
object HceConstants {
    val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
    const val AID = "F222222222"

    fun createSelectAidApdu(aid: String): ByteArray {
        val aidBytes = aid.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val header = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
        return header + aidBytes.size.toByte() + aidBytes
    }
}


// ------------------- MainActivity -------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = SBB_Red,
                    background = SBB_Grey,
                    surface = Color.White,
                    onPrimary = Color.White,
                    onSurface = SBB_Text
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TicketingScreen(this)
                }
            }
        }
    }
}

// ------------------- UI Components -------------------
@Composable
fun SBBHeader() {
    Column(modifier = Modifier.fillMaxWidth().background(SBB_Red).padding(16.dp)) {
        Text("SBB CFF FFS", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Mobile Ticket Demo", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}

@Composable
fun TicketCard(title: String, subtitle: String, price: String? = null, buttonText: String, onClick: () -> Unit, isActive: Boolean = false, isUsed: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), shape = RoundedCornerShape(8.dp)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = SBB_Text, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (price != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(price, style = MaterialTheme.typography.bodyMedium, color = SBB_Red, fontWeight = FontWeight.Bold)
                }
            }
            Button(onClick = onClick, enabled = !isUsed, colors = ButtonDefaults.buttonColors(containerColor = if (isActive) Color(0xFF4CAF50) else SBB_Red, disabledContainerColor = Color.Gray), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Text(if (isActive) "Active" else if (isUsed) "Expired" else buttonText, color = Color.White, fontWeight = FontWeight.Bold)
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
                title = { Text("Scan Result") },
                text = { Text("Token verification: ${TicketStorage.scanResult}") },
                confirmButton = {
                    Button(onClick = { TicketStorage.scanResult = null }) {
                        Text("Dismiss")
                    }
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            SBBHeader()
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Buy Tickets", style = MaterialTheme.typography.titleLarge, color = SBB_Text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(types) { t ->
                        TicketCard(title = t.title, subtitle = t.zones, price = t.price, buttonText = "Buy", onClick = {
                            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                            val uniqueTicketId = UUID.randomUUID().toString()
                            val ot = OwnedTicket(uniqueTicketId, deviceId, t, System.currentTimeMillis())
                            TicketStorage.owned.add(ot)
                            Toast.makeText(context, "Purchased ${t.title}", Toast.LENGTH_SHORT).show()
                        })
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SBB_Black), shape = RoundedCornerShape(8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val totalTickets = TicketStorage.owned.size
                        val activeTickets = TicketStorage.owned.count { it.isActive }
                        Text("Your Wallet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("$totalTickets tickets available • $activeTickets active", color = Color.White.copy(alpha = 0.8f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(modifier = Modifier.weight(1f).height(56.dp), onClick = { TicketStorage.isScanning = true }, colors = ButtonDefaults.buttonColors(containerColor = Color.White), shape = RoundedCornerShape(8.dp), elevation = ButtonDefaults.buttonElevation(2.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Search, contentDescription = null, tint = SBB_Red); Text("Inspector", color = SBB_Red, fontSize = 12.sp) }
                    }
                    Button(modifier = Modifier.weight(1f).height(56.dp), onClick = { TicketStorage.showingTickets = true }, colors = ButtonDefaults.buttonColors(containerColor = SBB_Red), shape = RoundedCornerShape(8.dp), elevation = ButtonDefaults.buttonElevation(2.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.White); Text("My Tickets", color = Color.White, fontSize = 12.sp) }
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
        Row(modifier = Modifier.fillMaxWidth().background(Color.White).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { TicketStorage.showingTickets = false }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = SBB_Red) }
            Text("My Tickets", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SBB_Text)
        }
        if (owned.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tickets purchased yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                itemsIndexed(owned) { index, ot ->
                    val isExpired = System.currentTimeMillis() > (ot.boughtAt + ot.type.durationMinutes * 60000L)
                    TicketCard(
                        title = ot.type.title,
                        subtitle = "Purchased: ${dateString(ot.boughtAt)}",
                        buttonText = "Activate",
                        isActive = ot.isActive,
                        isUsed = isExpired,
                        onClick = {
                            if (!isExpired) {
                                scope.launch {
                                    owned.forEach { it.isActive = false }
                                    ot.isActive = true
                                    val signed = activateTicket(ot)
                                    TicketStorage.activeSignedToken = signed
                                    TicketStorage.message = "Activated ${ot.type.title}"
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
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(activity) }

    DisposableEffect(Unit) {
        val readerCallback = NfcAdapter.ReaderCallback { tag ->
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                Log.e("ScanningScreen", "Not an IsoDep tag")
                return@ReaderCallback
            }

            try {
                isoDep.connect()
                val selectApdu = HceConstants.createSelectAidApdu(HceConstants.AID)
                val response = isoDep.transceive(selectApdu)

                val responseData = response.copyOfRange(0, response.size - 2)
                val statusCode = response.copyOfRange(response.size - 2, response.size)

                if (Arrays.equals(statusCode, HceConstants.SW_OK)) {
                    val token = String(responseData, StandardCharsets.UTF_8)
                    Log.d("ScanningScreen", "Scanned token: $token")
                    val verified = verifyTokenString(token)
                    activity.runOnUiThread {
                        val result = if (verified) "OK" else "FAILED"
                        TicketStorage.message = "Scanned token: $result"
                        TicketStorage.scanResult = result
                        TicketStorage.isScanning = false
                    }
                } else {
                    Log.e("ScanningScreen", "APDU command failed: ${statusCode.toHex()}")
                    activity.runOnUiThread {
                        TicketStorage.scanResult = "FAILED"
                        TicketStorage.isScanning = false
                    }
                }

            } catch (e: IOException) {
                Log.e("ScanningScreen", "Error communicating with HCE device", e)
                activity.runOnUiThread {
                    TicketStorage.scanResult = "FAILED"
                    TicketStorage.isScanning = false
                }
            } finally {
                try {
                    isoDep.close()
                } catch (e: IOException) {
                    Log.e("ScanningScreen", "Error closing IsoDep", e)
                }
            }
        }

        val options = Bundle()
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
        Text("Waiting to scan...", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(modifier = Modifier.size(64.dp), strokeWidth = 6.dp, color = SBB_Red)
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            TicketStorage.isScanning = false
            TicketStorage.message = "Scan cancelled."
        }) {
            Text("Cancel")
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

fun verifyTokenString(token: String): Boolean {
    return try {
        val obj = JSONObject(token)
        val payloadB = Base64.decode(obj.getString("payload"), Base64.NO_WRAP)
        val sigB = Base64.decode(obj.getString("signature"), Base64.NO_WRAP)
        val pubB = Base64.decode(obj.getString("issuerPub"), Base64.NO_WRAP)

        val p = JSONObject(String(payloadB, StandardCharsets.UTF_8))
        if (System.currentTimeMillis() > p.getLong("validUntil")) {
            Log.w("verifyTokenString", "Verification failed: Ticket expired")
            return false
        }

        val isValid = verifyPayload(pubB, payloadB, sigB)
        if (!isValid) {
            Log.w("verifyTokenString", "Verification failed: Signature invalid")
        }
        isValid
    } catch (e: Exception) {
        Log.e("verifyTokenString", "Verification failed with exception", e)
        false
    }
}

fun dateString(ms: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(ms))
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

