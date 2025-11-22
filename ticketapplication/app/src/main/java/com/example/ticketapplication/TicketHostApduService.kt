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

        if (selectAidApdu(commandApdu)) {
            Log.i(TAG, "Application selected")
            return TicketStorage.activeSignedToken?.toByteArray()
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
