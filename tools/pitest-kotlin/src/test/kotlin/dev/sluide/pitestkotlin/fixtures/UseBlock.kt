package dev.sluide.pitestkotlin.fixtures

import java.io.InputStream

class UseBlock {
    fun firstByte(stream: InputStream): Int = stream.use { it.read() }

    fun hash(resource: AutoCloseable): Int = resource.use { it.hashCode() }
}
