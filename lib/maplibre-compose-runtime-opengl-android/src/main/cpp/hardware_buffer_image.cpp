#include <cstdint>
#include <stdexcept>
#include <string>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer_jni.h>
#include <jni.h>

namespace {

auto throw_java_exception(JNIEnv* env, const char* message) -> void {
  auto* exception_class = env->FindClass("java/lang/IllegalStateException");
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message);
  }
}

template <typename Function>
auto egl_function(const char* name) -> Function {
  auto function = reinterpret_cast<Function>(eglGetProcAddress(name));
  if (function == nullptr) {
    throw std::runtime_error(std::string(name) + " is unavailable");
  }
  return function;
}

auto display_from_handle(jlong display) -> EGLDisplay {
  return reinterpret_cast<EGLDisplay>(static_cast<uintptr_t>(display));
}

auto image_from_handle(jlong image) -> EGLImageKHR {
  return reinterpret_cast<EGLImageKHR>(static_cast<uintptr_t>(image));
}

// Imports hardware_buffer into the texture bound to GL_TEXTURE_2D in the
// current context. The returned EGLImage holds its own buffer reference.
auto bind_hardware_buffer(JNIEnv* env, EGLDisplay display, jobject hardware_buffer)
  -> EGLImageKHR {
  AHardwareBuffer* buffer = nullptr;
  if (__builtin_available(android 26, *)) {
    buffer = AHardwareBuffer_fromHardwareBuffer(env, hardware_buffer);
  }
  if (buffer == nullptr) {
    throw std::runtime_error("AHardwareBuffer_fromHardwareBuffer returned null");
  }
  auto get_client_buffer =
    egl_function<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
      "eglGetNativeClientBufferANDROID"
    );
  auto create_image =
    egl_function<PFNEGLCREATEIMAGEKHRPROC>("eglCreateImageKHR");
  auto destroy_image =
    egl_function<PFNEGLDESTROYIMAGEKHRPROC>("eglDestroyImageKHR");
  auto target_texture =
    egl_function<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
      "glEGLImageTargetTexture2DOES"
    );

  const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
  auto* image = create_image(
    display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
    get_client_buffer(buffer), attributes
  );
  if (image == EGL_NO_IMAGE_KHR) {
    throw std::runtime_error(
      "eglCreateImageKHR failed with EGL error " +
      std::to_string(eglGetError())
    );
  }
  target_texture(GL_TEXTURE_2D, image);
  if (const auto error = glGetError(); error != GL_NO_ERROR) {
    destroy_image(display, image);
    throw std::runtime_error(
      "glEGLImageTargetTexture2DOES failed with GL error " +
      std::to_string(error)
    );
  }
  return image;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_maplibre_compose_mlnffi_AndroidEglNativeBridge_bindHardwareBuffer(
  JNIEnv* env, jobject, jlong display, jobject hardware_buffer
) {
  try {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(
      bind_hardware_buffer(env, display_from_handle(display), hardware_buffer)
    ));
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_org_maplibre_compose_mlnffi_AndroidEglNativeBridge_destroyImage(
  JNIEnv* env, jobject, jlong display, jlong image
) {
  try {
    egl_function<PFNEGLDESTROYIMAGEKHRPROC>("eglDestroyImageKHR")(
      display_from_handle(display), image_from_handle(image)
    );
  } catch (const std::exception& error) {
    throw_java_exception(env, error.what());
  }
}
