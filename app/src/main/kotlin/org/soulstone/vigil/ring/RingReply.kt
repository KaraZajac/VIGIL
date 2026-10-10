package org.soulstone.vigil.ring

/**
 * What a tracker's reply to a ring command actually says — parsed, never assumed.
 *
 * VIGIL used to treat ANY indication on the ring characteristic as proof that the
 * tracker was ringing. But a DULT accessory answers every Sound_Start with a
 * Command_Response, including the refusal it is required to send outside the
 * separated state, so a tag sitting with its owner showed "Ringing confirmed" while
 * staying silent. Only a reply that says Success for Sound_Start is a confirmation.
 *
 * Wire format, from IETF draft-ietf-dult-accessory-protocol-00: a 2-byte opcode then
 * operands (§3.12.1, Table 5), little-endian (§3.11.1), with the non-owner opcodes
 * and Command_Response operands of §3.13.4 (Tables 18 and 19):
 *
 *   Command_Response  0x0302 | CommandOpCode u16 | ResponseStatus u16   (6 bytes)
 *   Sound_Completed   0x0303                                             (2 bytes)
 *
 * Kept free of Android types so it is unit-testable on the JVM.
 */
sealed interface RingReply {
    /** The tracker accepted Sound_Start. The only reply that confirms ringing. */
    data object Ringing : RingReply

    /** The tracker reports it has finished playing its sound. */
    data object Completed : RingReply

    /** The tracker rejected the command, with the DULT ResponseStatus it gave. */
    data class Refused(val status: Int) : RingReply

    /** A reply VIGIL cannot verify, which therefore must not be reported as ringing. */
    data object Unreadable : RingReply
}

object RingReplyParser {
    const val OP_SOUND_START = 0x0300
    const val OP_COMMAND_RESPONSE = 0x0302
    const val OP_SOUND_COMPLETED = 0x0303

    const val STATUS_SUCCESS = 0x0000
    /** §3.13.4.1: a sound is already playing, or one this device did not start. */
    const val STATUS_INVALID_STATE = 0x0001
    /** §3.13.4: what a tag must return to Sound_Start outside the separated state. */
    const val STATUS_INVALID_COMMAND = 0xFFFF

    fun parse(value: ByteArray?): RingReply {
        if (value == null || value.size < 2) return RingReply.Unreadable
        return when (u16(value, 0)) {
            OP_COMMAND_RESPONSE -> {
                if (value.size < 6) return RingReply.Unreadable
                val command = u16(value, 2)
                val status = u16(value, 4)
                when {
                    // Any error status is a refusal, whichever command it echoes.
                    status != STATUS_SUCCESS -> RingReply.Refused(status)
                    command == OP_SOUND_START -> RingReply.Ringing
                    // Success, but for something other than Sound_Start: proves nothing.
                    else -> RingReply.Unreadable
                }
            }
            OP_SOUND_COMPLETED -> RingReply.Completed
            else -> RingReply.Unreadable
        }
    }

    /** What to tell the user. Only [RingReply.Ringing] may claim the tracker is ringing. */
    fun message(reply: RingReply): String = when (reply) {
        RingReply.Ringing -> "Ringing confirmed - listen for the tracker."
        RingReply.Completed -> "The tracker reported it played its sound."
        is RingReply.Refused -> when (reply.status) {
            STATUS_INVALID_COMMAND ->
                "The tracker refused to ring. Tags only ring while separated from their owner, so it may be with its owner right now."
            STATUS_INVALID_STATE ->
                "The tracker refused to ring because it says a sound is already playing."
            else -> "The tracker refused to ring (error 0x%04X).".format(reply.status)
        }
        RingReply.Unreadable ->
            "The tracker replied, but VIGIL couldn't confirm it is ringing - listen for it."
    }

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}
