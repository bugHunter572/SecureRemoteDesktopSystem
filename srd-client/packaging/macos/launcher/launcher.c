/*
 * SecureRemoteDesktopClient.app/Contents/MacOS/SecureRemoteDesktopClient
 *
 * Native launcher: starts the bundled Java runtime INSIDE this process (libjli's JLI_Launch, the
 * same entry point the `java` command and jpackage launchers use). The app then runs as one
 * signed process with the app's identity (com.srd.client), so macOS privacy permissions (Screen
 * Recording, Accessibility) are granted to and checked against the same identity. The old shell
 * script exec'd a separately signed `java`, and the permission switched on in System Settings did
 * not match what the running process was checked against, so the app kept asking for it.
 *
 * Config, logs and the single-instance lock live in ~/Library/Application Support (the .app may be
 * read-only). Built universal (arm64 + x86_64) by build/build_macos_audio.sh.
 */
#include <dlfcn.h>
#include <errno.h>
#include <libgen.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef int (*JLI_Launch_t)(int argc, char **argv, int jargc, const char **jargv, int appclassc,
                            const char **appclassv, const char *fullversion, const char *dotversion,
                            const char *pname, const char *lname, unsigned char javaargs,
                            unsigned char cpwildcard, unsigned char javaw, int ergo);

static void mkdirs(const char *path) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            mkdir(tmp, 0755);
            *p = '/';
        }
    }
    mkdir(tmp, 0755);
}

int main(int argc, char **argv) {
    char exe[PATH_MAX], real[PATH_MAX];
    uint32_t size = sizeof exe;
    if (_NSGetExecutablePath(exe, &size) != 0 || realpath(exe, real) == NULL) {
        fprintf(stderr, "SecureRemoteDesktopClient: cannot resolve executable path\n");
        return 1;
    }
    /* .../X.app/Contents/MacOS/SecureRemoteDesktopClient -> .../X.app */
    char app[PATH_MAX];
    snprintf(app, sizeof app, "%s", real);
    for (int i = 0; i < 3; i++) {
        char *slash = strrchr(app, '/');
        if (slash) *slash = 0;
    }

    const char *home = getenv("HOME");
    if (home) {
        char data[PATH_MAX];
        snprintf(data, sizeof data, "%s/Library/Application Support/SecureRemoteDesktop/Client", home);
        mkdirs(data);
        if (chdir(data) != 0) fprintf(stderr, "SecureRemoteDesktopClient: chdir %s: %s\n", data, strerror(errno));
    }

    char jli[PATH_MAX], jar[PATH_MAX], bundleProp[PATH_MAX + 32];
    snprintf(jli, sizeof jli, "%s/Contents/runtime/Contents/Home/lib/libjli.dylib", app);
    snprintf(jar, sizeof jar, "%s/Contents/Resources/SecureRemoteDesktopClient.jar", app);
    snprintf(bundleProp, sizeof bundleProp, "-Dsrd.appBundle=%s", app);

    void *lib = dlopen(jli, RTLD_NOW | RTLD_GLOBAL);
    if (!lib) {
        fprintf(stderr, "SecureRemoteDesktopClient: cannot load the bundled Java runtime: %s\n", dlerror());
        return 1;
    }
    JLI_Launch_t launch = (JLI_Launch_t) dlsym(lib, "JLI_Launch");
    if (!launch) {
        fprintf(stderr, "SecureRemoteDesktopClient: JLI_Launch missing: %s\n", dlerror());
        return 1;
    }

    const char *fixed[] = {
        "java",
        "-Xms128m", "-Xmx768m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=10",
        "-Dapple.awt.UIElement=true",          /* menu-bar app: no Dock icon */
        "-Dapple.awt.application.name=Secure Remote Desktop",
        bundleProp,
        "-jar", jar,
    };
    int nfixed = (int) (sizeof fixed / sizeof fixed[0]);
    char **args = calloc((size_t) (nfixed + argc), sizeof(char *));
    int n = 0;
    for (int i = 0; i < nfixed; i++) args[n++] = (char *) fixed[i];
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "-psn_", 5) == 0) continue; /* old Finder process-serial argument */
        args[n++] = argv[i];
    }
    args[n] = NULL;
    return launch(n, args, 0, NULL, 0, NULL, "", "", "java", "java", 0, 0, 0, 0);
}
