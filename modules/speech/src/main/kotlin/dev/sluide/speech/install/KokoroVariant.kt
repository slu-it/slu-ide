package dev.sluide.speech.install

import kotlin.math.roundToLong

/** The Kokoro model files of the kokoro-onnx release `model-files-v1.1`, pinned by size and SHA-256 (ADR 0124). */
enum class KokoroVariant(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val label: String,
) {
    FP32(
        "kokoro-v1.0.onnx",
        325_505_369,
        "beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a",
        "Full quality (fp32)",
    ),
    FP16(
        "kokoro-v1.0.fp16.onnx",
        163_527_961,
        "f3a290d384fbb27966d462905c71a46cef9e5fd00516b40df32a0b4afe77ac96",
        "Half precision (fp16)",
    ),
    INT8(
        "kokoro-v1.0.int8.onnx",
        114_119_327,
        "ae315a79b623f244700e4afb9246c46a26066782e049ba174bf3ba433970ee9c",
        "Small (int8)",
    ),
}

/** The voices file all variants share. */
object KokoroVoicesFile {
    const val FILE_NAME = "voices-v1.0.bin"
    const val BYTES = 28_214_398L
    const val SHA256 = "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d"
}

/** A file to download: its name in the release and in the store, its size and its SHA-256 (lower-case hex). */
internal data class ModelFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

/** The files [variant] needs: its model and the voices file. */
internal fun kokoroFiles(variant: KokoroVariant): List<ModelFile> =
    listOf(
        ModelFile(variant.fileName, variant.bytes, variant.sha256),
        ModelFile(KokoroVoicesFile.FILE_NAME, KokoroVoicesFile.BYTES, KokoroVoicesFile.SHA256),
    )

/** [bytes] in decimal megabytes, rounded: `326 MB`. */
fun formatMegabytes(bytes: Long): String = "${(bytes / 1_000_000.0).roundToLong()} MB"
