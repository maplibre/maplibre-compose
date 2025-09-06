#include <memory>

#include <mbgl/gfx/renderer_backend.hpp>
#include <mbgl/renderer/renderer.hpp>

#include <smjni/java_ref.h>
#include <smjni/jni_provider.h>
#include <type_mapping.h>

#include "canvas_renderer.hpp"
#include "conversions.hpp"
#include "java_classes.hpp"

namespace maplibre_jni {

CanvasRenderer::CanvasRenderer(
  JNIEnv* env, jCanvasRenderer canvasFrontend, float pixelRatio
)
    : runLoop_(
        std::make_unique<mbgl::util::RunLoop>(mbgl::util::RunLoop::Type::New)
      ),
      canvasRenderer_(smjni::jglobal_ref(canvasFrontend)),
      backend_(
        std::make_unique<CanvasBackend>(
          env, java_classes::get<CanvasRenderer_class>()
                 .getCanvas(env, canvasFrontend)
                 .c_ptr()
        )
      ),
      renderer_(std::make_unique<mbgl::Renderer>(*backend_, pixelRatio)) {
  runLoop_->setPlatformCallback([this]() {
    if (!canvasRenderer_) return;
    auto* env = smjni::jni_provider::get_jni();
    java_classes::get<CanvasRenderer_class>().requestRunOnce(
      env, canvasRenderer_
    );
  });
}

void CanvasRenderer::reset() {
  renderer_.reset();
  observer_.reset();
  updateParameters_.reset();
  backend_.reset();
  runLoop_.reset();
}

void CanvasRenderer::setObserver(mbgl::RendererObserver& observer) {
  renderer_->setObserver(&observer);
}

void CanvasRenderer::update(std::shared_ptr<mbgl::UpdateParameters> params) {
  updateParameters_ = std::move(params);
  if (canvasRenderer_.c_ptr() == nullptr) return;
  java_classes::get<CanvasRenderer_class>().requestCanvasRepaint(
    smjni::jni_provider::get_jni(), canvasRenderer_.c_ptr()
  );
}

auto CanvasRenderer::getThreadPool() const -> const mbgl::TaggedScheduler& {
  return backend_->getThreadPool();
}

void CanvasRenderer::render() {
  if (!renderer_ || !updateParameters_) return;
  mbgl::gfx::BackendScope scope(*backend_);
  renderer_->render(updateParameters_);
}

void CanvasRenderer::runOnce() { runLoop_->runOnce(); }

void CanvasRenderer::setSize(mbgl::Size size) { backend_->setSize(size); }

}  // namespace maplibre_jni

void JNICALL
CanvasRenderer_class::render(JNIEnv* env, jCanvasRenderer renderer) {
  try {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    auto* frontend = reinterpret_cast<maplibre_jni::CanvasRenderer*>(
      java_classes::get<CanvasRenderer_class>().getNativePointer(env, renderer)
    );
    // TODO: something in here is segfaulting on Linux
    frontend->render();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL
CanvasRenderer_class::runOnce(JNIEnv* env, jCanvasRenderer renderer) {
  try {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    auto* frontend = reinterpret_cast<maplibre_jni::CanvasRenderer*>(
      java_classes::get<CanvasRenderer_class>().getNativePointer(env, renderer)
    );
    frontend->runOnce();
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

void JNICALL CanvasRenderer_class::setSize(
  JNIEnv* env, jCanvasRenderer canvasFrontend, jSize size
) {
  try {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    auto* renderer = reinterpret_cast<maplibre_jni::CanvasRenderer*>(
      java_classes::get<CanvasRenderer_class>().getNativePointer(
        env, canvasFrontend
      )
    );
    renderer->setSize(maplibre_jni::convertSize(env, size));
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}

auto JNICALL CanvasRenderer_class::alloc(
  JNIEnv* env, jclass /*unused*/, jCanvasRenderer renderer, jfloat pixelRatio
) -> jlong {
  try {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast)
    return reinterpret_cast<jlong>(
      new maplibre_jni::CanvasRenderer(env, renderer, pixelRatio)
    );
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
    return 0;
  }
}

void JNICALL
CanvasRenderer_class::destroy(JNIEnv* env, jclass /*unused*/, jlong ptr) {
  try {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast,cppcoreguidelines-owning-memory)
    delete reinterpret_cast<maplibre_jni::CanvasRenderer*>(ptr);
  } catch (const std::exception& e) {
    smjni::java_exception::translate(env, e);
  }
}
