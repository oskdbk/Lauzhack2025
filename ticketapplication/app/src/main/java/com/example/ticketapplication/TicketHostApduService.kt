package com.example.ticketapplication

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.charset.StandardCharsets // ADDED: Required for toByteArray(StandardCharsets.UTF_8)
import java.util.*

class TicketHostApduService : HostApduService() {

    companion object {
        private val TAG = "TicketHostApduService"
        private val AID = "F222222222"
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        Log.d(TAG, "processCommandApdu: ${commandApdu.toHex()}")

        if (selectAidApdu(commandApdu)) {
            Log.i(TAG, "Application selected")

            // Get the token data. Fallback to "NO_TICKET" if null.
            val responseData = TicketStorage.activeSignedToken?.toByteArray(StandardCharsets.UTF_8)
                ?: "NO_TICKET".toByteArray(StandardCharsets.UTF_8)

            // FIX: Append the mandatory success status word 0x90 0x00 to the data.
            // This tells the reader phone that the transaction was successful.
            return responseData + byteArrayOf(0x90.toByte(), 0x00.toByte())
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