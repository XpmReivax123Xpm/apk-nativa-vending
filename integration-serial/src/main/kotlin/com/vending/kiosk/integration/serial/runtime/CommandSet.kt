package com.vending.kiosk.integration.serial.runtime

object CommandSet {
    const val POLL_DRIVER_STATUS = "010300030001740A"
    const val POLL_IO_STATUS = "0103000B0001F5C8"

    /**
     * Sends Modbus function 06 to register 0x1002 with value 0x0001.
     * In ZhongDa SDK this maps to ResetLift (lift/platform reset).
     */
    fun buildResetLift(address: Int = 1): String {
        require(address in 1..247) { "Direccion Modbus fuera de rango (1..247): $address" }
        val addrHex = String.format("%02X", address)
        val baseNoCrc = "${addrHex}0610020001"
        return appendCrc16Modbus(baseNoCrc)
    }

    fun buildSelectCellFull(cell: Int): String {
        require(cell in 10..68) { "Celda fuera de rango (10..68): $cell" }
        val id = cell - 10
        val idHex = String.format("%02X", id)
        val baseNoCrc = "01100001000204${idHex}030101"
        return appendCrc16Modbus(baseNoCrc)
    }

    private fun appendCrc16Modbus(hexNoCrc: String): String {
        val data = HexUtil.hexToBytes(hexNoCrc)
        val crc = crc16Modbus(data)
        val lo = crc and 0xFF
        val hi = (crc shr 8) and 0xFF
        return hexNoCrc + String.format("%02X%02X", lo, hi)
    }

    private fun crc16Modbus(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc and 0xFFFF
    }
}
