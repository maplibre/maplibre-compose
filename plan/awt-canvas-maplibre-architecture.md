## Architecture: Connecting MapLibre Native to a Java AWT Canvas

This document outlines a clean architecture to connect MapLibre Native to a Java
`Canvas` using JAWT, across Metal, OpenGL, and Vulkan. It explains how
`RendererFrontend`, `RendererBackend`, `Renderable`, and `RenderableResource`
fit together, and provides pseudocode for platform-specific flows (macOS/Metal,
X11/Win32 for GL/Vulkan), including correct JAWT usage per Oracle’s AWT Native
Interface specification
([Java AWT Native Interface — Oracle](https://docs.oracle.com/en/java/javase/24/docs/specs/AWT_Native_Interface.html)).

### Core MapLibre roles and responsibilities

- **Frontend** drives the renderer and delivers observer callbacks on the
  main/original thread.

```cpp
    /// Must synchronously clean up the Renderer if set
    virtual void reset() = 0;

    /// Implementer must bind the renderer observer to the renderer in a
    /// appropriate manner so that the callbacks occur on the main thread
    virtual void setObserver(RendererObserver&) = 0;

    /// Coalescing updates is up to the implementer
    virtual void update(std::shared_ptr<UpdateParameters>) = 0;

    virtual const TaggedScheduler& getThreadPool() const = 0;
```

- **Backend** exposes the GPU context, activation/deactivation, shader init, and
  default surface.

```cpp
    /// Returns a reference to the default surface that should be rendered on.
    virtual Renderable& getDefaultRenderable() = 0;

    /// One-time shader initialization
    virtual void initShaders(gfx::ShaderRegistry&, const ProgramParameters&) = 0;
```

```cpp
protected:
  virtual std::unique_ptr<Context> createContext() = 0;
  virtual void activate() = 0;
  virtual void deactivate() = 0;
```

- **Renderable** wraps a backend-specific `RenderableResource` and exposes size
  and binding.

```cpp
    virtual void bind() = 0;
```

```cpp
protected:
    Renderable(const Size size_, std::unique_ptr<RenderableResource> resource_)
        : size(size_),
          resource(std::move(resource_)) {}
    template <typename T>
    T& getResource() const {
        assert(resource);
        return static_cast<T&>(*resource);
    }
```

- **Observer** reports lifecycle and telemetry to the host.

```cpp
    /// Signals that a repaint is required
    virtual void onInvalidate() {}

    /// Resource failed to download / parse
    virtual void onResourceError(std::exception_ptr) {}

    /// First frame
    virtual void onWillStartRenderingMap() {}

    /// Start of frame, initial is the first frame for this map
    virtual void onWillStartRenderingFrame() {}

    /// End of frame...
    virtual void onDidFinishRenderingFrame(RenderMode, bool /*repaint*/, bool /*placementChanged*/) {}
```

## High-level design: AWT + MapLibre

- **Frontend (API-agnostic, AWT-specific)**
  - `AwtCanvasRendererFrontend : mbgl::RendererFrontend`
  - Coalesces `update(...)`, owns `mbgl::Renderer`, posts `RendererObserver`
    events to AWT’s Event Dispatch Thread (EDT).

- **Backends (API-specific)**
  - `AwtMetalBackend : mbgl::mtl::RendererBackend`
  - `AwtGLBackend : mbgl::gl::RendererBackend`
  - `AwtVulkanBackend : mbgl::vk::RendererBackend`
  - Own the graphics context, implement `activate()/deactivate()`, return the
    default `Renderable`.

- **Renderable (AWT surface, API-agnostic)**
  - `AwtSurfaceRenderable : mbgl::gfx::Renderable`
  - Holds size and a `std::unique_ptr<RenderableResource>` that is API-specific.

- **RenderableResource (API-specific)**
  - `AwtMetalSurface : mbgl::gfx::RenderableResource`
  - `AwtGLSurface : mbgl::gfx::RenderableResource`
  - `AwtVkSurface : mbgl::gfx::RenderableResource`
  - Implements `bind()` to make the correct render target current.

### Ownership and threading

- Backend constructs and owns the default `AwtSurfaceRenderable` (and its
  `RenderableResource`).
- Frontend constructs and owns `mbgl::Renderer`, sets observer, and forwards
  updates.
- JAWT locking/unlocking and native surface acquisition happen inside backend
  `activate()/deactivate()` on the render thread.
- Observer callbacks are marshalled to the AWT EDT.

## Canvas integration (AWT-driven rendering)

- The frontend caches `UpdateParameters` and schedules a repaint on the EDT.
- Java side overrides `Canvas.paint(Graphics)` and calls a native method, which
  bridges to `Frontend.render()` on the render thread.
- This mirrors iOS (view-driven), but keeps backend and renderable separate.

Java sketch:

```java
public class MapCanvas extends Canvas {
    static { System.loadLibrary("maplibre_awt"); }

    public native void paint(Graphics g);

    public void requestRedraw() { repaint(); }
}
```

JNI bridge sketch:

```cpp
JNIEXPORT void JNICALL Java_org_maplibre_MapCanvas_paint(JNIEnv* env, jobject thiz, jobject graphics) {
    awtFrontend->render();
}
```

## Class pseudocode

### AwtCanvasRendererFrontend (API-agnostic, AWT-specific)

```cpp
class AwtCanvasRendererFrontend : public mbgl::RendererFrontend {
public:
    explicit AwtCanvasRendererFrontend(std::unique_ptr<mbgl::gfx::RendererBackend> backend)
        : backend_(std::move(backend)),
          renderer_(std::make_unique<mbgl::Renderer>(*backend_)) {}

    void reset() override {
        // Ensure synchronous shutdown on the render thread
        renderer_.reset();
    }

    void setObserver(mbgl::RendererObserver& observer) override {
        observer_ = &observer;
        // Wire observer to renderer; ensure callbacks land on AWT EDT
        // e.g., wrap observer calls with invokeLater(...)
    }

    void update(std::shared_ptr<mbgl::UpdateParameters> params) override {
        updateParameters_ = std::move(params);
        requestCanvasRepaintOnEDT();
    }

    const mbgl::TaggedScheduler& getThreadPool() const override {
        return backend_->getThreadPool();
    }

private:
    std::unique_ptr<mbgl::gfx::RendererBackend> backend_;
    std::unique_ptr<mbgl::Renderer> renderer_;
    mbgl::RendererObserver* observer_ = nullptr;
    std::shared_ptr<mbgl::UpdateParameters> updateParameters_;

public:
    void render() {
        if (!renderer_ || !updateParameters_) return;
        mbgl::gfx::BackendScope scope{*backend_, mbgl::gfx::BackendScope::ScopeType::Implicit};
        auto paramsCopy = updateParameters_;
        renderer_->render(paramsCopy);
    }
};
```

### BackendScope explained

`mbgl::gfx::BackendScope` is an RAII guard that ensures the backend’s graphics
context is active for the duration of a render and restored afterwards by
calling `activate()`/`deactivate()` on the backend. Use it to wrap each render
call so GPU state is correctly scoped even on errors.

```cpp
{
    mbgl::gfx::BackendScope scope{backend, mbgl::gfx::BackendScope::ScopeType::Implicit};
    renderer.render(params);
}
```

### AwtSurfaceRenderable (AWT surface, API-agnostic)

```cpp
class AwtSurfaceRenderable : public mbgl::gfx::Renderable {
public:
    AwtSurfaceRenderable(mbgl::Size size,
                         std::unique_ptr<mbgl::gfx::RenderableResource> resource)
        : mbgl::gfx::Renderable(size, std::move(resource)) {}

    void wait() override {
        // Optional: block until presentation/swap completes if required
    }
};
```

### Backends (per API)

```cpp
class AwtMetalBackend : public mbgl::mtl::RendererBackend {
public:
    AwtMetalBackend(JNIEnv* env, jobject canvas /* global ref, etc. */);

    mbgl::gfx::Renderable& getDefaultRenderable() override {
        return *renderable_;
    }

    void initShaders(mbgl::gfx::ShaderRegistry& registry,
                     const mbgl::ProgramParameters& params) override {
        // one-time shader init
    }

protected:
    std::unique_ptr<mbgl::gfx::Context> createContext() override {
        // create Metal context/device/command-queue
    }

    void activate() override {
        // Lock JAWT, acquire CALayer, update drawable/size, make current
    }

    void deactivate() override {
        // Unlock JAWT; presentation handled by the resource via swap()
    }

private:
    std::unique_ptr<AwtSurfaceRenderable> renderable_;
    // JAWT and platform state (global refs, layer, etc.)
};

class AwtGLBackend : public mbgl::gl::RendererBackend { /* analogous */ };
class AwtVulkanBackend : public mbgl::vk::RendererBackend { /* analogous */ };
```

## Present/swap semantics (why they live in the resource)

- Rendering targets a back buffer. “Present/swap” displays that buffer by
  swapping with the front buffer (or equivalent). The exact API differs:
  - Metal: present a `CAMetalDrawable` (layer-backed) and commit the command
    buffer.
  - OpenGL: swap the window surface buffers (platform call like
    `SwapBuffers`/`glXSwapBuffers`).
  - Vulkan: acquire a swapchain image, render, then present via
    `vkQueuePresentKHR`.

- The `RenderableResource` owns platform targets (layer/FBO/swapchain), so it
  implements:
  - `bind()`: make target current and set up per-frame descriptors.
  - `swap()`: present/commit the rendered frame.

Metal:

```cpp
class AwtMetalSurface : public mbgl::mtl::RenderableResource {
public:
    void attach(CAMetalLayer* layer);
    void bind() override {
        if (!commandQueue) commandQueue = [device newCommandQueue];
        if (!commandBuffer) commandBuffer = [commandQueue commandBuffer];
        drawable = [layer nextDrawable];
        renderPassDesc = [MTLRenderPassDescriptor renderPassDescriptor];
        renderPassDesc.colorAttachments[0].texture = drawable.texture;
    }
    void swap() override {
        if (drawable) { [commandBuffer presentDrawable:drawable]; [commandBuffer commit]; }
        commandBuffer = nil; drawable = nil; renderPassDesc = nil;
    }
};
```

OpenGL:

```cpp
class AwtGLSurface : public mbgl::gl::RenderableResource {
public:
    void bind() override {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
    }
    void swap() override {
        #if defined(_WIN32)
        ::SwapBuffers(hdc);
        #elif defined(__linux__)
        glXSwapBuffers(display, drawable);
        #elif defined(__APPLE__)
        [nsOpenGLContext flushBuffer];
        #endif
    }
};
```

Vulkan:

```cpp
class AwtVkSurface : public mbgl::vulkan::RenderableResource {
public:
    void bind() override {
        imageIndex = vkAcquireNextImageKHR(device, swapchain, timeout, imageAvailableSemaphore, VK_NULL_HANDLE);
        // Begin command buffer and render pass targeting framebuffer[imageIndex]
    }
    void swap() override {
        VkPresentInfoKHR presentInfo{...};
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain;
        presentInfo.pImageIndices = &imageIndex;
        vkQueuePresentKHR(presentQueue, &presentInfo);
    }
};
```

### RenderableResource (per API)

```cpp
class AwtMetalSurface : public mbgl::gfx::RenderableResource {
public:
    void bind() override {
        // Acquire next CAMetalDrawable, set up render pass
    }
    // Handle resize, layer changes, fence/sync, etc.
};

class AwtGLSurface : public mbgl::gfx::RenderableResource {
public:
    void bind() override {
        // Make GL context current to native surface; bind default framebuffer
    }
};

class AwtVkSurface : public mbgl::gfx::RenderableResource {
public:
    void bind() override {
        // Acquire swapchain image and begin render pass
    }
};
```

## Correct JAWT usage (modern Java)

Reference:
[Java AWT Native Interface — Oracle](https://docs.oracle.com/en/java/javase/24/docs/specs/AWT_Native_Interface.html)

General pattern:

```cpp
JAWT awt{};
awt.version = JAWT_VERSION_9; // Modern JDK
if (JAWT_GetAWT(env, &awt) == JNI_FALSE) { /* handle error */ }

JAWT_DrawingSurface* ds = awt.GetDrawingSurface(env, canvas);
if (!ds) { /* handle error */ }

jint lock = ds->Lock(ds);
if ((lock & JAWT_LOCK_ERROR) != 0) { awt.FreeDrawingSurface(ds); /* handle error */ }

JAWT_DrawingSurfaceInfo* dsi = ds->GetDrawingSurfaceInfo(ds);
if (!dsi) { ds->Unlock(ds); awt.FreeDrawingSurface(ds); /* handle error */ }

// Use dsi->platformInfo as per platform (see below)

ds->FreeDrawingSurfaceInfo(dsi);
ds->Unlock(ds);
awt.FreeDrawingSurface(ds);
```

Notes:

- On modern Java (≥ 9), you do not need legacy rendering flags. The
  `JAWT_MACOSX_USE_CALAYER` flag was needed only when requesting a pre-1.7
  rendering model.
- Always pair `Lock`/`Unlock` and `GetDrawingSurface`/`FreeDrawingSurface`
  correctly.

### macOS (CALayer bridge)

```cpp
// Objective-C++ allowed
JAWT awt{ .version = JAWT_VERSION_9 };
JAWT_GetAWT(env, &awt);

JAWT_DrawingSurface* ds = awt.GetDrawingSurface(env, canvas);
jint lock = ds->Lock(ds);
JAWT_DrawingSurfaceInfo* dsi = ds->GetDrawingSurfaceInfo(ds);

// dsi->platformInfo is an NSObject conforming to JAWT_SurfaceLayers
id<JAWT_SurfaceLayers> layersObj = (id<JAWT_SurfaceLayers>)dsi->platformInfo;

CAMetalLayer* metalLayer = ensureMetalLayer(layersObj.windowLayer);
layersObj.layer = metalLayer; // attach layer to component

// Resize: metalLayer.drawableSize = {width, height};

ds->FreeDrawingSurfaceInfo(dsi);
ds->Unlock(ds);
awt.FreeDrawingSurface(ds);
```

### X11 (Linux)

```cpp
JAWT awt{ .version = JAWT_VERSION_9 };
JAWT_GetAWT(env, &awt);

JAWT_DrawingSurface* ds = awt.GetDrawingSurface(env, canvas);
jint lock = ds->Lock(ds);
JAWT_DrawingSurfaceInfo* dsi = ds->GetDrawingSurfaceInfo(ds);

auto* x11 = (JAWT_X11DrawingSurfaceInfo*)dsi->platformInfo;
// x11->display (Display*), x11->drawable (Drawable), x11->depth, etc.

ds->FreeDrawingSurfaceInfo(dsi);
ds->Unlock(ds);
awt.FreeDrawingSurface(ds);
```

### Win32 (Windows)

```cpp
JAWT awt{ .version = JAWT_VERSION_9 };
JAWT_GetAWT(env, &awt);

JAWT_DrawingSurface* ds = awt.GetDrawingSurface(env, canvas);
jint lock = ds->Lock(ds);
JAWT_DrawingSurfaceInfo* dsi = ds->GetDrawingSurfaceInfo(ds);

auto* win = (JAWT_Win32DrawingSurfaceInfo*)dsi->platformInfo;
// win->hwnd (or hbitmap/pbits), win->hdc, win->hpalette

ds->FreeDrawingSurfaceInfo(dsi);
ds->Unlock(ds);
awt.FreeDrawingSurface(ds);
```

## Metal flow (macOS)

- Backend `activate()`:
  - Lock JAWT, obtain `JAWT_SurfaceLayers`, set `CAMetalLayer` on
    `layersObj.layer`.
  - Ensure device exists; update `drawableSize`.
  - Hook the layer into `AwtMetalSurface`.
- `RenderableResource.bind()`:
  - Acquire next `CAMetalDrawable`, create render pass descriptor.
- Rendering:
  - Issue draw calls.
- `RenderableResource.swap()`:
  - Present drawable and commit the command buffer.
- Backend `deactivate()`:
  - Unlock JAWT.

```cpp
void AwtMetalBackend::activate() {
    jawtLockAndGetLayers(..., /*out*/ layersObj);
    CAMetalLayer* layer = ensureLayer(layersObj);
    layer.device = device;
    layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    layer.drawableSize = {size.width, size.height};
    renderable_->getResource<AwtMetalSurface>().attach(layer);
}

void AwtMetalSurface::bind() {
    drawable = [layer nextDrawable];
    passDesc.colorAttachments[0].texture = drawable.texture;
    // set on encoder/commandBuffer
}

void AwtMetalBackend::deactivate() {
    // Presentation already handled by resource.swap()
    jawtUnlock(...);
}
```

## OpenGL flow (macOS, X11, Win32)

- Backend `activate()`:
  - Lock JAWT, get platform handle.
  - Create/make-current GL context bound to the native surface.
  - Update viewport on resize.
- `RenderableResource.bind()`:
  - Bind default framebuffer (0) or platform FBO.
- Rendering:
  - Issue GL draw calls.
- `RenderableResource.swap()`:
  - Swap buffers/present.
- Backend `deactivate()`:
  - Unlock JAWT.

```cpp
void AwtGLBackend::activate() {
    jawtLockAndGetPlatformInfo(..., platform);
    // macOS: attach context to view/layer (legacy), make current
    // X11: glXMakeCurrent(display, drawable, ctx)
    // Win32: wglMakeCurrent(hdc, hglrc)
    glViewport(0, 0, size.width, size.height);
}

void AwtGLSurface::bind() {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void AwtGLBackend::deactivate() {
    // Swap occurred in resource.swap()
    jawtUnlock(...);
}
```

## Vulkan flow (macOS via Metal surface, X11, Win32)

- Backend `activate()`:
  - Lock JAWT, obtain platform handles.
  - Create `VkSurfaceKHR`:
    - macOS: `vkCreateMetalSurfaceEXT` with `CAMetalLayer*`
    - X11: `vkCreateXlibSurfaceKHR` with `Display*` and `Drawable`
    - Win32: `vkCreateWin32SurfaceKHR` with `HWND/HINSTANCE`
  - Create/recreate swapchain on resize; get image views/framebuffers.
- `RenderableResource.bind()`:
  - Acquire next image (`vkAcquireNextImageKHR`), begin render pass.
- Rendering:
  - Record/submit command buffers.
- `RenderableResource.swap()`:
  - Present (`vkQueuePresentKHR`).
- Backend `deactivate()`:
  - Unlock JAWT.

```cpp
void AwtVulkanBackend::activate() {
    jawtLockAndGetPlatformInfo(..., platform);
    ensureSurfaceAndSwapchain(platformHandles, size);
    renderable_->getResource<AwtVkSurface>().syncFromSwapchain(swapchain);
}

void AwtVkSurface::bind() {
    imageIndex = vkAcquireNextImageKHR(device, swapchain, ...);
    beginRenderPass(framebuffers[imageIndex], extent);
}

void AwtVulkanBackend::deactivate() {
    endRenderPassAndSubmit(...);
    // Presentation occurred in resource.swap()
    jawtUnlock(...);
}
```

## Resize handling

- On AWT `Component` resize:
  - Frontend posts a resize event to the render thread.
  - Backend updates `Renderable.size` and recreates API-specific resources:
    - Metal: update `CAMetalLayer.drawableSize`
    - GL: update viewport and any FBOs
    - Vulkan: recreate swapchain and dependent resources
  - Next frame uses the new dimensions.

## Observer wiring

- Frontend ensures callbacks occur on the AWT EDT:

```cpp
renderer_->setObserver([this](auto&& fn) {
    // invokeLater on EDT
    java_awt_EventQueue_invokeLater(env, runnableWrapping(fn));
});
```

- Typical callbacks:
  - `onWillStartRenderingFrame()`
  - `onDidFinishRenderingFrame(RenderMode, repaint, placementChanged)`
  - `onInvalidate()` to trigger another `update(...)`

## Putting it together: frame lifecycle

- `AwtCanvasRendererFrontend.update(params)`
  - Dispatch to render thread
  - Backend `activate()`:
    - JAWT lock → native surface/layer → context current
  - `RenderableResource.bind()`:
    - Make target current (GL FBO / Metal drawable / Vulkan image)
  - Renderer draws
  - Backend `deactivate()`:
    - Present/swap/commit → JAWT unlock
  - Frontend posts observer callbacks to EDT

## References

- JAWT specification and correct usage:
  [Java AWT Native Interface — Oracle](https://docs.oracle.com/en/java/javase/24/docs/specs/AWT_Native_Interface.html)

- MapLibre Native interfaces used:
  - `mbgl::renderer::RendererFrontend` (ownership and updates)
  - `mbgl::renderer::RendererObserver` (callbacks)
  - `mbgl::gfx::RendererBackend` (context, default surface, activation)
  - `mbgl::gfx::Renderable` / `RenderableResource` (surface binding)
