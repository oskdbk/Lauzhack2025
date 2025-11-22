package com.example.ticketapplication

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.charset.StandardCharsets

class TicketHostApduService : HostApduService() {

    companion object {
        private val TAG = "TicketHostApduService"
        private val AID = "F222222222"
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_COMMAND_NOT_ALLOWED = byteArrayOf(0x69.toByte(), 0x86.toByte())
        private const val SELECT_APDU_HEADER = "00A40400"
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: $reason")
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (commandApdu.toHex().startsWith(SELECT_APDU_HEADER, ignoreCase = true)) {
            Log.i(TAG, "SELECT APDU received")

            val aidLength = commandApdu[4].toInt()
            val aidBytes = commandApdu.copyOfRange(5, 5 + aidLength)
            if (AID == aidBytes.toHex()) {
                Log.i(TAG, "AID match, sending token")
                val token = TicketStorage.activeSignedToken
                return if (token != null) {
                    val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
                    tokenBytes + SW_OK
                } else {
                    Log.w(TAG, "No active token available.")
                    SW_FILE_NOT_FOUND
                }
            }
        }

        Log.w(TAG, "Received unknown APDU: ${commandApdu.toHex()}")
        return SW_COMMAND_NOT_ALLOWED
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
