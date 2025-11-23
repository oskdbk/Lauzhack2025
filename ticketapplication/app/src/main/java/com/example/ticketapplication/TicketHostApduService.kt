package com.example.ticketapplication

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Base64
import android.util.Log

// Simple APDU responder that returns the active signed token when SELECT AID is received.
class TicketHostApduService : HostApduService() {

    companion object {
        private val TAG = "TicketHCE"
        private val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val UNKNOWN_CMD_SW = byteArrayOf(0x00.toByte(), 0x00.toByte())
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        if (commandApdu == null) return UNKNOWN_CMD_SW
        try {
            // Convert APDU to hex to inspect if it's a SELECT AID
            val hex = commandApdu.joinToString("") { String.format("%02X", it) }
            Log.d(TAG, "APDU received: $hex")

            // A simple check for SELECT (00A404) at start
            if (hex.startsWith("00A404")) {
                // If we have an active signed token, return it as UTF-8 bytes, followed by OK status
                val payload = TicketStorage.activeSignedToken
                return if (!payload.isNullOrEmpty()) {
                    val payloadBytes = payload.toByteArray(Charsets.UTF_8)
                    // Append status word
                    payloadBytes + SELECT_OK_SW
                } else {
                    // Return NO_TICKET string and OK
                    "NO_TICKET".toByteArray(Charsets.UTF_8) + SELECT_OK_SW
                }
            }

            // For everything else, return unknown
            return UNKNOWN_CMD_SW
        } catch (e: Exception) {
            Log.e(TAG, "Error processing APDU", e)
            return UNKNOWN_CMD_SW
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: $reason")
    }
}

