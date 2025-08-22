#ifdef __APPLE__
#import <Cocoa/Cocoa.h>
#import <QuartzCore/CAMetalLayer.h>
#include <jawt.h>
#include <jawt_md.h>

#include "canvas_renderer.hpp"

namespace maplibre_jni {

void* CanvasSurfaceInfo::createMetalLayer() {
  CGFloat scale = [NSScreen mainScreen].backingScaleFactor;
  CAMetalLayer* metalLayer = [CAMetalLayer layer];
  metalLayer.bounds = CGRectMake(0, 0, 1, 1);
  metalLayer.contentsScale = scale;
  auto* macInfo = (id<JAWT_SurfaceLayers>)(drawingSurfaceInfo_->platformInfo);
  macInfo.layer = metalLayer;
  [metalLayer retain];
  metalLayer_ = metalLayer;
  return metalLayer_;
}

}  // namespace maplibre_jni
#endif  // __APPLE__
