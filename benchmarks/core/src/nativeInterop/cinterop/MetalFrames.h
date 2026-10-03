#import <Foundation/Foundation.h>
#import <QuartzCore/CAMetalLayer.h>

NS_ASSUME_NONNULL_BEGIN

// Used only by benchmark hosts; the published MapLibre libraries do not link this helper.
@interface BenchmarkMetalFrames : NSObject
+ (BOOL)available;
+ (void)install;
- (instancetype)initWithLayers:(NSArray<CAMetalLayer *> *)layers screen:(NSObject *)screen;
- (void)startDuration:(double)milliseconds;
- (void)end;
- (NSString *)report;
@end

NS_ASSUME_NONNULL_END
