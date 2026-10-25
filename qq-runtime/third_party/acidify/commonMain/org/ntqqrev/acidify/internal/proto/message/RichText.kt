package org.ntqqrev.acidify.internal.proto.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
internal class RichText(
    @ProtoNumber(1) val attr: Attr? = null,
    @ProtoNumber(2) val elems: List<Elem> = emptyList(),
    @ProtoNumber(3) val notOnlineFile: NotOnlineFile? = null,
    @ProtoNumber(4) val ptt: Ptt? = null,
    @ProtoNumber(5) val tmpPtt: TmpPtt? = null,
    @ProtoNumber(6) val trans211TmpMsg: Trans211TmpMsg? = null,
)
