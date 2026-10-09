package libcore.io

import java.io.FileDescriptor
import java.io.FileInputStream

object IoBridge {
    // why does Android even have one
    fun read(fd: FileDescriptor, bytes: ByteArray, byteOffset: Int, byteCount: Int) = FileInputStream(
        fd
    ).read(bytes, byteOffset, byteCount)
}