package org.ntqqrev.acidify.event

/** Original MsgPush protobuf, emitted before optional content decoding. */
data class MessagePushEvent(val sequence: Int, val payload: ByteArray) : AcidifyEvent
