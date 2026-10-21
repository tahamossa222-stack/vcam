// language: C++, file: NativeBridge.cpp, target: NDK r28+, Android 11-15
// Single translation unit: EglCore, BufferRing, SurfaceSink, session thread, JNI.

#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
// Core GL types (GLenum, GLsizei, GLuint) must be visible before any
// extension header is parsed. gl3.h pulls them in for the ES3 entry points,
// gl2.h restates the ES2 set, and gl2ext.h is parsed last.
#include <GLES3/gl3.h>
#include <GLES2/gl2.h>
#define GL_GLEXT_PROTOTYPES 1
#include <GLES2/gl2ext.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <future>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <time.h>
#include <unistd.h>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#define VCAM_TAG "vcam-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, VCAM_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, VCAM_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VCAM_TAG, __VA_ARGS__)

namespace vcam {

// ===========================================================================
// Status
// ===========================================================================

struct Status {
    bool ok = true;
    bool retryable = false;
    std::string message;

    static Status success() { return {}; }
    static Status drop(std::string m) { return {false, true, std::move(m)}; }
    static Status fail(std::string m) { return {false, false, std::move(m)}; }

    explicit operator bool() const { return ok; }
};

// ===========================================================================
// EglCore
// ===========================================================================

struct EglProcs {
    PFNEGLCREATEIMAGEKHRPROC               create_image = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC              destroy_image = nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC get_native_client_buffer = nullptr;
    PFNEGLCREATESYNCKHRPROC                create_sync = nullptr;
    PFNEGLDESTROYSYNCKHRPROC               destroy_sync = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC      dup_native_fence_fd = nullptr;
    PFNEGLCLIENTWAITSYNCKHRPROC            client_wait_sync = nullptr;
    PFNEGLWAITSYNCKHRPROC                  wait_sync = nullptr;
};

class EglCore {
public:
    static std::unique_ptr<EglCore> create(Status* st);
    ~EglCore();

    EglCore(const EglCore&) = delete;
    EglCore& operator=(const EglCore&) = delete;

    EGLDisplay display() const { return display_; }
    EGLConfig config() const { return config_; }
    const EglProcs& procs() const { return procs_; }

    bool has_extension(const char* name) const;
    EGLContext create_context(EGLContext share, Status* st);
    EGLSurface scratch_surface(Status* st);
    bool make_current(EGLSurface draw, EGLContext ctx, Status* st);
    void clear_current();

private:
    EglCore() = default;
    void destroy();

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLSurface scratch_ = EGL_NO_SURFACE;
    EglProcs procs_{};
    std::string extensions_;
};

namespace {

constexpr EGLint kConfigAttribs[] = {
    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
    EGL_SURFACE_TYPE,    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
    EGL_RED_SIZE,        8,
    EGL_GREEN_SIZE,      8,
    EGL_BLUE_SIZE,       8,
    EGL_ALPHA_SIZE,      8,
    EGL_DEPTH_SIZE,      0,
    EGL_STENCIL_SIZE,    0,
    EGL_NONE,
};

constexpr EGLint kContextAttribs[] = {
    EGL_CONTEXT_CLIENT_VERSION, 3,
    EGL_NONE,
};

constexpr EGLint kPbufferAttribs[] = {
    EGL_WIDTH,  1,
    EGL_HEIGHT, 1,
    EGL_NONE,
};

}  // namespace

std::unique_ptr<EglCore> EglCore::create(Status* st) {
    auto core = std::unique_ptr<EglCore>(new EglCore());

    core->display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (core->display_ == EGL_NO_DISPLAY) {
        *st = Status::fail("eglGetDisplay returned EGL_NO_DISPLAY");
        return nullptr;
    }

    EGLint major = 0, minor = 0;
    if (eglInitialize(core->display_, &major, &minor) != EGL_TRUE) {
        *st = Status::fail("eglInitialize failed");
        return nullptr;
    }
    if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
        *st = Status::fail("eglBindAPI(EGL_OPENGL_ES_API) failed");
        return nullptr;
    }

    EGLint count = 0;
    if (eglChooseConfig(core->display_, kConfigAttribs, &core->config_, 1, &count) != EGL_TRUE ||
        count == 0) {
        *st = Status::fail("no EGL config with RGBA8888 + ES3 + window/pbuffer");
        return nullptr;
    }

    const char* ext = eglQueryString(core->display_, EGL_EXTENSIONS);
    core->extensions_ = (ext != nullptr) ? ext : "";

    auto& p = core->procs_;
    p.create_image = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
        eglGetProcAddress("eglCreateImageKHR"));
    p.destroy_image = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
        eglGetProcAddress("eglDestroyImageKHR"));
    p.get_native_client_buffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    p.create_sync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
        eglGetProcAddress("eglCreateSyncKHR"));
    p.destroy_sync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
        eglGetProcAddress("eglDestroySyncKHR"));
    p.dup_native_fence_fd = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
        eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    p.client_wait_sync = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(
        eglGetProcAddress("eglClientWaitSyncKHR"));
    p.wait_sync = reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(
        eglGetProcAddress("eglWaitSyncKHR"));

    if (p.create_image == nullptr || p.destroy_image == nullptr ||
        p.get_native_client_buffer == nullptr || p.create_sync == nullptr ||
        p.destroy_sync == nullptr || p.dup_native_fence_fd == nullptr) {
        *st = Status::fail("required EGL extensions missing "
                           "(image_native_buffer / get_native_client_buffer / "
                           "native_fence_sync)");
        return nullptr;
    }
    return core;
}

EglCore::~EglCore() { destroy(); }

void EglCore::destroy() {
    if (display_ == EGL_NO_DISPLAY) return;
    clear_current();
    if (scratch_ != EGL_NO_SURFACE) {
        eglDestroySurface(display_, scratch_);
        scratch_ = EGL_NO_SURFACE;
    }
    eglTerminate(display_);
    display_ = EGL_NO_DISPLAY;
}

bool EglCore::has_extension(const char* name) const {
    if (extensions_.empty() || name == nullptr || *name == '\0') return false;
    const std::string needle(name);
    std::size_t pos = 0;
    while ((pos = extensions_.find(needle, pos)) != std::string::npos) {
        const bool head = (pos == 0) || (extensions_[pos - 1] == ' ');
        const std::size_t end = pos + needle.size();
        const bool tail = (end == extensions_.size()) || (extensions_[end] == ' ');
        if (head && tail) return true;
        pos = end;
    }
    return false;
}

EGLContext EglCore::create_context(EGLContext share, Status* st) {
    const EGLContext ctx = eglCreateContext(display_, config_, share, kContextAttribs);
    if (ctx == EGL_NO_CONTEXT) {
        *st = Status::fail("eglCreateContext(GLES3) failed");
    }
    return ctx;
}

EGLSurface EglCore::scratch_surface(Status* st) {
    if (scratch_ != EGL_NO_SURFACE) return scratch_;
    scratch_ = eglCreatePbufferSurface(display_, config_, kPbufferAttribs);
    if (scratch_ == EGL_NO_SURFACE) {
        *st = Status::fail("eglCreatePbufferSurface failed");
    }
    return scratch_;
}

bool EglCore::make_current(EGLSurface draw, EGLContext ctx, Status* st) {
    if (eglMakeCurrent(display_, draw, draw, ctx) != EGL_TRUE) {
        *st = Status::fail("eglMakeCurrent failed");
        return false;
    }
    return true;
}

void EglCore::clear_current() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
}

// ===========================================================================
// BufferRing
// ===========================================================================

class BufferRing {
public:
    static constexpr uint32_t kInvalidIndex = 0xFFFFFFFFu;

    struct Config {
        uint32_t width = 1920;
        uint32_t height = 1080;
        uint32_t capacity = 4;
        uint32_t format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                         AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
                         AHARDWAREBUFFER_USAGE_VIDEO_ENCODE;
        std::chrono::milliseconds acquire_timeout{16};
    };

    class Lease {
    public:
        Lease() = default;
        Lease(Lease&& other) noexcept;
        Lease& operator=(Lease&& other) noexcept;
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        ~Lease();

        GLuint texture() const;
        GLuint fbo() const;
        AHardwareBuffer* buffer() const;
        uint32_t width() const;
        uint32_t height() const;
        int64_t pts_ns() const;
        void set_pts_ns(int64_t pts) const;
        void reset();

        explicit operator bool() const { return ring_ != nullptr; }

    private:
        friend class BufferRing;
        Lease(BufferRing* ring, uint32_t index) : ring_(ring), index_(index) {}

        BufferRing* ring_ = nullptr;
        uint32_t index_ = kInvalidIndex;
    };

    static std::unique_ptr<BufferRing> create(Config cfg, EglCore* egl, Status* st);
    ~BufferRing();

    BufferRing(const BufferRing&) = delete;
    BufferRing& operator=(const BufferRing&) = delete;

    Lease acquire();
    Lease acquire_for(std::chrono::milliseconds timeout);

    uint32_t capacity() const { return static_cast<uint32_t>(slots_.size()); }
    uint32_t width() const { return cfg_.width; }
    uint32_t height() const { return cfg_.height; }

private:
    struct Slot {
        AHardwareBuffer* buffer = nullptr;
        EGLImageKHR image = EGL_NO_IMAGE_KHR;
        GLuint texture = 0;
        GLuint fbo = 0;
        int64_t pts_ns = 0;
        bool leased = false;
    };

    BufferRing() = default;

    bool init(EglCore* egl, Status* st);
    void destroy();
    void release(uint32_t index);

    Config cfg_{};
    EglCore* egl_ = nullptr;
    std::vector<Slot> slots_;
    std::vector<uint32_t> free_list_;
    std::mutex mutex_;
    std::condition_variable cv_;
};

namespace {

constexpr EGLint kImageAttribs[] = {
    EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
    EGL_NONE,
};

}  // namespace

std::unique_ptr<BufferRing> BufferRing::create(Config cfg, EglCore* egl, Status* st) {
    if (egl == nullptr) {
        *st = Status::fail("BufferRing::create: null EglCore");
        return nullptr;
    }
    if (eglGetCurrentContext() == EGL_NO_CONTEXT) {
        *st = Status::fail("BufferRing::create: no current EGL context");
        return nullptr;
    }
    if (cfg.width == 0 || cfg.height == 0 || cfg.capacity < 2) {
        *st = Status::fail("BufferRing::create: invalid geometry or capacity < 2");
        return nullptr;
    }

    auto ring = std::unique_ptr<BufferRing>(new BufferRing());
    ring->cfg_ = cfg;
    if (!ring->init(egl, st)) {
        return nullptr;
    }
    return ring;
}

bool BufferRing::init(EglCore* egl, Status* st) {
    egl_ = egl;

    const EGLDisplay dpy = egl_->display();
    const EglProcs& procs = egl_->procs();

    slots_.resize(cfg_.capacity);
    free_list_.reserve(cfg_.capacity);

    for (uint32_t i = 0; i < cfg_.capacity; ++i) {
        Slot& slot = slots_[i];

        AHardwareBuffer_Desc desc{};
        desc.width = cfg_.width;
        desc.height = cfg_.height;
        desc.layers = 1;
        desc.format = cfg_.format;
        desc.usage = cfg_.usage;

        const int rc = AHardwareBuffer_allocate(&desc, &slot.buffer);
        if (rc != 0 || slot.buffer == nullptr) {
            *st = Status::fail("AHardwareBuffer_allocate failed on slot " +
                               std::to_string(i) + " rc=" + std::to_string(rc));
            destroy();
            return false;
        }

        EGLClientBuffer client = procs.get_native_client_buffer(slot.buffer);
        if (client == nullptr) {
            *st = Status::fail("eglGetNativeClientBufferANDROID null on slot " +
                               std::to_string(i));
            destroy();
            return false;
        }

        slot.image = procs.create_image(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                        client, kImageAttribs);
        if (slot.image == EGL_NO_IMAGE_KHR) {
            *st = Status::fail("eglCreateImageKHR failed on slot " + std::to_string(i));
            destroy();
            return false;
        }

        glGenTextures(1, &slot.texture);
        if (slot.texture == 0) {
            *st = Status::fail("glGenTextures failed on slot " + std::to_string(i));
            destroy();
            return false;
        }
        glBindTexture(GL_TEXTURE_2D, slot.texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, slot.image);

        if (const GLenum err = glGetError(); err != GL_NO_ERROR) {
            *st = Status::fail("glEGLImageTargetTexture2DOES failed on slot " +
                               std::to_string(i) + " err=0x" + std::to_string(err));
            destroy();
            return false;
        }

        glGenFramebuffers(1, &slot.fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, slot.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               slot.texture, 0);
        const GLenum fb_status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (fb_status != GL_FRAMEBUFFER_COMPLETE) {
            *st = Status::fail("framebuffer incomplete on slot " + std::to_string(i) +
                               " status=0x" + std::to_string(fb_status));
            destroy();
            return false;
        }

        slot.leased = false;
        slot.pts_ns = 0;
        free_list_.push_back(i);
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

BufferRing::~BufferRing() { destroy(); }

void BufferRing::destroy() {
    const bool has_context = (egl_ != nullptr) &&
                             (eglGetCurrentContext() != EGL_NO_CONTEXT);
    for (Slot& slot : slots_) {
        if (has_context) {
            if (slot.fbo != 0) glDeleteFramebuffers(1, &slot.fbo);
            if (slot.texture != 0) glDeleteTextures(1, &slot.texture);
            if (slot.image != EGL_NO_IMAGE_KHR) {
                egl_->procs().destroy_image(egl_->display(), slot.image);
            }
        }
        slot.fbo = 0;
        slot.texture = 0;
        slot.image = EGL_NO_IMAGE_KHR;

        if (slot.buffer != nullptr) {
            AHardwareBuffer_release(slot.buffer);
            slot.buffer = nullptr;
        }
    }
    slots_.clear();
    free_list_.clear();
}

BufferRing::Lease BufferRing::acquire() { return acquire_for(cfg_.acquire_timeout); }

BufferRing::Lease BufferRing::acquire_for(std::chrono::milliseconds timeout) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!cv_.wait_for(lock, timeout, [this] { return !free_list_.empty(); })) {
        return Lease{};
    }
    const uint32_t index = free_list_.back();
    free_list_.pop_back();
    slots_[index].leased = true;
    return Lease(this, index);
}

void BufferRing::release(uint32_t index) {
    if (index == kInvalidIndex || index >= slots_.size()) return;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!slots_[index].leased) return;
        slots_[index].leased = false;
        slots_[index].pts_ns = 0;
        free_list_.push_back(index);
    }
    cv_.notify_one();
}

BufferRing::Lease::Lease(Lease&& other) noexcept
    : ring_(std::exchange(other.ring_, nullptr)),
      index_(std::exchange(other.index_, kInvalidIndex)) {}

BufferRing::Lease& BufferRing::Lease::operator=(Lease&& other) noexcept {
    if (this != &other) {
        reset();
        ring_ = std::exchange(other.ring_, nullptr);
        index_ = std::exchange(other.index_, kInvalidIndex);
    }
    return *this;
}

BufferRing::Lease::~Lease() { reset(); }

void BufferRing::Lease::reset() {
    if (ring_ != nullptr && index_ != kInvalidIndex) {
        ring_->release(index_);
    }
    ring_ = nullptr;
    index_ = kInvalidIndex;
}

GLuint BufferRing::Lease::texture() const {
    return (ring_ != nullptr) ? ring_->slots_[index_].texture : 0;
}

GLuint BufferRing::Lease::fbo() const {
    return (ring_ != nullptr) ? ring_->slots_[index_].fbo : 0;
}

AHardwareBuffer* BufferRing::Lease::buffer() const {
    return (ring_ != nullptr) ? ring_->slots_[index_].buffer : nullptr;
}

uint32_t BufferRing::Lease::width() const {
    return (ring_ != nullptr) ? ring_->cfg_.width : 0;
}

uint32_t BufferRing::Lease::height() const {
    return (ring_ != nullptr) ? ring_->cfg_.height : 0;
}

int64_t BufferRing::Lease::pts_ns() const {
    return (ring_ != nullptr) ? ring_->slots_[index_].pts_ns : 0;
}

void BufferRing::Lease::set_pts_ns(int64_t pts) const {
    if (ring_ != nullptr) ring_->slots_[index_].pts_ns = pts;
}

// ===========================================================================
// SurfaceSink
//
// The public NDK exposes no BufferQueue dequeue/queue entry points, so the
// sink drives the consumer's queue through an EGLSurface: eglSwapBuffers
// performs dequeue, presentation and queue internally, including fence
// handoff. The ring texture remains the source, so the composition side is
// unchanged. If the consumer's buffers cannot back an EGLSurface, creation
// fails loudly here and the slot is never armed.
// ===========================================================================

enum class YuvMatrix : int { kBt601 = 0, kBt709 = 1, kBt2020 = 2 };

class SurfaceSink {
public:
    struct Config {
        YuvMatrix matrix = YuvMatrix::kBt709;
        bool limited_range = true;
    };

    struct Stats {
        uint64_t presented = 0;
        uint64_t dropped = 0;
        uint64_t errors = 0;
    };

    static std::unique_ptr<SurfaceSink> create(ANativeWindow* window, EglCore* egl,
                                               Config cfg, Status* st);
    ~SurfaceSink();

    SurfaceSink(const SurfaceSink&) = delete;
    SurfaceSink& operator=(const SurfaceSink&) = delete;

    Status present(const BufferRing::Lease& src);
    const Stats& stats() const { return stats_; }

private:
    SurfaceSink() = default;

    bool init(EglCore* egl, Status* st);
    bool build_program(Status* st);
    GLuint compile(GLenum type, const char* source, Status* st);
    GLuint link(const char* vs, const char* fs, Status* st);
    void destroy();
    Status draw(GLuint src_tex);

    Config cfg_{};
    EglCore* egl_ = nullptr;
    ANativeWindow* window_ = nullptr;
    EGLSurface egl_surface_ = EGL_NO_SURFACE;

    GLuint prog_rgba_ = 0;
    GLint u_rgba_src_ = -1;

    int32_t width_ = 0;
    int32_t height_ = 0;
    Stats stats_{};
};

namespace {

constexpr char kVertexShader[] = R"glsl(
#version 300 es
out vec2 v_uv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    v_uv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)glsl";

constexpr char kFragmentRgba[] = R"glsl(
#version 300 es
precision mediump float;
uniform sampler2D u_src;
in vec2 v_uv;
out vec4 fragColor;
void main() {
    fragColor = texture(u_src, v_uv);
}
)glsl";

std::string hex32(uint32_t value) {
    char buf[16];
    snprintf(buf, sizeof(buf), "0x%08x", value);
    return std::string(buf);
}

}  // namespace

std::unique_ptr<SurfaceSink> SurfaceSink::create(ANativeWindow* window, EglCore* egl,
                                                 Config cfg, Status* st) {
    if (window == nullptr || egl == nullptr) {
        *st = Status::fail("SurfaceSink::create: null window or EglCore");
        return nullptr;
    }
    if (eglGetCurrentContext() == EGL_NO_CONTEXT) {
        *st = Status::fail("SurfaceSink::create: no current EGL context");
        return nullptr;
    }

    auto sink = std::unique_ptr<SurfaceSink>(new SurfaceSink());
    sink->cfg_ = cfg;
    ANativeWindow_acquire(window);
    sink->window_ = window;

    if (!sink->init(egl, st)) {
        sink->destroy();
        return nullptr;
    }
    return sink;
}

bool SurfaceSink::init(EglCore* egl, Status* st) {
    egl_ = egl;
    width_ = ANativeWindow_getWidth(window_);
    height_ = ANativeWindow_getHeight(window_);
    if (width_ <= 0 || height_ <= 0) {
        *st = Status::fail("ANativeWindow has zero geometry");
        return false;
    }

    if (!build_program(st)) return false;

    // Default swap behaviour is already EGL_BUFFER_DESTROYED; passing it as a
    // creation attribute is not portable across vendor EGL implementations.
    egl_surface_ = eglCreateWindowSurface(egl_->display(), egl_->config(), window_, nullptr);
    if (egl_surface_ == EGL_NO_SURFACE) {
        *st = Status::fail("eglCreateWindowSurface failed " +
                           hex32(static_cast<uint32_t>(eglGetError())));
        return false;
    }

    const EGLContext ctx = eglGetCurrentContext();
    if (!egl_->make_current(egl_surface_, ctx, st)) return false;

    LOGI("sink ready %dx%d surface=%p", width_, height_,
         reinterpret_cast<void*>(egl_surface_));
    return true;
}

bool SurfaceSink::build_program(Status* st) {
    prog_rgba_ = link(kVertexShader, kFragmentRgba, st);
    if (prog_rgba_ == 0) return false;

    u_rgba_src_ = glGetUniformLocation(prog_rgba_, "u_src");
    if (u_rgba_src_ < 0) {
        *st = Status::fail("RGBA program lacks u_src");
        return false;
    }
    glUseProgram(prog_rgba_);
    glUniform1i(u_rgba_src_, 0);
    glUseProgram(0);
    return true;
}

GLuint SurfaceSink::compile(GLenum type, const char* source, Status* st) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) {
        *st = Status::fail("glCreateShader failed");
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        GLint len = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
        std::string log(static_cast<std::size_t>(len > 1 ? len : 1), '\0');
        if (len > 1) glGetShaderInfoLog(shader, len, nullptr, log.data());
        glDeleteShader(shader);
        *st = Status::fail("shader compile failed: " + log);
        return 0;
    }
    return shader;
}

GLuint SurfaceSink::link(const char* vs_source, const char* fs_source, Status* st) {
    Status local{};
    const GLuint vs = compile(GL_VERTEX_SHADER, vs_source, &local);
    if (vs == 0) {
        *st = local;
        return 0;
    }
    const GLuint fs = compile(GL_FRAGMENT_SHADER, fs_source, &local);
    if (fs == 0) {
        glDeleteShader(vs);
        *st = local;
        return 0;
    }

    const GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint linked = GL_FALSE;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint len = 0;
        glGetProgramiv(prog, GL_INFO_LOG_LENGTH, &len);
        std::string log(static_cast<std::size_t>(len > 1 ? len : 1), '\0');
        if (len > 1) glGetProgramInfoLog(prog, len, nullptr, log.data());
        glDeleteProgram(prog);
        *st = Status::fail("program link failed: " + log);
        return 0;
    }
    return prog;
}

SurfaceSink::~SurfaceSink() { destroy(); }

void SurfaceSink::destroy() {
    if (eglGetCurrentContext() != EGL_NO_CONTEXT && prog_rgba_ != 0) {
        glDeleteProgram(prog_rgba_);
    }
    prog_rgba_ = 0;

    if (egl_surface_ != EGL_NO_SURFACE && egl_ != nullptr) {
        eglDestroySurface(egl_->display(), egl_surface_);
        egl_surface_ = EGL_NO_SURFACE;
    }
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

Status SurfaceSink::draw(GLuint src_tex) {
    if (src_tex == 0) return Status::fail("draw: null source texture");

    glUseProgram(prog_rgba_);
    glUniform1i(u_rgba_src_, 0);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width_, height_);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, src_tex);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    const GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        return Status::fail("blit failed " + hex32(static_cast<uint32_t>(err)));
    }
    return Status::success();
}

Status SurfaceSink::present(const BufferRing::Lease& src) {
    if (!src) return Status::fail("present: empty lease");
    if (eglGetCurrentContext() == EGL_NO_CONTEXT) {
        return Status::fail("present: no current EGL context on this thread");
    }
    if (egl_surface_ == EGL_NO_SURFACE) {
        return Status::fail("present: window surface unavailable");
    }

    Status st = draw(src.texture());
    if (!st) {
        ++stats_.errors;
        return st;
    }

    if (eglSwapBuffers(egl_->display(), egl_surface_) != EGL_TRUE) {
        ++stats_.dropped;
        return Status::drop("eglSwapBuffers failed " +
                            hex32(static_cast<uint32_t>(eglGetError())));
    }

    ++stats_.presented;
    return Status::success();
}

}  // namespace vcam

// ===========================================================================
// Session thread + JNI
// ===========================================================================

namespace {

using vcam::BufferRing;
using vcam::EglCore;
using vcam::Status;
using vcam::SurfaceSink;
using vcam::YuvMatrix;

constexpr const char* kBridgeClass = "com/vcam/core/NativeBridge";
constexpr int kDefaultFps = 30;
constexpr int kMaxFps = 120;
constexpr int kIdleSleepMs = 5;

int64_t now_boottime_ns() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;
}

struct Session {
    ANativeWindow* window = nullptr;
    int width = 0;
    int height = 0;
    YuvMatrix matrix = YuvMatrix::kBt709;
    bool limited = true;

    std::thread worker;
    std::atomic<bool> running{true};
    std::atomic<bool> active{false};
    std::atomic<int> fps{kDefaultFps};
    std::atomic<uint64_t> presented{0};
    std::atomic<uint64_t> dropped{0};
    std::atomic<uint64_t> errors{0};
    std::promise<bool> ready;
};

std::mutex g_live_mutex;
std::unordered_set<Session*> g_live;

void draw_test_pattern(int width, int height, uint64_t frame) {
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_SCISSOR_TEST);

    glScissor(0, 0, width, height);
    glClearColor(0.04f, 0.06f, 0.08f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    constexpr int kBarCount = 8;
    const int bar_w = width / kBarCount;
    const int bar_h = height / 3;
    for (int i = 0; i < kBarCount; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(kBarCount - 1);
        glScissor(i * bar_w, bar_h, bar_w, bar_h);
        glClearColor(0.10f + 0.90f * t, 0.18f, 1.00f - 0.80f * t, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    const int band_h = height / 12;
    const int travel = height - band_h;
    const int band_y = (travel > 0)
        ? static_cast<int>((frame * 7) % static_cast<uint64_t>(travel))
        : 0;
    glScissor(0, band_y, width, band_h);
    glClearColor(0.95f, 0.95f, 0.95f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    // Cadence meter: one tick per frame across the top edge, resets each second.
    const int meter_w = static_cast<int>((frame % kDefaultFps) * width / kDefaultFps);
    glScissor(0, height - 4, meter_w, 4);
    glClearColor(0.10f, 1.00f, 0.35f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glDisable(GL_SCISSOR_TEST);
}

void worker_main(Session* s) {
    Status st{};

    auto egl = EglCore::create(&st);
    if (!egl) {
        LOGE("EglCore: %s", st.message.c_str());
        s->ready.set_value(false);
        return;
    }

    const EGLContext ctx = egl->create_context(EGL_NO_CONTEXT, &st);
    const EGLSurface scratch = egl->scratch_surface(&st);
    if (ctx == EGL_NO_CONTEXT || scratch == EGL_NO_SURFACE ||
        !egl->make_current(scratch, ctx, &st)) {
        LOGE("EGL setup: %s", st.message.c_str());
        s->ready.set_value(false);
        return;
    }

    BufferRing::Config ring_cfg{};
    ring_cfg.width = static_cast<uint32_t>(s->width);
    ring_cfg.height = static_cast<uint32_t>(s->height);
    ring_cfg.capacity = 4;

    auto ring = BufferRing::create(ring_cfg, egl.get(), &st);
    if (!ring) {
        LOGE("BufferRing: %s", st.message.c_str());
        s->ready.set_value(false);
        return;
    }

    SurfaceSink::Config sink_cfg{};
    sink_cfg.matrix = s->matrix;
    sink_cfg.limited_range = s->limited;

    auto sink = SurfaceSink::create(s->window, egl.get(), sink_cfg, &st);
    if (!sink) {
        LOGE("SurfaceSink: %s", st.message.c_str());
        s->ready.set_value(false);
        return;
    }

    s->ready.set_value(true);

    int64_t next_due = now_boottime_ns();
    uint64_t frame = 0;
    bool was_active = false;

    while (s->running.load(std::memory_order_relaxed)) {
        if (!s->active.load(std::memory_order_relaxed)) {
            was_active = false;
            std::this_thread::sleep_for(std::chrono::milliseconds(kIdleSleepMs));
            continue;
        }
        if (!was_active) {
            was_active = true;
            next_due = now_boottime_ns();  // rebase so resume never sprints
        }

        const int fps = std::clamp(s->fps.load(std::memory_order_relaxed), 1, kMaxFps);
        const int64_t period = 1'000'000'000LL / fps;
        next_due += period;

        auto slot = ring->acquire_for(std::chrono::milliseconds(16));
        if (!slot) {
            s->dropped.fetch_add(1, std::memory_order_relaxed);
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, slot.fbo());
            draw_test_pattern(s->width, s->height, frame);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            slot.set_pts_ns(now_boottime_ns());

            const Status presented = sink->present(slot);
            if (presented.ok) {
                s->presented.fetch_add(1, std::memory_order_relaxed);
            } else if (presented.retryable) {
                s->dropped.fetch_add(1, std::memory_order_relaxed);
            } else {
                s->errors.fetch_add(1, std::memory_order_relaxed);
            }
        }
        ++frame;

        timespec ts{};
        ts.tv_sec = static_cast<time_t>(next_due / 1'000'000'000LL);
        ts.tv_nsec = static_cast<long>(next_due % 1'000'000'000LL);
        while (s->running.load(std::memory_order_relaxed) &&
               clock_nanosleep(CLOCK_BOOTTIME, TIMER_ABSTIME, &ts, nullptr) == EINTR) {
        }

        if (now_boottime_ns() - next_due > period * 4) {
            next_due = now_boottime_ns();
        }
    }

    sink.reset();
    ring.reset();
    egl->clear_current();
    egl.reset();
}

bool is_live(Session* s) {
    std::lock_guard<std::mutex> lock(g_live_mutex);
    return g_live.count(s) != 0;
}

Session* from_handle(jlong handle) {
    if (handle == 0) return nullptr;
    auto* s = reinterpret_cast<Session*>(static_cast<intptr_t>(handle));
    return is_live(s) ? s : nullptr;
}

// ---- JNI entry points ------------------------------------------------------

jlong j_create_session(JNIEnv* env, jclass, jobject surface, jint width, jint height,
                       jint matrix, jboolean limited) {
    if (surface == nullptr || width <= 0 || height <= 0) return 0;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LOGE("ANativeWindow_fromSurface returned null");
        return 0;
    }

    std::unique_ptr<Session> s(new Session());
    s->window = window;
    s->width = width;
    s->height = height;
    s->matrix = static_cast<YuvMatrix>(matrix);
    s->limited = (limited == JNI_TRUE);

    std::future<bool> ready = s->ready.get_future();

    try {
        s->worker = std::thread(worker_main, s.get());
    } catch (const std::exception& e) {
        LOGE("thread start failed: %s", e.what());
        ANativeWindow_release(window);
        return 0;
    }

    if (ready.wait_for(std::chrono::seconds(3)) != std::future_status::ready || !ready.get()) {
        LOGE("session init failed %dx%d", width, height);
        s->running.store(false, std::memory_order_relaxed);
        if (s->worker.joinable()) s->worker.join();
        ANativeWindow_release(window);
        return 0;
    }

    Session* raw = s.release();
    {
        std::lock_guard<std::mutex> lock(g_live_mutex);
        g_live.insert(raw);
    }
    LOGI("session created %dx%d handle=%p", width, height, static_cast<void*>(raw));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(raw));
}

void j_destroy_session(JNIEnv*, jclass, jlong handle) {
    Session* s = from_handle(handle);
    if (s == nullptr) return;

    s->running.store(false, std::memory_order_relaxed);
    if (s->worker.joinable()) s->worker.join();
    if (s->window != nullptr) ANativeWindow_release(s->window);

    {
        std::lock_guard<std::mutex> lock(g_live_mutex);
        g_live.erase(s);
    }
    LOGI("session destroyed handle=%p", static_cast<void*>(s));
    delete s;
}

void j_set_active(JNIEnv*, jclass, jlong handle, jboolean active) {
    Session* s = from_handle(handle);
    if (s == nullptr) return;
    s->active.store(active == JNI_TRUE, std::memory_order_relaxed);
}

jintArray j_surface_size(JNIEnv* env, jclass, jobject surface) {
    if (surface == nullptr) return nullptr;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return nullptr;

    const jint dims[2] = {
        ANativeWindow_getWidth(window),
        ANativeWindow_getHeight(window),
    };
    ANativeWindow_release(window);

    jintArray result = env->NewIntArray(2);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 2, dims);
    return result;
}

jlongArray j_stats(JNIEnv* env, jclass, jlong handle) {
    Session* s = from_handle(handle);
    if (s == nullptr) return nullptr;

    const jlong values[3] = {
        static_cast<jlong>(s->presented.load(std::memory_order_relaxed)),
        static_cast<jlong>(s->dropped.load(std::memory_order_relaxed)),
        static_cast<jlong>(s->errors.load(std::memory_order_relaxed)),
    };
    jlongArray result = env->NewLongArray(3);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}

void j_set_fps(JNIEnv*, jclass, jlong handle, jint fps) {
    Session* s = from_handle(handle);
    if (s == nullptr) return;
    s->fps.store(std::clamp<int>(fps, 1, kMaxFps), std::memory_order_relaxed);
}

const JNINativeMethod kMethods[] = {
    { "createSession",  "(Landroid/view/Surface;IIIZ)J", reinterpret_cast<void*>(j_create_session) },
    { "destroySession", "(J)V",                          reinterpret_cast<void*>(j_destroy_session) },
    { "setActive",      "(JZ)V",                         reinterpret_cast<void*>(j_set_active) },
    { "surfaceSize",    "(Landroid/view/Surface;)[I",     reinterpret_cast<void*>(j_surface_size) },
    { "stats",          "(J)[J",                          reinterpret_cast<void*>(j_stats) },
    { "setFps",         "(JI)V",                          reinterpret_cast<void*>(j_set_fps) },
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass bridge = env->FindClass(kBridgeClass);
    if (bridge == nullptr) {
        LOGE("NativeBridge class not found");
        return JNI_ERR;
    }
    if (env->RegisterNatives(bridge, kMethods,
                             static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])))
        != JNI_OK) {
        LOGE("RegisterNatives failed");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
