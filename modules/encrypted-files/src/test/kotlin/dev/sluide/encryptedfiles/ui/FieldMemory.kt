package dev.sluide.encryptedfiles.ui

import javafx.scene.control.TextInputControl

/**
 * The backing array of the StringBuilder in which a text field keeps its characters (`TextFieldContent.characters`),
 * stale bytes after the length included. Read with Unsafe (jdk.unsupported), which needs no `--add-opens`.
 */
@Suppress("DEPRECATION", "removal")
internal fun backingArray(field: TextInputControl): ByteArray {
    val unsafe =
        Class
            .forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe

    fun read(
        owner: Any,
        declaring: Class<*>,
        name: String,
    ): Any = unsafe.getObject(owner, unsafe.objectFieldOffset(declaring.getDeclaredField(name)))
    val content = read(field, TextInputControl::class.java, "content")
    val characters = read(content, content.javaClass, "characters")
    return read(characters, Class.forName("java.lang.AbstractStringBuilder"), "value") as ByteArray
}
