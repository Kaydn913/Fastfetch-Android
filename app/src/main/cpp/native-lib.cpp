#include <jni.h>
#include <string>
#include <sstream>
#include <fstream>
#include <iomanip>
#include <sys/utsname.h>
#include <sys/sysinfo.h>
#include <unistd.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <vulkan/vulkan.h>
#include <sys/system_properties.h>
#include <cstdio>


static std::string formatUptime(long seconds) {
    long days = seconds / 86400;
    seconds %= 86400;

    long hours = seconds / 3600;
    seconds %= 3600;

    long minutes = seconds / 60;

    std::ostringstream out;

    if (days > 0) {
        out << days << " day" << (days == 1 ? "" : "s") << ", ";
    }

    if (hours > 0 || days > 0) {
        out << hours << " hour" << (hours == 1 ? "" : "s") << ", ";
    }

    out << minutes << " min" << (minutes == 1 ? "" : "s");

    return out.str();
}

static long long readNumberFile(const std::string& path) {
    std::ifstream file(path);

    if (!file.is_open()) {
        return -1;
    }

    long long value = -1;
    file >> value;

    return value;
}

static long long getMaximumCpuFrequencyKHz() {
    long cores = sysconf(_SC_NPROCESSORS_CONF);

    if (cores < 1) {
        cores = 32;
    }

    long long highest = -1;

    // Try per-core CPUFreq directories
    for (int i = 0; i < cores; i++) {
        std::string base =
                "/sys/devices/system/cpu/cpu" +
                std::to_string(i) +
                "/cpufreq/";

        long long freq =
                readNumberFile(base + "cpuinfo_max_freq");

        if (freq <= 0) {
            freq =
                    readNumberFile(base + "scaling_max_freq");
        }

        if (freq > highest) {
            highest = freq;
        }
    }

    // Fallback to policy directories
    if (highest <= 0) {
        for (int i = 0; i < 32; i++) {
            std::string base =
                    "/sys/devices/system/cpu/cpufreq/policy" +
                    std::to_string(i) +
                    "/";

            long long freq =
                    readNumberFile(base + "cpuinfo_max_freq");

            if (freq <= 0) {
                freq =
                        readNumberFile(base + "scaling_max_freq");
            }

            if (freq > highest) {
                highest = freq;
            }
        }
    }

    return highest;
}

static long long readMeminfoKB(const std::string& key) {
    std::ifstream file("/proc/meminfo");

    if (!file.is_open()) {
        return -1;
    }

    std::string line;

    while (std::getline(file, line)) {
        if (line.rfind(key, 0) == 0) {
            std::istringstream parser(
                    line.substr(key.length())
            );

            long long value = -1;
            parser >> value;

            return value;
        }
    }

    return -1;
}

static std::string formatGiBFromKB(long long kb) {
    std::ostringstream out;

    out << std::fixed
        << std::setprecision(2)
        << (kb / 1048576.0)
        << " GiB";

    return out.str();
}

static std::string getGpuInfo() {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);

    if (display == EGL_NO_DISPLAY) {
        return "";
    }

    if (!eglInitialize(display, nullptr, nullptr)) {
        return "";
    }

    const EGLint configAttribs[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_NONE
    };

    EGLConfig config;
    EGLint configCount = 0;

    if (!eglChooseConfig(
            display,
            configAttribs,
            &config,
            1,
            &configCount
    ) || configCount == 0) {
        eglTerminate(display);
        return "";
    }

    const EGLint surfaceAttribs[] = {
            EGL_WIDTH, 1,
            EGL_HEIGHT, 1,
            EGL_NONE
    };

    EGLSurface surface =
            eglCreatePbufferSurface(
                    display,
                    config,
                    surfaceAttribs
            );

    if (surface == EGL_NO_SURFACE) {
        eglTerminate(display);
        return "";
    }

    const EGLint contextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
    };

    EGLContext context =
            eglCreateContext(
                    display,
                    config,
                    EGL_NO_CONTEXT,
                    contextAttribs
            );

    if (context == EGL_NO_CONTEXT) {
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return "";
    }

    if (!eglMakeCurrent(
            display,
            surface,
            surface,
            context
    )) {
        eglDestroyContext(display, context);
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return "";
    }

    const char* renderer =
            reinterpret_cast<const char*>(
                    glGetString(GL_RENDERER)
            );

    const char* vendor =
            reinterpret_cast<const char*>(
                    glGetString(GL_VENDOR)
            );

    const char* version =
            reinterpret_cast<const char*>(
                    glGetString(GL_VERSION)
            );

    std::ostringstream result;

    if (renderer) {
        result << "GPU: " << renderer << "\n";
    }

    if (vendor) {
        result << "GPU Vendor: " << vendor << "\n";
    }

    if (version) {
        result << "OpenGL ES: " << version << "\n";
    }

    eglMakeCurrent(
            display,
            EGL_NO_SURFACE,
            EGL_NO_SURFACE,
            EGL_NO_CONTEXT
    );

    eglDestroyContext(display, context);
    eglDestroySurface(display, surface);
    eglTerminate(display);

    return result.str();
}

static std::string getVulkanInfo() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Fastfetch Android";
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;

    if (vkCreateInstance(&createInfo, nullptr, &instance) != VK_SUCCESS) {
        return "";
    }

    uint32_t deviceCount = 0;

    if (
            vkEnumeratePhysicalDevices(
                    instance,
                    &deviceCount,
                    nullptr
            ) != VK_SUCCESS ||
            deviceCount == 0
            ) {
        vkDestroyInstance(instance, nullptr);
        return "";
    }

    VkPhysicalDevice device = VK_NULL_HANDLE;
    uint32_t oneDevice = 1;

    vkEnumeratePhysicalDevices(
            instance,
            &oneDevice,
            &device
    );

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(
            device,
            &properties
    );

    uint32_t version = properties.apiVersion;

    std::ostringstream result;

    result
            << "Vulkan: "
            << VK_API_VERSION_MAJOR(version)
            << "."
            << VK_API_VERSION_MINOR(version)
            << "."
            << VK_API_VERSION_PATCH(version)
            << "\n";

    vkDestroyInstance(instance, nullptr);

    return result.str();
}

static std::string getSelinuxStatus() {
    FILE* pipe = popen("/system/bin/getenforce", "r");

    if (!pipe) {
        return "Unknown";
    }

    char buffer[128] = {0};

    if (fgets(buffer, sizeof(buffer), pipe) == nullptr) {
        pclose(pipe);
        return "Unknown";
    }

    pclose(pipe);

    std::string result(buffer);

    while (!result.empty() &&
           (result.back() == '\n' || result.back() == '\r')) {
        result.pop_back();
    }

    if (result.empty()) {
        return "Unknown";
    }

    return result;
}

static std::string getSystemProperty(const char* name) {
    char value[PROP_VALUE_MAX] = {0};

    if (__system_property_get(name, value) > 0) {
        return std::string(value);
    }

    return "";
}

static std::string getBootloaderStatus() {
    std::string locked =
            getSystemProperty("ro.boot.flash.locked");

    if (locked == "1") {
        return "Locked";
    }

    if (locked == "0") {
        return "Unlocked";
    }

    return "Unknown";
}

static std::string getVerifiedBootStatus() {
    std::string state =
            getSystemProperty("ro.boot.verifiedbootstate");

    if (state.empty()) {
        return "Unknown";
    }

    return state;
}

static std::string getRootStatus() {
    const char* suPaths[] = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system_ext/bin/su",
            "/product/bin/su",
            "/vendor/bin/su",
            "/data/local/bin/su",
            "/data/local/xbin/su"
    };

    for (const char* path : suPaths) {
        if (access(path, F_OK) == 0) {
            return "Detected [su]";
        }
    }

    std::string buildType =
            getSystemProperty("ro.build.type");

    if (buildType == "eng" ||
        buildType == "userdebug") {
        return "Possible [Debug Build]";
    }

    return "Not detected [Heuristic]";
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kaydn_fastfetchandroid_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {

    std::ostringstream output;

    // Kernel + architecture
    struct utsname systemInfo{};

    if (uname(&systemInfo) == 0) {
        output
                << "Kernel: "
                << systemInfo.sysname
                << " "
                << systemInfo.release
                << "\n"

                << "Architecture: "
                << systemInfo.machine
                << "\n";
    } else {
        output << "Kernel: Unable to detect\n";
    }

    // Uptime
    struct sysinfo info{};

    if (sysinfo(&info) == 0) {
        output
                << "Uptime: "
                << formatUptime(info.uptime)
                << "\n";
    }

    // CPU
    long configuredCores =
            sysconf(_SC_NPROCESSORS_CONF);

    long onlineCores =
            sysconf(_SC_NPROCESSORS_ONLN);

    long long maxFrequencyKHz =
            getMaximumCpuFrequencyKHz();

    if (configuredCores > 0) {
        output
                << "CPU: "
                << configuredCores
                << " cores";

        if (maxFrequencyKHz > 0) {
            output
                    << " @ "
                    << std::fixed
                    << std::setprecision(2)
                    << (maxFrequencyKHz / 1000000.0)
                    << " GHz";
        }

        if (
                onlineCores > 0 &&
                onlineCores != configuredCores
                ) {
            output
                    << " ["
                    << onlineCores
                    << " online]";
        }

        output << "\n";
    }
    std::string gpuInfo = getGpuInfo();


    if (!gpuInfo.empty()) {
        output << gpuInfo;
    }
    std::string vulkanInfo = getVulkanInfo();

    if (!vulkanInfo.empty()) {
        output << vulkanInfo;
    }
    output
            << "SELinux: "
            << getSelinuxStatus()
            << "\n";

    output
            << "Bootloader: "
            << getBootloaderStatus()
            << "\n";

    output
            << "Verified Boot: "
            << getVerifiedBootStatus()
            << "\n";

    output
            << "Root: "
            << getRootStatus()
            << "\n";

    // Swap / zRAM
    long long swapTotalKB =
            readMeminfoKB("SwapTotal:");

    long long swapFreeKB =
            readMeminfoKB("SwapFree:");

    if (
            swapTotalKB > 0 &&
            swapFreeKB >= 0
            ) {
        long long swapUsedKB =
                swapTotalKB - swapFreeKB;

        int swapPercent =
                static_cast<int>(
                        (
                                static_cast<double>(swapUsedKB) /
                                static_cast<double>(swapTotalKB)
                        ) * 100.0
                );

        output
                << "Swap: "
                << formatGiBFromKB(swapUsedKB)
                << " / "
                << formatGiBFromKB(swapTotalKB)
                << " ("
                << swapPercent
                << "%)"
                << "\n";
    }

    std::string result = output.str();

    return env->NewStringUTF(
            result.c_str()
    );
}