package com.example.domain.terminal

import android.content.Context
import com.example.domain.filesystem.FileSystemEngine
import java.io.File

/**
 * Extension point for real proot-based Linux virtualization (the way UserLand
 * and Termux actually isolate their environment), as opposed to the current
 * approach of shelling out to Android's own /system/bin/sh.
 *
 * NOT WIRED UP YET. This class only prepares the directory layout; it does not
 * execute anything through proot. TerminalEngine still runs commands directly
 * via ProcessBuilder against the real Android shell.
 *
 * To make this real, two binary assets that cannot be generated from source —
 * they need to be downloaded and bundled — are required:
 *
 * 1. A static `proot` binary for each ABI the app ships (arm64-v8a, armeabi-v7a,
 *    x86_64), placed under app/src/main/jniLibs/<abi>/libproot.so (Android only
 *    allows executing files from jniLibs/ or files your app wrote itself with
 *    the executable bit, since /data partitions are mounted `noexec` for
 *    arbitrarily-placed files on modern Android).
 * 2. A minimal Linux rootfs archive (e.g. Alpine minirootfs or a Debian/Ubuntu
 *    base tarball for the matching architecture), shipped as a raw asset or
 *    fetched on first run, then extracted into [FileSystemEngine.linuxRootDir].
 *
 * Once those exist, the flow is:
 *   1. On first launch, extract the rootfs tarball into linuxRootDir if
 *      linuxRootDir/usr doesn't exist yet (idempotent bootstrap).
 *   2. Instead of `ProcessBuilder("sh", "-c", cmd)`, run:
 *        ProcessBuilder(
 *            prootBinaryPath,
 *            "-r", linuxRootDir.absolutePath,
 *            "-b", "/dev", "-b", "/proc", "-b", "/sys",
 *            "-w", "/home/codestudio",
 *            "-0",
 *            "/bin/sh", "-c", cmd
 *        )
 *   3. Inside that proot call, /home/codestudio, /tmp, /etc etc. become real
 *      paths *from the shell's point of view* — cd/pwd/ls all see a genuine
 *      Linux tree, with its own coreutils/busybox rather than Android's.
 *
 * This sandbox environment has no network access and cannot fetch or verify
 * those binaries, so this class is left as documented scaffolding rather than
 * a working implementation. [FileSystemEngine] already creates the directory
 * layout (linuxRootDir/home/codestudio, /tmp, /etc, /var/log) this would bind
 * into.
 */
class ProotEnvironment(
    private val context: Context,
    private val fileSystemEngine: FileSystemEngine
) {
    val isBootstrapped: Boolean
        get() = File(fileSystemEngine.linuxRootDir, "usr").exists()

    /** Placeholder — see class doc. Returns false until real assets are added. */
    fun bootstrapIfNeeded(): Boolean {
        return isBootstrapped
    }
}
