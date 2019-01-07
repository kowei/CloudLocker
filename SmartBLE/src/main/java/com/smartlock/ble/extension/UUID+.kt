package com.smartlock.ble.extension

import java.nio.ByteBuffer
import java.util.*


fun UUID.asBytes(): ByteArray? {
    val bb = ByteBuffer.wrap(ByteArray(16))
    bb.putLong(this.getMostSignificantBits())
    bb.putLong(this.getLeastSignificantBits())
    return bb.array()
}

fun ByteArray.asUUID(): UUID {
    val bb = ByteBuffer.wrap(this)
    val firstLong = bb.long
    val secondLong = bb.long
    return UUID(firstLong, secondLong)
}
