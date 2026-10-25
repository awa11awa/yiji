package org.ntqqrev.acidify.internal.service.file

import org.ntqqrev.acidify.internal.AbstractClient
import org.ntqqrev.acidify.internal.proto.oidb.Oidb0x6D9Req
import org.ntqqrev.acidify.internal.service.NoOutputOidbService
import org.ntqqrev.acidify.internal.util.pbEncode
import kotlin.random.Random

internal object BroadcastGroupFile : NoOutputOidbService<BroadcastGroupFile.Req>(0x6d9, 4) {
    class Req(
        val groupUin: Long,
        val fileId: String
    )

    override fun buildOidb(client: AbstractClient, payload: Req): ByteArray =
        Oidb0x6D9Req(
            broadcastBody = Oidb0x6D9Req.BroadcastBody(
                groupUin = payload.groupUin,
                type = 2,
                info = Oidb0x6D9Req.BroadcastBody.Info(
                    busiType = 102,
                    fileId = payload.fileId,
                    field3 = Random.nextInt(),
                    field5 = true,
                )
            )
        ).pbEncode()
}
