package dev.astra.ime

import dev.astra.ime.oss.zenz.ZenzNative
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * zenz judge: a zenz GGUF and the arm64 `libastra_zenz.so` bundled in assets/zenz are copied once to app storage
 * (llama.cpp needs a file path; the library is not in the APK's lib/, so the APK installs on every ABI), checked
 * against the bundled SHA-256 both when copied and when reused, then the library is loaded by its absolute path.
 * Before any of that, [ZenzCpu] must confirm this process can run the library; an unsupported or unknown CPU copies
 * and loads nothing. Missing assets, an unsupported CPU or any failure leave conversion to the dictionary alone.
 * Nothing is logged.
 */
internal object ZenzBackend {
    const val ASSET_DIR = "zenz"
    const val HASHES = "zenz-assets.sha256"
    /** Path below [ASSET_DIR], and the library's name in [HASHES] and android/app/zenz-assets.lock. */
    const val LIBRARY = "arm64-v8a/libastra_zenz.so"
    /** Below the storage directory; each copy is named by its hash, so a copy is never rewritten in place. */
    const val NATIVE_DIR = "native"
    private const val LIBRARY_PREFIX = "libastra_zenz-"
    private const val THREADS = 4
    /** Temporary copies left by a killed process are removed once this old; a younger one may still be written. */
    private const val STALE_TMP_MILLIS = 60 * 60 * 1000L

    enum class LoadState { MISSING, UNSUPPORTED, READY, FAILED }

    class Loaded(val state: LoadState, val scorer: ZenzNative?, val millis: Long)

    /**
     * Call off the main thread. [open] reads a path below [ASSET_DIR]; [storage] is a private directory. [supported]
     * is asked first, before anything is copied; [openNative] gets the absolute paths of the verified model and library.
     */
    fun load(
        open: (String) -> InputStream,
        exists: (String) -> Boolean,
        storage: File,
        supported: () -> Boolean = ZenzCpu::supported,
        openNative: (model: String, library: String) -> ZenzNative? = { model, library ->
            ZenzNative.open(model, THREADS) { System.load(library) }
        },
    ): Loaded {
        if (!exists(HASHES)) return Loaded(LoadState.MISSING, null, 0)
        if (!supported()) return Loaded(LoadState.UNSUPPORTED, null, 0)
        val started = System.nanoTime()
        return try {
            val expected = open(HASHES).use { OssBackend.parseHashes(it) }
            val (name, sha) = expected.entries.singleOrNull { it.key.endsWith(".gguf") }?.toPair()
                ?: throw IOException("zenz model not listed")
            require(name.matches(Regex("[A-Za-z0-9_.-]+\\.gguf")))
            val librarySha = expected[LIBRARY] ?: throw IOException("zenz library not listed")
            storage.mkdirs()
            val model = provide(open, name, sha, File(storage, name))
            val library = provide(open, LIBRARY, librarySha, File(File(storage, NATIVE_DIR), "$LIBRARY_PREFIX${librarySha.take(16)}.so"))
            removeStale(model)
            removeStale(library)
            val scorer = openNative(model.absolutePath, library.absolutePath) ?: return Loaded(LoadState.FAILED, null, 0)
            Loaded(LoadState.READY, scorer, (System.nanoTime() - started) / 1_000_000)
        } catch (e: Exception) {
            Loaded(LoadState.FAILED, null, 0)
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError: the library could not be loaded (or a JVM test environment).
            Loaded(LoadState.FAILED, null, 0)
        } catch (e: OutOfMemoryError) {
            Loaded(LoadState.FAILED, null, 0)
        }
    }

    /** [target] holding exactly [sha]: reused when it does, otherwise copied again from the asset [name]. */
    private fun provide(open: (String) -> InputStream, name: String, sha: String, target: File): File {
        if (target.isFile && sha256(target.inputStream()) == sha) return target
        if (target.exists()) {
            target.setWritable(true)
            target.delete()
        }
        install(open, name, sha, target)
        if (sha256(target.inputStream()) != sha) throw IOException("zenz asset hash mismatch")
        return target
    }

    /**
     * Copies into a temporary file of its own (another process may be doing the same), syncs and verifies it, makes it
     * read-only and moves it into place in one step, so [target] is never seen half written.
     */
    private fun install(open: (String) -> InputStream, name: String, sha: String, target: File) {
        val dir = target.parentFile!!.apply { mkdirs() }
        val tmp = File.createTempFile("${target.name}.", ".tmp", dir)
        try {
            open(name).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output, 1 shl 16)
                    output.fd.sync()
                }
            }
            if (sha256(tmp.inputStream()) != sha) throw IOException("zenz asset hash mismatch")
            // Never written after this; Android 14+ also expects dynamically loaded code to be read-only.
            if (!tmp.setReadOnly()) throw IOException("zenz asset not made read-only")
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                // Where a rename cannot replace, another process may have put the same verified bytes in place first.
                if (!(target.isFile && sha256(target.inputStream()) == sha)) throw e
            }
        } finally {
            if (tmp.exists()) {
                tmp.setWritable(true)
                tmp.delete()
            }
        }
    }

    /** Other versions' library copies and old abandoned temporary files in [current]'s directory. */
    private fun removeStale(current: File) {
        val now = System.currentTimeMillis()
        current.parentFile?.listFiles()?.forEach { file ->
            val oldLibrary = file.name.startsWith(LIBRARY_PREFIX) && file.name.endsWith(".so") && file.name != current.name
            val oldTmp = file.name.endsWith(".tmp") && now - file.lastModified() > STALE_TMP_MILLIS
            if (oldLibrary || oldTmp) file.delete()
        }
    }

    private fun sha256(input: InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
