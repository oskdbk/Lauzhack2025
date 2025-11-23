package com.example.ticketapplication

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.util.*

class TicketHostApduService : HostApduService() {

    companion object {
        private val TAG = "TicketHostApduService"
        private val AID = "F222222222"
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        Log.d(TAG, "processCommandApdu: ${commandApdu.toHex()}")

        // 1. Check if this is the correct APDU
        if (selectAidApdu(commandApdu)) {
            val token = TicketStorage.activeSignedToken

            if (token != null) {
                Log.i(TAG, "Sending active token")
                val tokenBytes = token.toByteArray(Charsets.UTF_8)

                // 2. Append 0x90 0x00 (Success Status Word)
                val successSw = byteArrayOf(0x90.toByte(), 0x00.toByte())
                return tokenBytes + successSw
            } else {
                // Optional: Return "File Not Found" (6A 82) if no ticket is active
                Log.i(TAG, "No active token found")
                return byteArrayOf(0x6A.toByte(), 0x82.toByte())
            }
        }

        return null
    }


    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "onDeactivated: reason = $reason")
    }

    private fun selectAidApdu(apdu: ByteArray): Boolean {
        return apdu.size >= 2 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
