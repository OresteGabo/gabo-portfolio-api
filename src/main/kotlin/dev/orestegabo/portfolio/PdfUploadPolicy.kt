package dev.orestegabo.portfolio

/** Bounded envelope validation for opaque storage, not malware detection or PDF signature validation.
 * Rendering/extraction remains in the constrained native/client PDF engines. */
internal fun validateUploadedPdfEnvelope(bytes: ByteArray) {
    val message="Invalid PDF upload. Choose a complete PDF of up to 10 MB."
    checkApi(bytes.size in 1..10*1024*1024,message)
    val header=String(bytes,0,minOf(16,bytes.size),Charsets.US_ASCII)
    checkApi(Regex("""^%PDF-(?:1\.[0-7]|2\.0)[\r\n\t ]""").containsMatchIn(header),message)
    var end=bytes.size
    while(end>0 && bytes[end-1].toInt() in setOf(0,9,10,12,13,32))end--
    val marker="%%EOF".toByteArray(Charsets.US_ASCII)
    checkApi(end>=marker.size && marker.indices.all { bytes[end-marker.size+it]==marker[it] },message)
}
