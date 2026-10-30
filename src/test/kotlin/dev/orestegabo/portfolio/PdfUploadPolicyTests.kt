package dev.orestegabo.portfolio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PdfUploadPolicyTests {
    @Test fun `complete supported envelopes and trailing whitespace are accepted`() {
        for(version in listOf("1.0","1.7","2.0"))
            validateUploadedPdfEnvelope("%PDF-$version\nsynthetic bytes\n%%EOF\n \t".toByteArray())
    }
    @Test fun `wrong versions truncated headers appended bodies and oversized payloads fail closed`() {
        for(value in listOf("", "%PDF-", "%PDF-9.9\n%%EOF", "%PDF-1.7x\n%%EOF", "%PDF-1.7\ntruncated", "%PDF-1.7\n%%EOF<script>"))
            assertThrows(ApiError::class.java) { validateUploadedPdfEnvelope(value.toByteArray()) }
        assertThrows(ApiError::class.java) { validateUploadedPdfEnvelope(ByteArray(10*1024*1024+1)) }
    }
}
