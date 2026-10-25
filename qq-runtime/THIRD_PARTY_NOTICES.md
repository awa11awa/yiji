# Protocol implementation references

The protocol-12 encoder, protocol-12/13 response layout and secure protobuf
field definitions in the QQ adapter are adapted from Acidify:

- Repository: https://github.com/LagrangeDev/acidify
- Commit: d203aa5075ee0e62d687c3f4de72b5ebbde2d546
- Relevant source: acidify-core/src/commonMain/kotlin/org/ntqqrev/acidify/internal/context/PacketContext.kt
- TEA reference: acidify-core/src/commonMain/kotlin/org/ntqqrev/acidify/internal/crypto/tea/TEA.kt
- License: GNU General Public License version 3; a copy is provided in LICENSE.

The Java implementation, host integration, Android UI and tests are distributed
under GPL-3.0 with this package. Original Acidify contributors retain the
copyright to their work. This package is independent of Acidify and Tencent.

Host-only interoperability tests use github.com/fumiama/gofastTEA v0.1.3,
also GPL-3.0, as an independent TEA implementation. Its Go binary and source
are not packaged in the APK. The test dependency is pinned with go.mod/go.sum.

The APK does not redistribute Tencent's APK, native libraries or class
definitions. It loads the user's locally installed QQ libraries at runtime.
The ChannelProxy declaration in compile-stubs is a compile-only API signature
written for this adapter and is excluded from both application DEX files.

## v0.3.0 Android login and message client

Acidify core 1.6.3 (GPL-3.0) is rebuilt from its published source artifact:
https://repo.maven.apache.org/maven2/org/ntqqrev/acidify-core-jvm/1.6.3/acidify-core-jvm-1.6.3-sources.jar
The SHA-256 is pinned in login-dependencies.json. Corresponding source, including
our Android changes, is supplied in third_party/acidify in the source archive.
Original authors and contributors retain their copyrights. Changes are:

- Compile for Java 8 APIs and bytecode, and supply the JVM atomicfu runtime.
- Use Android device values in the online report and WtLogin device TLVs.
- Encode the SDK build time from the installed QQ metadata.
- Handle a direct SMS challenge after the first password request.
- In v0.3.2, preserve queued pushes and events without DROP_OLDEST; remove
  transport-sequence-only push suppression; archive original MsgPush bytes
  before decoding; retain unsupported message elements and quoted/following
  text; expose raw message fields and multipart/long-text metadata; pass the
  Android phone/Pad identity to long-message resource retrieval.

The TLV544 energy salt layout is based on fuqiuluo/unidbg-fetch-qsign (GPL-3.0),
using this preserved source reference:
https://github.com/MarindaCloud/unidbg-fetch-qsign/blob/a8fb28c2202a1480a96b3e72d4edaf79a79bc743/src/main/kotlin/moe/fuqiuluo/api/energy.kt
No unidbg emulator or remote signature service is included.

Bundled runtime dependencies and versions are listed in login-dependencies.json
under sets.runtime; acidify-core-jvm is replaced by the source build above.

- Kotlin, kotlinx libraries and JetBrains annotations: JetBrains and contributors,
  Apache-2.0 (https://github.com/JetBrains/kotlin,
  https://github.com/Kotlin/kotlinx.coroutines,
  https://github.com/Kotlin/kotlinx.serialization,
  https://github.com/Kotlin/kotlinx-io, https://github.com/Kotlin/kotlinx-atomicfu).
- Ktor: JetBrains and contributors, Apache-2.0 (https://github.com/ktorio/ktor).
- XMLUtil: XMLUtil contributors, Apache-2.0 (https://github.com/pdvrieze/xmlutil).
  In core-jvm 1.0.0-rc3, two java.util.List.removeLast invocations in
  InjectingInOutBuffer are rewritten to Kotlin CollectionsKt.removeLast(List),
  preserving last-element removal semantics for Android API 26. The exact
  version and transformation source are supplied in tools/BackportXmlutil.java.
- Kompress: Karma Krafts and contributors, Apache-2.0
  (https://github.com/karmakrafts/Kompress).
- SLF4J API: QOS.ch and contributors, MIT. Original copyright and license text
  copied from the release JAR is supplied in third_party/licenses/SLF4J-MIT.txt.

Apache-2.0 and MIT license texts are included in third_party/licenses and in
the APK's assets/qq-licenses. The root LICENSE supplies GPL-3.0. Build tools and
the Kotlin compiler are downloaded for building and are not bundled in the APK.

## Yiji v2.2.0 integration changes

The receive-only integration adds an account/conversation selection callback before
message segment decoding, avoids group-member cache refresh, and exposes transport
connection generations for on-demand history reconciliation. Yiji stores selected
messages as full text and compressed typed/raw-message JSON; it does not persist
unselected MsgPush payloads. The standalone probe's optional wire-payload archive
is not included. The Java host glue uses a blocking native-request queue and a
rotating diagnostic log. All corresponding modified protocol source is supplied.
