package org.futo.voiceinput.shared.opencl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v45 regression seam for the v44 CAPABILITY bug. Android recycles a Message
 * (data = null, what = 0) as soon as the Handler returns, so the request must
 * be copied on the main thread. The first test fails if [OpenClCapabilityRequest]
 * ever stores the accessor and reads it later in the worker.
 */
class OpenClCapabilityRequestTest {
    @Test fun captureReadsProtocolBeforeTheMessageCanBeRecycled() {
        var protocol: Int? = OpenClCapabilityProtocol.VERSION
        val request = OpenClCapabilityRequest.capture("id") { protocol }
        protocol = null // Message.recycleUnchecked()
        assertNotNull(request)
        assertTrue(request!!.protocolAccepted)
        assertEquals(OpenClCapabilityProtocol.VERSION, request.protocol)
        assertEquals("id", request.id)
    }

    @Test fun missingProtocolOrIdIsRejectedRatherThanGuessed() {
        assertFalse(OpenClCapabilityRequest.capture("id") { null }!!.protocolAccepted)
        assertFalse(OpenClCapabilityRequest.capture("id") { 0 }!!.protocolAccepted)
        assertNull(OpenClCapabilityRequest.capture(null) { OpenClCapabilityProtocol.VERSION })
    }

    @Test fun workerMapsProtocolAndNativeFailuresToBoundedReplies() {
        var calls = 0
        assertEquals("ocv=1\nprobe=ok\n", OpenClCapabilityWorker.reply(true) {
            calls++
            "ocv=1\nprobe=ok\n"
        })
        assertEquals(1, calls)

        assertEquals(OpenClCapabilityProtocol.QUERY_FAILED_PROBE,
            OpenClCapabilityWorker.reply(false) { calls++; "unexpected" })
        assertEquals("rejected protocol never touches native", 1, calls)

        assertEquals(OpenClCapabilityProtocol.NOT_PACKAGED_PROBE,
            OpenClCapabilityWorker.reply(true) { throw UnsatisfiedLinkError("missing") })
        assertEquals(OpenClCapabilityProtocol.QUERY_FAILED_PROBE,
            OpenClCapabilityWorker.reply(true) { throw IllegalStateException("boom") })
    }
}
