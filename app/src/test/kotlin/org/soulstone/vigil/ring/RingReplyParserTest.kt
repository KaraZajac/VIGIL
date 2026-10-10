package org.soulstone.vigil.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the ring-reply parsing to the DULT wire format (little-endian opcode, then
 * operands). The bug this guards: every reply, including a refusal, was reported
 * as "Ringing confirmed".
 */
class RingReplyParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // Command_Response(Sound_Start, status): 0x0302 | 0x0300 | status, all little-endian.
    private fun commandResponse(status: Int, command: Int = 0x0300) = bytes(
        0x02, 0x03,
        command and 0xFF, (command shr 8) and 0xFF,
        status and 0xFF, (status shr 8) and 0xFF
    )

    @Test fun successForSoundStartIsTheOnlyConfirmation() {
        assertEquals(RingReply.Ringing, RingReplyParser.parse(commandResponse(0x0000)))
    }

    @Test fun nearOwnerRefusalIsReportedAsARefusal() {
        // A DULT tag MUST answer Sound_Start with Invalid_command outside the
        // separated state. This exact reply used to read "Ringing confirmed".
        val reply = RingReplyParser.parse(commandResponse(0xFFFF))
        assertEquals(RingReply.Refused(0xFFFF), reply)
        val msg = RingReplyParser.message(reply)
        assertTrue(msg, msg.contains("refused"))
        assertFalse(msg, msg.contains("Ringing confirmed"))
    }

    @Test fun soundAlreadyPlayingIsARefusal() {
        assertEquals(RingReply.Refused(0x0001), RingReplyParser.parse(commandResponse(0x0001)))
    }

    @Test fun unknownErrorStatusIsStillARefusal() {
        val reply = RingReplyParser.parse(commandResponse(0x0004))
        assertEquals(RingReply.Refused(0x0004), reply)
        assertTrue(RingReplyParser.message(reply).contains("0x0004"))
    }

    @Test fun soundCompletedIsRecognised() {
        assertEquals(RingReply.Completed, RingReplyParser.parse(bytes(0x03, 0x03)))
    }

    @Test fun successForAnotherCommandProvesNothing() {
        assertEquals(RingReply.Unreadable, RingReplyParser.parse(commandResponse(0x0000, command = 0x0301)))
    }

    @Test fun malformedRepliesAreUnreadableNeverRinging() {
        assertEquals(RingReply.Unreadable, RingReplyParser.parse(null))
        assertEquals(RingReply.Unreadable, RingReplyParser.parse(ByteArray(0)))
        assertEquals(RingReply.Unreadable, RingReplyParser.parse(bytes(0x02)))
        assertEquals(RingReply.Unreadable, RingReplyParser.parse(bytes(0x02, 0x03, 0x00, 0x03)))   // truncated
        assertEquals(RingReply.Unreadable, RingReplyParser.parse(bytes(0x03, 0x02, 0x03, 0x00, 0x00, 0x00))) // big-endian misread
    }

    @Test fun onlyRingingEverClaimsTheTrackerIsRinging() {
        val notRinging = listOf(
            RingReply.Completed, RingReply.Unreadable,
            RingReply.Refused(0xFFFF), RingReply.Refused(0x0001), RingReply.Refused(0x0002)
        )
        for (r in notRinging) {
            assertFalse("$r must not claim ringing", RingReplyParser.message(r).contains("Ringing confirmed"))
        }
        assertTrue(RingReplyParser.message(RingReply.Ringing).contains("Ringing confirmed"))
    }
}
