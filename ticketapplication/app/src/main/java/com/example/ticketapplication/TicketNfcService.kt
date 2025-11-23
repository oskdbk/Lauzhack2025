package com.example.ticketapplication

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * TicketNfcService - HCE Service
 *
 * This service makes your phone act as an NFC card that broadcasts
 * your ticket data to inspector devices.
 *
 * IMPORTANT: Create this as a NEW FILE in your project:
 * app/src/main/java/com/example/ticketapplication/TicketNfcService.kt
 */
class TicketNfcService : HostApduService() {

    companion object {
        // This AID must match what the controller sends in SELECT command
        private val AID = byteArrayOf(
            0xF2.toByte(), 0x22.toByte(), 0x22.toByte(), 0x22.toByte(), 0x22.toByte()
        )
        private const val TAG = "TicketNfcService"
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        Log.d(TAG, "processCommandApdu called")

        if (commandApdu == null || commandApdu.size < 5) {
            Log.e(TAG, "Invalid APDU received")
            return getErrorResponse()
        }

        // Check if this is a SELECT command for our AID
        if (isSelectCommand(commandApdu)) {
            Log.d(TAG, "SELECT command received - sending ticket data")

            // Get the active ticket token from storage
            val token = TicketStorage.activeSignedToken

            return if (token != null) {
                Log.d(TAG, "Token available, sending: ${token.take(50)}...")
                // Return the ticket data + success status (0x9000)
                val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
                tokenBytes + byteArrayOf(0x90.toByte(), 0x00.toByte())
            } else {
                Log.d(TAG, "No token available, sending NO_TICKET")
                // No active ticket - return "NO_TICKET" message
                val noTicket = "NO_TICKET".toByteArray(StandardCharsets.UTF_8)
                noTicket + byteArrayOf(0x90.toByte(), 0x00.toByte())
            }
        }

        Log.e(TAG, "Unknown command received")
        return getErrorResponse()
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = when(reason) {
            DEACTIVATION_LINK_LOSS -> "Link lost"
            DEACTIVATION_DESELECTED -> "Deselected"
            else -> "Unknown ($reason)"
        }
        Log.d(TAG, "Service deactivated: $reasonStr")
    }

    private fun isSelectCommand(apdu: ByteArray): Boolean {
        // SELECT command structure: 00 A4 04 00 [length] [AID]
        if (apdu.size < 5 + AID.size) {
            Log.d(TAG, "APDU too short for SELECT")
            return false
        }

        // Check command bytes
        if (apdu[0] != 0x00.toByte() ||
            apdu[1] != 0xA4.toByte() ||
            apdu[2] != 0x04.toByte() ||
            apdu[3] != 0x00.toByte()) {
            Log.d(TAG, "Not a SELECT command")
            return false
        }

        // Check if AID matches
        for (i in AID.indices) {
            if (apdu[5 + i] != AID[i]) {
                Log.d(TAG, "AID mismatch at position $i")
                return false
            }
        }

        Log.d(TAG, "Valid SELECT command for our AID")
        return true
    }

    private fun getErrorResponse(): ByteArray {
        // Return error status: 0x6F00 (unknown error)
        return byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }
}