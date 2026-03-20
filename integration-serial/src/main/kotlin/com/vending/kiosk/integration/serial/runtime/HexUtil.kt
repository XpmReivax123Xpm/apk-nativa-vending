package com.vending.kiosk.integration.serial.runtime

object HexUtil {
    fun bytesToHex(data: ByteArray, size: Int = data.size): String {
        val out = StringBuilder()
        val limit = size.coerceAtMost(data.size)
        for (i in 0 until limit) {
            out.append(String.format("%02X", data[i]))
            if (i != limit - 1) out.append(" ")
        }
        return out.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").uppercase()
        require(clean.length % 2 == 0) { "HEX invalido: $hex" }
        return ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }
}

