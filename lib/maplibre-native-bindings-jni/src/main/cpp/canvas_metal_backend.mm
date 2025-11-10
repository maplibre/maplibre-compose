#ifdef USE_METAL_BACKEND
#include <iostream>
#include <memory>

#include <mbgl/mtl/context.hpp>
#include <mbgl/mtl/renderable_resource.hpp>
#include <mbgl/mtl/texture2d.hpp>

#import <Cocoa/Cocoa.h>
#include <Metal/Metal.hpp>
#import <QuartzCore/CAMetalLayer.h>
#include <QuartzCore/CAMetalLayer.hpp>
#include <jawt_md.h>

#include "canvas_renderer.hpp"
#include "java_classes.hpp"
#include "jawt_context.hpp"

namespace maplibre_jni {

class MetalRenderableResource final : public mbgl::mtl::RenderableResource {
 public:
  MetalRenderableResource(CanvasBackend &backend, JNIEnv *env, jCanvas canvas)
      : jawtContext_(env, canvas),
        rendererBackend(backend),
        commandQueue(NS::TransferPtr(backend.getDevice()->newCommandQueue())),
        metalLayer(NS::TransferPtr(CA::MetalLayer::layer())) {
    metalLayer->setDevice(rendererBackend.getDevice().get());

    auto rawLayer = (__bridge CAMetalLayer *)metalLayer.get();
    rawLayer.bounds = CGRectMake(0, 0, size.width, size.height);
    rawLayer.contentsScale = [NSScreen mainScreen].backingScaleFactor;
    jawtContext_.getSurfaceLayers().layer = rawLayer;
  }

  ~MetalRenderableResource() override {
    auto layer = (__bridge CAMetalLayer *)metalLayer.get();
    [layer removeFromSuperlayer];
  };

  MetalRenderableResource(const MetalRenderableResource &) = delete;
  MetalRenderableResource(MetalRenderableResource &&) = delete;
  auto operator=(const MetalRenderableResource &)
    -> MetalRenderableResource & = delete;
  auto operator=(MetalRenderableResource &&)
    -> MetalRenderableResource & = delete;

  void setSize(mbgl::Size size_) {
    size = size_;
    metalLayer->setDrawableSize(
      {static_cast<CGFloat>(size.width), static_cast<CGFloat>(size.height)}
    );
    depthTexture = nullptr;
    stencilTexture = nullptr;
  }

  void bind() override {
    // Acquire next drawable surface and update texture size
    metalDrawable = NS::TransferPtr(metalLayer->nextDrawable());
    auto texSize = mbgl::Size{
      static_cast<uint32_t>(metalLayer->drawableSize().width),
      static_cast<uint32_t>(metalLayer->drawableSize().height)
    };

    // Create command buffer and render pass descriptor
    commandBuffer = NS::TransferPtr(commandQueue->commandBuffer());
    renderPassDescriptor =
      NS::TransferPtr(MTL::RenderPassDescriptor::renderPassDescriptor());

    // Attach color texture to render pass
    renderPassDescriptor->colorAttachments()->object(0)->setTexture(
      metalDrawable->texture()
    );

    // Helper to create and configure a depth or stencil texture if missing
    auto ensureTexture = [&](
                           mbgl::gfx::Texture2DPtr &texture,
                           mbgl::gfx::TexturePixelType pixelType,
                           mbgl::gfx::TextureChannelDataType channelType
                         ) {
      if (!texture) {
        texture = rendererBackend.getContext().createTexture2D();
        texture->setSize(texSize);
        texture->setFormat(pixelType, channelType);
        texture->setSamplerConfiguration({
          .filter = mbgl::gfx::TextureFilterType::Linear,
          .wrapU = mbgl::gfx::TextureWrapType::Clamp,
          .wrapV = mbgl::gfx::TextureWrapType::Clamp,
        });
        dynamic_cast<mbgl::mtl::Texture2D *>(texture.get())
          ->setUsage(
            MTL::TextureUsageShaderRead | MTL::TextureUsageShaderWrite |
            MTL::TextureUsageRenderTarget
          );
      }
    };

    ensureTexture(
      depthTexture, mbgl::gfx::TexturePixelType::Depth,
      mbgl::gfx::TextureChannelDataType::Float
    );
    ensureTexture(
      stencilTexture, mbgl::gfx::TexturePixelType::Stencil,
      mbgl::gfx::TextureChannelDataType::UnsignedByte
    );

    // Configure color attachment
    auto configureColorAttachment = [&]() {
      auto &colorAttachment =
        *renderPassDescriptor->colorAttachments()->object(0);
      colorAttachment.setClearColor(MTL::ClearColor(1.0, 0.0, 0.0, 1.0));
      colorAttachment.setLoadAction(MTL::LoadActionClear);
      colorAttachment.setStoreAction(MTL::StoreActionStore);
    };

    // Configure depth attachment
    auto configureDepthAttachment = [&]() {
      depthTexture->create();
      renderPassDescriptor->depthAttachment()->setTexture(
        dynamic_cast<mbgl::mtl::Texture2D *>(depthTexture.get())
          ->getMetalTexture()
      );
      auto &depthAttachment = *renderPassDescriptor->depthAttachment();
      depthAttachment.setClearDepth(1.0);
      depthAttachment.setLoadAction(MTL::LoadActionClear);
      depthAttachment.setStoreAction(MTL::StoreActionDontCare);
    };

    // Configure stencil attachment
    auto configureStencilAttachment = [&]() {
      stencilTexture->create();
      renderPassDescriptor->stencilAttachment()->setTexture(
        dynamic_cast<mbgl::mtl::Texture2D *>(stencilTexture.get())
          ->getMetalTexture()
      );
      auto &stencilAttachment = *renderPassDescriptor->stencilAttachment();
      stencilAttachment.setClearStencil(0);
      stencilAttachment.setLoadAction(MTL::LoadActionClear);
      stencilAttachment.setStoreAction(MTL::StoreActionDontCare);
    };

    configureColorAttachment();
    configureDepthAttachment();
    configureStencilAttachment();
  }

  void swap() override {
    if (metalDrawable && commandBuffer) {
      commandBuffer->presentDrawable(metalDrawable.get());
      commandBuffer->commit();
      commandBuffer.reset();
      renderPassDescriptor.reset();
    }
  }

  void activate() { jawtContext_.lock(); }

  void deactivate() { jawtContext_.unlock(); }

  [[nodiscard]] auto getBackend() const
    -> const mbgl::mtl::RendererBackend & override {
    return rendererBackend;
  }

  [[nodiscard]] auto getCommandBuffer() const
    -> const mbgl::mtl::MTLCommandBufferPtr & override {
    return commandBuffer;
  }

  [[nodiscard]] auto getUploadPassDescriptor() const
    -> mbgl::mtl::MTLBlitPassDescriptorPtr override {
    return NS::TransferPtr(MTL::BlitPassDescriptor::alloc()->init());
  }

  [[nodiscard]] auto getRenderPassDescriptor() const
    -> const mbgl::mtl::MTLRenderPassDescriptorPtr & override {
    return renderPassDescriptor;
  }

 private:
  CanvasBackend &rendererBackend;
  JawtContext jawtContext_;
  mbgl::mtl::MTLCommandQueuePtr commandQueue;
  mbgl::mtl::MTLCommandBufferPtr commandBuffer;
  mbgl::mtl::MTLRenderPassDescriptorPtr renderPassDescriptor;
  mbgl::mtl::CAMetalDrawablePtr metalDrawable;
  mbgl::mtl::CAMetalLayerPtr metalLayer;
  mbgl::gfx::Texture2DPtr depthTexture;
  mbgl::gfx::Texture2DPtr stencilTexture;
  mbgl::Size size;
};

CanvasBackend::CanvasBackend(JNIEnv *env, jCanvas canvas)
    : mbgl::mtl::RendererBackend(mbgl::gfx::ContextMode::Unique),
      mbgl::gfx::Renderable(
        mbgl::Size(
          java_classes::get<Canvas_class>().getWidth(env, canvas),
          java_classes::get<Canvas_class>().getHeight(env, canvas)
        ),
        std::make_unique<MetalRenderableResource>(*this, env, canvas)
      ) {}

void CanvasBackend::setSize(mbgl::Size size) {
  getResource<MetalRenderableResource>().setSize(size);
}

auto CanvasBackend::getDefaultRenderable() -> mbgl::gfx::Renderable & {
  return *this;
}

void CanvasBackend::activate() {
  getResource<MetalRenderableResource>().activate();
}

void CanvasBackend::deactivate() {
  getResource<MetalRenderableResource>().deactivate();
}

}  // namespace maplibre_jni

#endif
