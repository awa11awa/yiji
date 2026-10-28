package cn.yiji.app.qq

import android.content.pm.PackageInfo
import android.content.pm.Signature
import dev.yiji.qqlocalprobe.QqPackageTrust
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class QqPackageTrustTest {
    private fun info(version: String = "9.3.75") = PackageInfo().apply {
        packageName = QqPackageTrust.PACKAGE; versionName = version
    }
    private fun official(): Signature = Signature(java.util.Base64.getDecoder().decode(
        javaClass.getResourceAsStream("/qq-official-certificate.b64")!!.bufferedReader().readText().trim()))

    @Test fun independentlyDownloadedTencentCertificateIsAccepted() {
        QqPackageTrust.verify(info(), arrayOf(official()))
    }
    @Test fun samePackageAndVersionWithAnotherCertificateIsRejected() {
        assertThrows(SecurityException::class.java) { QqPackageTrust.verify(info(), arrayOf(Signature(byteArrayOf(1, 2, 3)))) }
    }
    @Test fun unsupportedVersionAndUnverifiableSignersAreRejected() {
        assertThrows(IllegalStateException::class.java) { QqPackageTrust.verify(info("9.3.76"), arrayOf(official())) }
        assertThrows(SecurityException::class.java) { QqPackageTrust.verify(info(), null) }
        assertThrows(SecurityException::class.java) { QqPackageTrust.verify(info(), arrayOf(official(), official())) }
    }
}
