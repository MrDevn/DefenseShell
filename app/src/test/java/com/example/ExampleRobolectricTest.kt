package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("ClaudeShell", appName)
  }

  @Test
  fun `test dangerous command check`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fsEngine = com.example.domain.filesystem.FileSystemEngine(context)
    val (isDangerous, _) = fsEngine.checkDangerousCommand("rm -rf /")
    assertEquals(true, isDangerous)
  }

  @Test
  fun `test agent home directory and authorization`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fsEngine = com.example.domain.filesystem.FileSystemEngine(context)
    
    // Agent home directory check
    assertEquals(true, fsEngine.agentHomeDir.exists())
    assertEquals(true, fsEngine.isInsideHome(fsEngine.agentHomeDir.absolutePath))
    assertEquals(true, fsEngine.isInsideHome("~/test.sh"))
    assertEquals(true, fsEngine.isInsideHome("/sdcard/Defense/ClaudeShell/notes.txt"))

    // External path authorization check
    val externalPath = "/sdcard/Defense/Projects"
    val emptyGranted = emptyList<com.example.data.db.entities.GrantedFolderEntity>()
    assertEquals(false, fsEngine.isFolderAuthorized(externalPath, emptyGranted))

    val granted = listOf(
        com.example.data.db.entities.GrantedFolderEntity(
            id = "defense-1",
            folderPath = "/sdcard/Defense",
            treeUriString = "content://com.android.externalstorage.documents/tree/primary%3ADefense",
            displayName = "Defense"
        )
    )
    assertEquals(true, fsEngine.isFolderAuthorized(externalPath, granted))
  }
}
