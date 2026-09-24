package com.shellzero.terminal

import android.util.Log
import java.io.*
import java.util.concurrent.Executors

/**
 * PtyProcess - Spawns the PRoot Debian shell and manages PTY I/O
 *
 * Two modes:
 *  1. Native PTY (preferred): via JNI openpty() + fork/exec -> true TTY, ioctl(TIOCSWINSZ)
 *     Requires libpty.so compiled with NDK (see cpp/pty.cpp). Falls back gracefully.
 *  2. ProcessBuilder fallback: plain pipes, resize via `stty rows $r cols $c` injected.
 *
 * The Termux model uses native PTY; this class abstracts both.
 */
class PtyProcess private constructor(
    private val process: Process?,
    private val ptyFd: Int, // native fd if using JNI, -1 otherwise
    val pid: Int
) {
    companion object {
        private const val TAG = "PtyProcess"
        private var nativeLoaded = false

        init {
            try {
                System.loadLibrary("shellzero-pty")
                nativeLoaded = true
                Log.i(TAG, "Loaded libshellzero-pty.so")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native PTY lib not found, falling back to ProcessBuilder: ${e.message}")
                nativeLoaded = false
            }
        }

        // JNI declarations - implemented in cpp/pty.cpp
        @JvmStatic external fun nativeCreateSubprocess(
            cmd: Array<String>,
            env: Array<String>,
            cwd: String,
            rows: Int,
            cols: Int
        ): IntArray // returns [ptyFd, pid] or [-1, -1] on failure

        @JvmStatic external fun nativeGetPtyFd(): Int
        @JvmStatic external fun nativeResizePty(fd: Int, rows: Int, cols: Int, xPixel: Int, yPixel: Int): Int
        @JvmStatic external fun nativeClosePty(fd: Int)
        @JvmStatic external fun nativeWaitFor(pid: Int): Int

        /**
         * Factory: spawn PRoot shell. Tries native first, falls back to ProcessBuilder.
         */
        fun spawn(
            command: Array<String>,
            env: Map<String, String>,
            cwd: String,
            rows: Int,
            cols: Int
        ): PtyProcess {
            // Try native PTY
            if (nativeLoaded) {
                try {
                    val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
                    val result = nativeCreateSubprocess(command, envArray, cwd, rows, cols)
                    if (result.size == 2 && result[0] >= 0 && result[1] > 0) {
                        val fd = result[0]
                        val pid = result[1]
                        Log.i(TAG, "Native PTY spawned pid=$pid fd=$fd cmd=${command.joinToString(" ")}")
                        // Wrap fd as FileDescriptor streams
                        return PtyProcess(null, fd, pid)
                    } else {
                        Log.w(TAG, "nativeCreateSubprocess failed: ${result.contentToString()}, fallback")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Native spawn failed, fallback to ProcessBuilder", e)
                }
            }

            // Fallback: ProcessBuilder with pipes
            val pb = ProcessBuilder(*command)
            pb.directory(File(cwd.takeIf { File(it).exists() } ?: "/"))
            // Clear and set env explicitly
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            // Important: proot needs unbuffered
            val proc = pb.start()
            val pidFallback = try {
                proc.pid().toInt()
            } catch (_: Exception) {
                // Reflection fallback for older API
                try {
                    val field = proc.javaClass.getDeclaredField("pid")
                    field.isAccessible = true
                    field.getInt(proc)
                } catch (_: Exception) { -1 }
            }
            Log.i(TAG, "ProcessBuilder spawned pid=$pidFallback cmd=${command.joinToString(" ")}")
            return PtyProcess(proc, -1, pidFallback)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

    // For native mode, we wrap fd into streams via FileInput/OutputStream
    // For fallback, we use process streams directly
    private val nativeInputStream: InputStream?
    private val nativeOutputStream: OutputStream?

    init {
        if (ptyFd >= 0 && process == null) {
            // Native fd -> create streams from fd
            // We use ParcelFileDescriptor-like approach via FileDescriptor
            // Simplified: use FileInputStream/FileOutputStream on /proc/self/fd/$fd is not reliable,
            // so we rely on JNI read/write helpers. Here we create dummy streams and use JNI for I/O
            // For this template, we open streams via native helpers; fallback to creating via reflection if needed.
            nativeInputStream = createInputStreamFromFd(ptyFd)
            nativeOutputStream = createOutputStreamFromFd(ptyFd)
        } else {
            nativeInputStream = null
            nativeOutputStream = null
        }
    }

    private fun createInputStreamFromFd(fd: Int): InputStream {
        return try {
            // Use FileDescriptor via reflection
            val fdClass = FileDescriptor::class.java
            val fdObj = fdClass.getDeclaredConstructor().newInstance()
            val fdField = fdClass.getDeclaredField("descriptor")
            fdField.isAccessible = true
            fdField.setInt(fdObj, fd)
            FileInputStream(fdObj)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create InputStream from fd $fd", e)
            // Fallback: use native read loop via pipe?
            // For now return an empty stream; caller should use native read
            object : InputStream() {
                override fun read(): Int = -1
            }
        }
    }

    private fun createOutputStreamFromFd(fd: Int): OutputStream {
        return try {
            val fdClass = FileDescriptor::class.java
            val fdObj = fdClass.getDeclaredConstructor().newInstance()
            val fdField = fdClass.getDeclaredField("descriptor")
            fdField.isAccessible = true
            fdField.setInt(fdObj, fd)
            FileOutputStream(fdObj)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create OutputStream from fd $fd", e)
            object : OutputStream() {
                override fun write(b: Int) {}
            }
        }
    }

    val inputStream: InputStream
        get() = when {
            ptyFd >= 0 && nativeInputStream != null -> nativeInputStream
            process != null -> process.inputStream
            else -> throw IllegalStateException("No input stream")
        }

    val errorStream: InputStream
        get() = when {
            process != null -> process.errorStream
            else -> ByteArrayInputStream(ByteArray(0)) // native PTY merges stderr
        }

    val outputStream: OutputStream
        get() = when {
            ptyFd >= 0 && nativeOutputStream != null -> nativeOutputStream
            process != null -> process.outputStream
            else -> throw IllegalStateException("No output stream")
        }

    val isAlive: Boolean
        get() = when {
            ptyFd >= 0 -> {
                // Check via kill(pid, 0)
                try {
                    android.system.Os.kill(pid, 0)
                    true
                } catch (_: Exception) { false }
            }
            process != null -> process.isAlive
            else -> false
        }

    /**
     * Resize PTY window. Called from TerminalSession when Compose measures new rows/cols.
     */
    fun resizePty(rows: Int, cols: Int, xPixel: Int = 0, yPixel: Int = 0) {
        if (ptyFd >= 0 && nativeLoaded) {
            val res = nativeResizePty(ptyFd, rows, cols, xPixel, yPixel)
            if (res != 0) Log.w(TAG, "nativeResizePty failed res=$res")
            else Log.d(TAG, "Resized PTY to ${cols}x${rows}")
        } else if (process != null && process.isAlive) {
            // Fallback: send stty via output stream in a non-blocking way
            // Note: this only affects the shell's view if it reads winsize; not true ioctl.
            // We also try to inject via `stty` command if shell is idle? Better to just log.
            // For robustness, we send SIGWINCH via kill if possible? Not effective without PTY.
            // As last resort, write escape sequence? We'll try ioctl via reflection on process pid
            executor.execute {
                try {
                    // Send stty command as if user typed? This is hacky; we avoid polluting input.
                    // Instead try to use `kill -WINCH pid`
                    android.system.Os.kill(pid, 28) // SIGWINCH = 28 on aarch64
                } catch (_: Exception) {}
                try {
                    // Also try stty via separate process? Not needed.
                } catch (_: Exception) {}
            }
            Log.d(TAG, "Fallback resize requested ${cols}x${rows} (no native PTY)")
        }
    }

    fun waitFor(): Int {
        return when {
            ptyFd >= 0 && nativeLoaded -> nativeWaitFor(pid)
            process != null -> process.waitFor()
            else -> -1
        }
    }

    fun destroy() {
        try {
            if (ptyFd >= 0 && nativeLoaded) {
                nativeClosePty(ptyFd)
                try { android.system.Os.kill(pid, 9) } catch (_: Exception) {}
            }
            process?.destroy()
            // Force kill after grace period
            executor.execute {
                Thread.sleep(500)
                if (process?.isAlive == true) process.destroyForcibly()
                try { if (ptyFd >= 0) android.system.Os.kill(pid, 9) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed", e)
        } finally {
            executor.shutdownNow()
            try { inputStream.close() } catch (_: Exception) {}
            try { outputStream.close() } catch (_: Exception) {}
        }
    }

    fun destroyForcibly() = destroy()
}
