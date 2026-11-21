package dev.astra.ime

import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whether this process may load `libastra_zenz.so`, decided before any of its code is mapped. The library is built for
 * arm64-v8a with `armv8.2-a+dotprod+fp16` (third_party/zenz/README.md), so a CPU without one of those features would
 * die on an illegal instruction, which no catch can stop. Only a 64-bit ARM process whose kernel reports every needed
 * feature qualifies; anything else, including an unreadable or unknown answer, means no zenz (the dictionary stays).
 */
internal object ZenzCpu {
    /** AT_HWCAP in the ELF auxiliary vector. */
    private const val AT_HWCAP = 16L
    private const val AT_NULL = 0L

    /**
     * arm64 HWCAP bits (Linux uapi asm/hwcap.h) the build can use: fp, asimd, crc32, atomics (LSE), fphp, asimdhp,
     * asimdrdm and asimddp. armv8.1 features are listed too, because `armv8.2-a` lets the compiler emit them.
     */
    internal const val REQUIRED_HWCAP: Long =
        (1L shl 0) or (1L shl 1) or (1L shl 7) or (1L shl 8) or (1L shl 9) or (1L shl 10) or (1L shl 12) or (1L shl 20)

    /** The same features as named on `/proc/cpuinfo` Features lines. */
    internal val REQUIRED_FEATURES = setOf("fp", "asimd", "crc32", "atomics", "fphp", "asimdhp", "asimdrdm", "asimddp")

    /** Reads the running process; call off the main thread. Never throws. */
    fun supported(): Boolean = try {
        decide(
            System.getProperty("os.arch"),
            android.os.Process.is64Bit(),
            readOrNull("/proc/self/auxv"),
            readOrNull("/proc/cpuinfo")?.toString(Charsets.UTF_8),
        )
    } catch (e: Exception) {
        false
    } catch (e: LinkageError) {
        false
    }

    /**
     * [auxv] (the raw `/proc/self/auxv`) decides when it has AT_HWCAP: it is what the kernel grants every core of
     * this process. Only without it are [cpuinfo]'s Features lines used, and then every line must list every feature.
     */
    internal fun decide(arch: String?, is64Bit: Boolean, auxv: ByteArray?, cpuinfo: String?): Boolean {
        if (!is64Bit || (arch != "aarch64" && arch != "arm64")) return false
        hwcap(auxv)?.let { return (it and REQUIRED_HWCAP) == REQUIRED_HWCAP }
        val lines = cpuinfo?.lineSequence()?.filter { it.substringBefore(':').trim() == "Features" }?.toList().orEmpty()
        if (lines.isEmpty()) return false
        return lines.all { line ->
            line.substringAfter(':').trim().split(Regex("\\s+")).toSet().containsAll(REQUIRED_FEATURES)
        }
    }

    /** AT_HWCAP from a 64-bit little-endian auxiliary vector, or null when absent or malformed. */
    internal fun hwcap(auxv: ByteArray?): Long? {
        if (auxv == null) return null
        val buffer = ByteBuffer.wrap(auxv).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 16) {
            val type = buffer.long
            val value = buffer.long
            if (type == AT_NULL) return null
            if (type == AT_HWCAP) return value
        }
        return null
    }

    private fun readOrNull(path: String): ByteArray? = try {
        FileInputStream(path).use { input ->
            val bytes = input.readBytes()
            bytes.takeIf { it.size <= MAX_READ }
        }
    } catch (e: Exception) {
        null
    }

    /** procfs files here are a few KB; anything far larger is not trusted. */
    private const val MAX_READ = 1 shl 20
}
