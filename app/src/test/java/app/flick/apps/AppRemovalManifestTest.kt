package app.flick.apps

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRemovalManifestTest {
    @Test fun removalRequestsUseAndroidConfirmationWithoutPrivilegedDeletion() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = document.getElementsByTagName("uses-permission")
        val permissions = (0 until nodes.length).map { index ->
            nodes.item(index).attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "name").nodeValue
        }
        assertTrue(permissions.contains("android.permission.REQUEST_DELETE_PACKAGES"))
        assertFalse(permissions.contains("android.permission.DELETE_PACKAGES"))
    }
}
