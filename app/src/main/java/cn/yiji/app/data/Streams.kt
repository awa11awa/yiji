package cn.yiji.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "文件内容过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
