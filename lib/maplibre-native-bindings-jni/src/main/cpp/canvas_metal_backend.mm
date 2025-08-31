#include <iostream>
#include <memory>
#ifdef USE_METAL_BACKEND

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
      : rendererBackend(backend), jawtContext_(env, canvas) {
    commandQueue =
      NS::TransferPtr(rendererBackend.getDevice()->newCommandQueue());
    if (!commandQueue)
      throw std::runtime_error("Failed to create command queue");

    CGFloat scale = [NSScreen mainScreen].backingScaleFactor;
    auto metalLayer = [CAMetalLayer layer];
    metalLayer.bounds = CGRectMake(0, 0, size.width, size.height);
    metalLayer.contentsScale = scale;
    jawtContext_.getSurfaceLayers().layer = metalLayer;
    [metalLayer retain];

    swapchain = NS::TransferPtr(reinterpret_cast<CA::MetalLayer *>(metalLayer));
    swapchain->setDevice(rendererBackend.getDevice().get());
  }

  void setSize(mbgl::Size size_) {
    size = size_;
    swapchain->setDrawableSize(
      {static_cast<CGFloat>(size.width), static_cast<CGFloat>(size.height)}
    );
    depthTexture = nullptr;
    stencilTexture = nullptr;
  }

  void bind() override {
    // Acquire next drawable surface and update texture size
    surface = NS::TransferPtr(swapchain->nextDrawable());
    auto texSize = mbgl::Size{
      static_cast<uint32_t>(swapchain->drawableSize().width),
      static_cast<uint32_t>(swapchain->drawableSize().height)
    };

    // Create command buffer and render pass descriptor
    commandBuffer = NS::TransferPtr(commandQueue->commandBuffer());
    renderPassDescriptor =
      NS::TransferPtr(MTL::RenderPassDescriptor::renderPassDescriptor());

    // Attach color texture to render pass
    renderPassDescriptor->colorAttachments()->object(0)->setTexture(
      surface->texture()
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
        texture->setSamplerConfiguration(
          {mbgl::gfx::TextureFilterType::Linear,
           mbgl::gfx::TextureWrapType::Clamp, mbgl::gfx::TextureWrapType::Clamp}
        );
        static_cast<mbgl::mtl::Texture2D *>(texture.get())
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
        static_cast<mbgl::mtl::Texture2D *>(depthTexture.get())
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
        static_cast<mbgl::mtl::Texture2D *>(stencilTexture.get())
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
    if (surface && commandBuffer) {
      commandBuffer->presentDrawable(surface.get());
      commandBuffer->commit();
    }
  }

  void activate() { jawtContext_.lock(); }

  void deactivate() { jawtContext_.unlock(); }

  const mbgl::mtl::RendererBackend &getBackend() const override {
    return rendererBackend;
  }

  const mbgl::mtl::MTLCommandBufferPtr &getCommandBuffer() const override {
    return commandBuffer;
  }

  mbgl::mtl::MTLBlitPassDescriptorPtr getUploadPassDescriptor() const override {
    return NS::TransferPtr(MTL::BlitPassDescriptor::alloc()->init());
  }

  const mbgl::mtl::MTLRenderPassDescriptorPtr &
  getRenderPassDescriptor() const override {
    return renderPassDescriptor;
  }

 private:
  CanvasBackend &rendererBackend;
  JawtContext jawtContext_;
  mbgl::mtl::MTLCommandQueuePtr commandQueue;
  mbgl::mtl::MTLCommandBufferPtr commandBuffer;
  mbgl::mtl::MTLRenderPassDescriptorPtr renderPassDescriptor;
  mbgl::mtl::CAMetalDrawablePtr surface;
  mbgl::mtl::CAMetalLayerPtr swapchain;
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

std::unique_ptr<mbgl::gfx::Context> CanvasBackend::createContext() {
  return std::make_unique<mbgl::mtl::Context>(*this);
}

mbgl::gfx::Renderable &CanvasBackend::getDefaultRenderable() { return *this; }

void CanvasBackend::activate() {
  getResource<MetalRenderableResource>().activate();
}

void CanvasBackend::deactivate() {
  getResource<MetalRenderableResource>().deactivate();
}

}  // namespace maplibre_jni

#endif
