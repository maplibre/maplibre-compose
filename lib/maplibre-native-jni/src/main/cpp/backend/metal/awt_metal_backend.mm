#ifdef USE_METAL_BACKEND

#include "awt_metal_backend.hpp"
#import <Cocoa/Cocoa.h>
#include <Metal/Metal.hpp>
#import <QuartzCore/CAMetalLayer.h>
#include <QuartzCore/CAMetalLayer.hpp>
#include <jawt.h>
#include <jawt_md.h>
#include <jni.h>
#include <mbgl/gfx/context.hpp>
#include <mbgl/mtl/mtl_fwd.hpp>
#include <mbgl/mtl/renderable_resource.hpp>
#include <mbgl/mtl/texture2d.hpp>
#include <mbgl/util/logging.hpp>
#include <memory>

namespace maplibre_jni {
// Implementation follows below
}  // namespace maplibre_jni

namespace mbgl {

using namespace mtl;

// Metal renderable resource - exact copy from existing implementation
class MetalRenderableResource final : public mtl::RenderableResource {
 public:
  MetalRenderableResource(maplibre_jni::MetalBackend &backend)
      : rendererBackend(backend),
        commandQueue(NS::TransferPtr(backend.getDevice()->newCommandQueue())),
        swapchain(NS::TransferPtr(CA::MetalLayer::layer())) {
    swapchain->setDevice(backend.getDevice().get());
  }

  void setBackendSize(mbgl::Size size_) {
    size = size_;
    swapchain->setDrawableSize(
      {static_cast<CGFloat>(size.width), static_cast<CGFloat>(size.height)}
    );
    buffersInvalid = true;
  }

  mbgl::Size getSize() const { return size; }

  void bind() override {
    surface = NS::TransferPtr(swapchain->nextDrawable());
    auto texSize = mbgl::Size{
      static_cast<uint32_t>(swapchain->drawableSize().width),
      static_cast<uint32_t>(swapchain->drawableSize().height)
    };

    commandBuffer = NS::TransferPtr(commandQueue->commandBuffer());
    renderPassDescriptor =
      NS::TransferPtr(MTL::RenderPassDescriptor::renderPassDescriptor());
    renderPassDescriptor->colorAttachments()->object(0)->setTexture(
      surface->texture()
    );

    if (buffersInvalid || !depthTexture || !stencilTexture) {
      buffersInvalid = false;
      depthTexture = rendererBackend.getContext().createTexture2D();
      depthTexture->setSize(texSize);
      depthTexture->setFormat(
        gfx::TexturePixelType::Depth, gfx::TextureChannelDataType::Float
      );
      depthTexture->setSamplerConfiguration(
        {gfx::TextureFilterType::Linear, gfx::TextureWrapType::Clamp,
         gfx::TextureWrapType::Clamp}
      );
      static_cast<mtl::Texture2D *>(depthTexture.get())
        ->setUsage(
          MTL::TextureUsageShaderRead | MTL::TextureUsageShaderWrite |
          MTL::TextureUsageRenderTarget
        );

      stencilTexture = rendererBackend.getContext().createTexture2D();
      stencilTexture->setSize(texSize);
      stencilTexture->setFormat(
        gfx::TexturePixelType::Stencil,
        gfx::TextureChannelDataType::UnsignedByte
      );
      stencilTexture->setSamplerConfiguration(
        {gfx::TextureFilterType::Linear, gfx::TextureWrapType::Clamp,
         gfx::TextureWrapType::Clamp}
      );
      static_cast<mtl::Texture2D *>(stencilTexture.get())
        ->setUsage(
          MTL::TextureUsageShaderRead | MTL::TextureUsageShaderWrite |
          MTL::TextureUsageRenderTarget
        );
    }

    renderPassDescriptor->depthAttachment()->setTexture(
      static_cast<mtl::Texture2D *>(depthTexture.get())->getMetalTexture()
    );
    renderPassDescriptor->stencilAttachment()->setTexture(
      static_cast<mtl::Texture2D *>(stencilTexture.get())->getMetalTexture()
    );

    renderPassDescriptor->colorAttachments()->object(0)->setClearColor(
      MTL::ClearColor(clearColor.r, clearColor.g, clearColor.b, clearColor.a)
    );
    renderPassDescriptor->colorAttachments()->object(0)->setLoadAction(
      MTL::LoadActionClear
    );
    renderPassDescriptor->colorAttachments()->object(0)->setStoreAction(
      MTL::StoreActionStore
    );
    renderPassDescriptor->depthAttachment()->setClearDepth(1.0);
    renderPassDescriptor->depthAttachment()->setLoadAction(
      MTL::LoadActionClear
    );
    renderPassDescriptor->depthAttachment()->setStoreAction(
      MTL::StoreActionDontCare
    );
    renderPassDescriptor->stencilAttachment()->setClearStencil(0);
    renderPassDescriptor->stencilAttachment()->setLoadAction(
      MTL::LoadActionClear
    );
    renderPassDescriptor->stencilAttachment()->setStoreAction(
      MTL::StoreActionDontCare
    );
  }

  void swap() override {
    if (commandBuffer) {
      commandBuffer->presentDrawable(surface.get());
      commandBuffer->commit();
    }
  }

  void setClearColor(const mbgl::Color &color) { clearColor = color; }

  const mbgl::mtl::RendererBackend &getBackend() const override {
    return rendererBackend;
  }

  const MTLCommandBufferPtr &getCommandBuffer() const override {
    return commandBuffer;
  }

  MTLBlitPassDescriptorPtr getUploadPassDescriptor() const override {
    return MTLBlitPassDescriptorPtr();
  }

  const MTLRenderPassDescriptorPtr &getRenderPassDescriptor() const override {
    return renderPassDescriptor;
  }

  NS::SharedPtr<CA::MetalLayer> swapchain;

 private:
  mbgl::mtl::RendererBackend &rendererBackend;
  mbgl::Color clearColor{0.0, 0.0, 0.0, 1.0};
  mbgl::Size size{0, 0};
  NS::SharedPtr<MTL::CommandQueue> commandQueue;
  NS::SharedPtr<CA::MetalDrawable> surface;
  MTLCommandBufferPtr commandBuffer;
  MTLRenderPassDescriptorPtr renderPassDescriptor;
  gfx::Texture2DPtr depthTexture;
  gfx::Texture2DPtr stencilTexture;
  bool buffersInvalid = false;
};

}  // namespace mbgl

namespace maplibre_jni {

// MetalBackend implementation

MetalBackend::MetalBackend(JNIEnv *env, jobject canvas, int width, int height)
    : mbgl::mtl::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::gfx::Renderable(
        mbgl::Size{0, 0}, std::make_unique<mbgl::MetalRenderableResource>(*this)
      ),
      size({static_cast<uint32_t>(width), static_cast<uint32_t>(height)}) {
  // Get JavaVM for later use
  env->GetJavaVM(&jvm);
  canvasRef = env->NewGlobalRef(canvas);

  // Configure the Metal layer via Objective-C
  auto &resource = getResource<mbgl::MetalRenderableResource>();
  resource.setBackendSize(size);
  CAMetalLayer *metalLayer = (__bridge CAMetalLayer *)resource.swapchain.get();

  // Get the backing scale factor for proper Retina display support
  NSScreen *screen = [NSScreen mainScreen];
  CGFloat scale = screen.backingScaleFactor;

  // Set the frame accounting for the scale factor (convert from pixels to
  // points)
  metalLayer.frame = CGRectMake(0, 0, width / scale, height / scale);

  // Set the contentsScale to match the screen's backing scale
  metalLayer.contentsScale = scale;

  // Now set up the Metal layer on the AWT Canvas
  setupMetalLayer(env, canvas);
}

MetalBackend::~MetalBackend() {
  releaseNativeWindow();

  if (canvasRef && jvm) {
    JNIEnv *env = getEnv();
    if (env) {
      env->DeleteGlobalRef(canvasRef);
    }
  }
}

void MetalBackend::setSize(mbgl::Size newSize) {
  size = newSize;
  auto &resource = getResource<mbgl::MetalRenderableResource>();
  resource.setBackendSize(size);

  // Update the Metal layer frame to match the new size
  CAMetalLayer *metalLayer = (__bridge CAMetalLayer *)resource.swapchain.get();
  NSScreen *screen = [NSScreen mainScreen];
  CGFloat scale = screen.backingScaleFactor;
  metalLayer.frame =
    CGRectMake(0, 0, newSize.width / scale, newSize.height / scale);
  metalLayer.contentsScale = scale;
}

mbgl::gfx::Renderable &MetalBackend::getDefaultRenderable() { return *this; }

mbgl::Size MetalBackend::getSize() const { return size; }

void MetalBackend::setupMetalLayer(JNIEnv *env, jobject canvas) {
  // Get JAWT
  JAWT awt;
  awt.version = JAWT_VERSION_9;

  jboolean result = JAWT_GetAWT(env, &awt);
  if (result == JNI_FALSE) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "JAWT_GetAWT failed");
    return;
  }

  // Get the drawing surface
  JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, canvas);
  if (!ds) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "GetDrawingSurface returned null");
    return;
  }

  // Lock the drawing surface
  jint lock = ds->Lock(ds);
  if ((lock & JAWT_LOCK_ERROR) != 0) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Error locking drawing surface");
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Get the drawing surface info
  JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
  if (!dsi) {
    mbgl::Log::Error(
      mbgl::Event::OpenGL, "GetDrawingSurfaceInfo returned null"
    );
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Get the platform-specific drawing info
  id<JAWT_SurfaceLayers> surfaceLayers =
    (id<JAWT_SurfaceLayers>)dsi->platformInfo;
  if (!surfaceLayers) {
    mbgl::Log::Error(mbgl::Event::OpenGL, "Platform info is null");
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return;
  }

  // Get the Metal layer from our resource and set it on the JAWT surface
  auto &resource = getResource<mbgl::MetalRenderableResource>();
  CAMetalLayer *metalLayer = (__bridge CAMetalLayer *)resource.swapchain.get();

  // Set the Metal layer on the JAWT surface
  surfaceLayers.layer = metalLayer;

  // Store references for cleanup
  jawtDrawingSurface = ds;
  jawtDrawingSurfaceInfo = dsi;
}

void MetalBackend::releaseNativeWindow() {
  if (jawtDrawingSurfaceInfo && jawtDrawingSurface) {
    JAWT_DrawingSurface *ds = (JAWT_DrawingSurface *)jawtDrawingSurface;
    ds->FreeDrawingSurfaceInfo(
      (JAWT_DrawingSurfaceInfo *)jawtDrawingSurfaceInfo
    );
    ds->Unlock(ds);

    // Get JAWT to free the surface
    JAWT awt;
    awt.version = JAWT_VERSION_9;
    JNIEnv *env = getEnv();
    if (env && JAWT_GetAWT(env, &awt) != JNI_FALSE) {
      awt.FreeDrawingSurface(ds);
    }

    jawtDrawingSurface = nullptr;
    jawtDrawingSurfaceInfo = nullptr;
  }
}

JNIEnv *MetalBackend::getEnv() {
  JNIEnv *env = nullptr;
  if (jvm) {
    jvm->AttachCurrentThread((void **)&env, nullptr);
  }
  return env;
}

// Factory function
std::unique_ptr<mbgl::gfx::RendererBackend> createMetalBackend(
  JNIEnv *env, jobject canvas, int width, int height,
  const mbgl::gfx::ContextMode contextMode
) {
  return std::make_unique<MetalBackend>(env, canvas, width, height);
}

}  // namespace maplibre_jni

#endif  // USE_METAL_BACKEND
