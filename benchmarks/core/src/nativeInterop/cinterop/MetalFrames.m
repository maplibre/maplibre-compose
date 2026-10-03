#import "MetalFrames.h"
#import <Metal/Metal.h>
#import <objc/runtime.h>
#import <TargetConditionals.h>
#if TARGET_OS_OSX
#import <AppKit/AppKit.h>
#import <CoreVideo/CoreVideo.h>
#else
#import <UIKit/UIKit.h>
#endif

#if !TARGET_OS_SIMULATOR

@interface BenchmarkMetalFrames ()
- (void)refreshPeriod:(double)period;
@end
#if TARGET_OS_OSX
static CVReturn displayTick(CVDisplayLinkRef link, const CVTimeStamp *now,
                           const CVTimeStamp *output, CVOptionFlags flags,
                           CVOptionFlags *outFlags, void *context) {
  [(__bridge BenchmarkMetalFrames *)context refreshPeriod:
    (double)output->videoRefreshPeriod / output->videoTimeScale];
  return kCVReturnSuccess;
}
#endif

@interface BenchmarkDrawableHistory : NSObject
@property NSLock *lock;
@property NSMutableDictionary<NSNumber *, NSNumber *> *presented;
@property NSHashTable<id<MTLDrawable>> *drawables;
@property BOOL collecting;
- (void)record:(id<MTLDrawable>)drawable;
@end

@implementation BenchmarkDrawableHistory
- (instancetype)init {
  if ((self = [super init])) {
    _lock = [NSLock new];
    _presented = [NSMutableDictionary new];
    _drawables = [NSHashTable weakObjectsHashTable];
  }
  return self;
}
- (void)record:(id<MTLDrawable>)drawable {
  double time = drawable.presentedTime;
  if (time <= 0) return;
  [_lock lock];
  _presented[@(drawable.drawableID)] = @((uint64_t)(time * 1e9));
  if (!_collecting && _presented.count > 128) {
    NSNumber *oldest = [[_presented allKeys] sortedArrayUsingSelector:@selector(compare:)].firstObject;
    [_presented removeObjectForKey:oldest];
  }
  [_lock unlock];
}
@end

static char historyKey;
static id<MTLDrawable> (*originalNextDrawable)(id, SEL);

static id<MTLDrawable> benchmarkNextDrawable(CAMetalLayer *layer, SEL selector) {
  id<MTLDrawable> drawable = originalNextDrawable(layer, selector);
  if (!drawable) return nil;
  BenchmarkDrawableHistory *history = objc_getAssociatedObject(layer, &historyKey);
  if (!history) {
    history = [BenchmarkDrawableHistory new];
    objc_setAssociatedObject(layer, &historyKey, history, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
  }
  [history.lock lock];
  [history.drawables addObject:drawable];
  [history.lock unlock];
  [drawable addPresentedHandler:^(id<MTLDrawable> presented) { [history record:presented]; }];
  return drawable;
}

@implementation BenchmarkMetalFrames {
  NSArray<CAMetalLayer *> *_layers;
  uint64_t _start;
  uint64_t _end;
  NSObject *_screen;
  NSLock *_refreshLock;
  NSMutableArray<NSArray<NSNumber *> *> *_periods;
#if TARGET_OS_OSX
  CVDisplayLinkRef _displayLink;
#else
  CADisplayLink *_displayLink;
#endif
}
+ (BOOL)available { return YES; }
+ (void)install {
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    Method method = class_getInstanceMethod(CAMetalLayer.class, @selector(nextDrawable));
    originalNextDrawable = (void *)method_setImplementation(method, (IMP)benchmarkNextDrawable);
  });
}
- (instancetype)initWithLayers:(NSArray<CAMetalLayer *> *)layers screen:(NSObject *)screen {
  if ((self = [super init])) {
    _layers = [layers copy];
    _screen = screen;
    _refreshLock = [NSLock new];
    _periods = [NSMutableArray new];
  }
  return self;
}
- (void)refreshPeriod:(double)period {
  if (period <= 0) return;
  uint64_t ns = (uint64_t)(period * 1e9);
  [_refreshLock lock];
  [_periods addObject:@[@((uint64_t)(CACurrentMediaTime() * 1e9)), @(ns)]];
  [_refreshLock unlock];
}
#if !TARGET_OS_OSX
- (void)displayTick:(CADisplayLink *)link {
  [self refreshPeriod:link.targetTimestamp - link.timestamp];
}
#endif
- (void)startDuration:(double)milliseconds {
  for (CAMetalLayer *layer in _layers) {
    BenchmarkDrawableHistory *history = objc_getAssociatedObject(layer, &historyKey);
    NSAssert(history, @"The map layer has not acquired a drawable during warm-up");
    [history.lock lock];
    history.collecting = YES;
    [history.lock unlock];
  }
  _start = (uint64_t)(CACurrentMediaTime() * 1e9);
  _end = milliseconds > 0 ? _start + (uint64_t)(milliseconds * 1e6) : UINT64_MAX;
#if TARGET_OS_OSX
  CGDirectDisplayID display = [((NSScreen *)_screen).deviceDescription[@"NSScreenNumber"] unsignedIntValue];
  NSAssert(CVDisplayLinkCreateWithCGDisplay(display, &_displayLink) == kCVReturnSuccess,
           @"Cannot observe the map display's refresh rate");
  CVDisplayLinkSetOutputCallback(_displayLink, displayTick, (__bridge void *)self);
  CVDisplayLinkStart(_displayLink);
#else
  _displayLink = [(UIScreen *)_screen displayLinkWithTarget:self selector:@selector(displayTick:)];
  _displayLink.preferredFrameRateRange = CAFrameRateRangeMake(0, ((UIScreen *)_screen).maximumFramesPerSecond,
                                                            ((UIScreen *)_screen).maximumFramesPerSecond);
  [_displayLink addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
#endif
}
- (void)end {
  _end = MIN(_end, (uint64_t)(CACurrentMediaTime() * 1e9));
}
- (NSString *)report {
#if TARGET_OS_OSX
  CVDisplayLinkStop(_displayLink);
  CVDisplayLinkRelease(_displayLink);
  _displayLink = NULL;
#else
  [_displayLink invalidate];
  _displayLink = nil;
#endif
  NSMutableSet<NSNumber *> *times = [NSMutableSet new];
  for (CAMetalLayer *layer in _layers) {
    BenchmarkDrawableHistory *history = objc_getAssociatedObject(layer, &historyKey);
    // Presentation callbacks can be delivered after the UI callback. Query live drawables too,
    // without retaining the layer's finite drawable pool during rendering.
    [history.lock lock];
    NSArray *live = history.drawables.allObjects;
    [history.lock unlock];
    for (id<MTLDrawable> drawable in live) [history record:drawable];
    [history.lock lock];
    [times addObjectsFromArray:history.presented.allValues];
    history.collecting = NO;
    [history.lock unlock];
  }
  NSArray *ordered = [[times allObjects] sortedArrayUsingSelector:@selector(compare:)];
  [_refreshLock lock];
  NSArray *periods = [_periods copy];
  [_refreshLock unlock];
  NSDictionary *capture = @{
    @"source": @"metal-presented", @"layer": @"map", @"gaps_ns": @[],
    @"presented_ns": ordered, @"refresh_periods_ns": periods,
    @"window": @{@"start_ns": @(_start), @"end_ns": @(_end)}
  };
  NSData *json = [NSJSONSerialization dataWithJSONObject:capture options:0 error:nil];
  return [[NSString alloc] initWithData:json encoding:NSUTF8StringEncoding];
}
@end

#else
// The simulator SDK omits Metal's drawable presentation API.
@implementation BenchmarkMetalFrames
+ (BOOL)available { return NO; }
+ (void)install {}
- (instancetype)initWithLayers:(NSArray<CAMetalLayer *> *)layers screen:(NSObject *)screen {
  return [super init];
}
- (void)startDuration:(double)milliseconds {}
- (void)end {}
- (NSString *)report { return @""; }
@end
#endif
