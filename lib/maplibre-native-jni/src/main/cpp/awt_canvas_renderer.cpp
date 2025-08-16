#include "awt_canvas_renderer.hpp"
#include <atomic>
#include <jawt.h>
#include <jawt_md.h>
#include <mbgl/actor/scheduler.hpp>
#include <mbgl/gfx/backend_scope.hpp>
#include <mbgl/gfx/renderable.hpp>
#include <mbgl/gfx/renderer_backend.hpp>
#include <mbgl/renderer/renderer.hpp>
#include <mbgl/renderer/renderer_observer.hpp>
#include <mbgl/renderer/update_parameters.hpp>
#include <mbgl/util/async_task.hpp>
#include <mbgl/util/logging.hpp>
#include <mbgl/util/run_loop.hpp>
#include <memory>
#include <type_mapping.h>
#include "./backend/awt_backend_factory.hpp"

namespace maplibre_jni {

// Implementation class that combines frontend and backend functionality
class AwtCanvasRenderer::Impl : public mbgl::RendererObserver {
 public:
  Impl(
    JNIEnv *env, jCanvas canvas,
    const std::optional<std::string> &localFontFamily
  )
      : runLoop(
          std::make_unique<mbgl::util::RunLoop>(mbgl::util::RunLoop::Type::New)
        ),
        jvm(nullptr),
        canvasRef(nullptr),
        dirty(false) {
    env->GetJavaVM(&jvm);
    canvasRef = env->NewGlobalRef(canvas);
    backend = createPlatformBackend(env, canvas);
    // TODO get the pixel ratio from mapOptions
    renderer = std::make_unique<mbgl::Renderer>(*backend, 2.0, localFontFamily);
    renderer->setObserver(this);
  }

  ~Impl() {
    // Clean up in reverse order
    renderer.reset();
    backend.reset();
    runLoop.reset();

    // Release global references
    if (canvasRef && jvm) {
      JNIEnv *env = getEnv();
      if (env) {
        env->DeleteGlobalRef(canvasRef);
      }
    }
  }

  bool tick() {
    // Process RunLoop events (network callbacks, timers, etc.)
    runLoop->runOnce();

    // Check if we need to render
    if (dirty.exchange(false)) {
      // Render the frame
      mbgl::gfx::BackendScope scope(*backend);
      if (updateParameters) {
        renderer->render(updateParameters);
      }

      // Swap buffers (platform-specific)
      swapBuffers();

      return true;  // Did render
    }

    return false;  // Nothing to render
  }

  void updateSize(int width, int height) {
    // Update the backend size directly - no cast needed!
    backend->setSize(
      mbgl::Size{static_cast<uint32_t>(width), static_cast<uint32_t>(height)}
    );

    // Mark as dirty to trigger render
    dirty = true;
  }

  void reset() { renderer.reset(); }

  void setObserver(mbgl::RendererObserver &observer) {
    externalObserver = &observer;
  }

  void update(std::shared_ptr<mbgl::UpdateParameters> parameters) {
    // Store the update parameters for the next render
    updateParameters = std::move(parameters);
    // Mark dirty when map state changes
    dirty = true;
  }

  const mbgl::TaggedScheduler &getThreadPool() const {
    return backend->getThreadPool();
  }

  mbgl::gfx::RendererBackend *getRendererBackend() { return backend.get(); }

  // RendererObserver implementation
  void onInvalidate() override {
    // Map needs to be redrawn
    dirty = true;

    // Forward to external observer if set
    if (externalObserver) {
      externalObserver->onInvalidate();
    }
  }

  void onResourceError(std::exception_ptr err) override {
    if (externalObserver) {
      externalObserver->onResourceError(err);
    }
  }

  void onDidFinishRenderingFrame(
    RenderMode mode, bool repaintNeeded, bool placementChanged,
    const mbgl::gfx::RenderingStats &stats
  ) override {
    if (repaintNeeded) {
      dirty = true;
    }

    if (externalObserver) {
      externalObserver->onDidFinishRenderingFrame(
        mode, repaintNeeded, placementChanged, stats
      );
    }
  }

  void onStyleImageMissing(
    const std::string &image, const StyleImageMissingCallback &callback
  ) override {
    if (externalObserver) {
      externalObserver->onStyleImageMissing(image, callback);
    }
  }

 private:
  JNIEnv *getEnv() {
    JNIEnv *env = nullptr;
    if (jvm) {
      jvm->AttachCurrentThread((void **)&env, nullptr);
    }
    return env;
  }

  void swapBuffers() {
    // Platform-specific buffer swap
    // The backend will handle this through its platform-specific implementation
  }

  // Core components
  std::unique_ptr<mbgl::util::RunLoop> runLoop;
  std::unique_ptr<PlatformBackend> backend;
  std::unique_ptr<mbgl::Renderer> renderer;

  // JNI references
  JavaVM *jvm;
  jobject canvasRef;

  // State
  std::atomic<bool> dirty;
  std::shared_ptr<mbgl::UpdateParameters> updateParameters;

  // External observer (usually the Map)
  mbgl::RendererObserver *externalObserver = nullptr;
};

// Public interface implementation

AwtCanvasRenderer::AwtCanvasRenderer() = default;

AwtCanvasRenderer::~AwtCanvasRenderer() = default;

std::unique_ptr<AwtCanvasRenderer> AwtCanvasRenderer::create(
  JNIEnv *env, jCanvas canvas, const std::optional<std::string> &localFontFamily
) {
  auto renderer = std::unique_ptr<AwtCanvasRenderer>(new AwtCanvasRenderer());
  renderer->impl = std::make_unique<Impl>(env, canvas, localFontFamily);
  return renderer;
}

bool AwtCanvasRenderer::tick() { return impl->tick(); }

void AwtCanvasRenderer::updateSize(int width, int height) {
  impl->updateSize(width, height);
}

void AwtCanvasRenderer::reset() { impl->reset(); }

void AwtCanvasRenderer::setObserver(mbgl::RendererObserver &observer) {
  impl->setObserver(observer);
}

void AwtCanvasRenderer::update(
  std::shared_ptr<mbgl::UpdateParameters> parameters
) {
  impl->update(std::move(parameters));
}

const mbgl::TaggedScheduler &AwtCanvasRenderer::getThreadPool() const {
  return impl->getThreadPool();
}

mbgl::gfx::RendererBackend *AwtCanvasRenderer::getRendererBackend() {
  return impl->getRendererBackend();
}

}  // namespace maplibre_jni
