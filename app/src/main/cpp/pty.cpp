// VASTAVIK CLI - Native PTY (arm64-v8a only)
// Minimal JNI bridge: openpty + fork + exec for true TTY ioctl support.
// Compile with NDK: add to CMakeLists.txt -> add_library(VASTAVIK CLI-pty SHARED pty.cpp)
// This gives correct TIOCSWINSZ handling and merges pty I/O.

#include <jni.h>
#include <pty.h>
#include <utmp.h>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "VASTAVIK CLI-Pty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static int g_ptyFd = -1;
static pid_t g_pid = -1;

extern "C" {

// Create subprocess with PTY
// cmd: String[], env: String[] (KEY=VALUE), cwd, rows, cols
// Returns int[]{ptyFd, pid}
JNIEXPORT jintArray JNICALL
Java_com_VASTAVIK CLI_terminal_PtyProcess_nativeCreateSubprocess(
    JNIEnv* env, jclass,
    jobjectArray cmdArray,
    jobjectArray envArray,
    jstring cwdStr,
    jint rows, jint cols) {

    int masterFd = -1, slaveFd = -1;
    struct winsize ws{};
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    if (openpty(&masterFd, &slaveFd, nullptr, nullptr, &ws) != 0) {
        LOGW("openpty failed: %d", errno);
        jintArray res = env->NewIntArray(2);
        jint v[2] = {-1, -1};
        env->SetIntArrayRegion(res, 0, 2, v);
        return res;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGW("fork failed: %d", errno);
        close(masterFd); close(slaveFd);
        jintArray res = env->NewIntArray(2);
        jint v[2] = {-1, -1};
        env->SetIntArrayRegion(res, 0, 2, v);
        return res;
    }

    if (pid == 0) {
        // Child
        close(masterFd);
        setsid();
        if (ioctl(slaveFd, TIOCSCTTY, 0) != 0) {
            LOGW("TIOCSCTTY failed");
        }
        dup2(slaveFd, STDIN_FILENO);
        dup2(slaveFd, STDOUT_FILENO);
        dup2(slaveFd, STDERR_FILENO);
        if (slaveFd > 2) close(slaveFd);

        // Set cwd
        const char* cwd = env->GetStringUTFChars(cwdStr, nullptr);
        if (cwd) {
            chdir(cwd);
            env->ReleaseStringUTFChars(cwdStr, cwd);
        }

        // Build cmd argc
        jsize cmdLen = env->GetArrayLength(cmdArray);
        std::vector<std::string> cmdStrs;
        std::vector<char*> argv;
        for (jsize i=0;i<cmdLen;i++) {
            auto s = (jstring) env->GetObjectArrayElement(cmdArray, i);
            const char* c = env->GetStringUTFChars(s, nullptr);
            cmdStrs.emplace_back(c);
            env->ReleaseStringUTFChars(s, c);
        }
        for (auto &s: cmdStrs) argv.push_back(const_cast<char*>(s.c_str()));
        argv.push_back(nullptr);

        // Build envp
        jsize envLen = env->GetArrayLength(envArray);
        std::vector<std::string> envStrs;
        std::vector<char*> envp;
        for (jsize i=0;i<envLen;i++) {
            auto s = (jstring) env->GetObjectArrayElement(envArray, i);
            const char* c = env->GetStringUTFChars(s, nullptr);
            envStrs.emplace_back(c);
            env->ReleaseStringUTFChars(s, c);
        }
        for (auto &s: envStrs) envp.push_back(const_cast<char*>(s.c_str()));
        envp.push_back(nullptr);

        execve(argv[0], argv.data(), envp.data());
        // If execve fails
        LOGW("execve failed for %s: %d", argv[0], errno);
        _exit(127);
    }

    // Parent
    close(slaveFd);
    g_ptyFd = masterFd;
    g_pid = pid;
    LOGI("pty fork success masterFd=%d pid=%d", masterFd, pid);

    jintArray res = env->NewIntArray(2);
    jint v[2] = {masterFd, (jint) pid};
    env->SetIntArrayRegion(res, 0, 2, v);
    return res;
}

JNIEXPORT jint JNICALL
Java_com_VASTAVIK CLI_terminal_PtyProcess_nativeGetPtyFd(JNIEnv*, jclass) {
    return g_ptyFd;
}

JNIEXPORT jint JNICALL
Java_com_VASTAVIK CLI_terminal_PtyProcess_nativeResizePty(JNIEnv*, jclass, jint fd, jint rows, jint cols, jint xpix, jint ypix) {
    struct winsize ws{};
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = (unsigned short) xpix;
    ws.ws_ypixel = (unsigned short) ypix;
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) return -1;
    if (g_pid > 0) kill(g_pid, SIGWINCH);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_VASTAVIK CLI_terminal_PtyProcess_nativeClosePty(JNIEnv*, jclass, jint fd) {
    if (fd >= 0) close(fd);
    if (fd == g_ptyFd) g_ptyFd = -1;
}

JNIEXPORT jint JNICALL
Java_com_VASTAVIK CLI_terminal_PtyProcess_nativeWaitFor(JNIEnv*, jclass, jint pid) {
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, 0);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

} // extern "C"
