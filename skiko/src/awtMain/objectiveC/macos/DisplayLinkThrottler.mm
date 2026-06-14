#ifdef SK_METAL

#import <jawt.h>
#import <jawt_md.h>
#import <QuartzCore/QuartzCore.h>
#import <AppKit/AppKit.h>
#import <stdatomic.h>
#import <objc/runtime.h>
#import <mach/mach_time.h>

// Converts a CVTimeStamp hostTime (mach_absolute_time units) to nanoseconds on the same clock that
// the JVM's System.nanoTime() reads on macOS, so the value is directly usable as a frame target time.
static int64_t machHostTimeToNanos(uint64_t hostTime) {
    static mach_timebase_info_data_t timebase = {0, 0};
    if (timebase.denom == 0) {
        mach_timebase_info(&timebase);
    }
    // numer/denom is 1/1 on x86_64 and a small ratio (e.g. 125/3) on Apple Silicon; for any realistic
    // uptime hostTime * numer stays within int64.
    return (int64_t)(hostTime * timebase.numer / timebase.denom);
}

typedef void (^OnScreenChangeCallback)(void);

@interface NSWindow (OnScreenChangeCallbackExtension)

- (void)skiko_setupOnScreenChangeCallback:(OnScreenChangeCallback)callback;

@end

static void *OnScreenChangeCallbackKey = &OnScreenChangeCallbackKey;

@implementation NSWindow (OnScreenChangeCallbackExtension)

- (void)skiko_setupOnScreenChangeCallback:(OnScreenChangeCallback)callback {
    objc_setAssociatedObject(self, OnScreenChangeCallbackKey, [callback copy], OBJC_ASSOCIATION_COPY_NONATOMIC);

    NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
    [center addObserver:self selector:@selector(skiko_onScreenChangeCallback) name:NSWindowDidChangeScreenNotification object:nil];
}

- (void)skiko_onScreenChangeCallback {
    OnScreenChangeCallback callback = objc_getAssociatedObject(self, OnScreenChangeCallbackKey);
    callback();
}

@end

@interface DisplayLinkThrottler : NSObject

- (void)onVSync;
- (void)onVSyncWithOutputTimeNanos:(int64_t)outputTimeNanos;

@end

static CVReturn displayLinkCallback(CVDisplayLinkRef displayLink, const CVTimeStamp *now, const CVTimeStamp *outputTime, CVOptionFlags flagsIn, CVOptionFlags *flagsOut, void *displayLinkContext) {
    DisplayLinkThrottler *throttler = (__bridge DisplayLinkThrottler *)displayLinkContext;

    // outputTime is the predicted present time of the frame this vsync drives; surface it so the
    // frame target time isn't discarded (skiko render-context plan, §5 / step K7).
    int64_t outputTimeNanos = (outputTime != NULL) ? machHostTimeToNanos(outputTime->hostTime) : 0;
    [throttler onVSyncWithOutputTimeNanos:outputTimeNanos];

    return kCVReturnSuccess;
}

@implementation DisplayLinkThrottler {
    // CGDirectDisplayID is an alias for uint32_t, we'll use int64_t to keep -1 in case there is no screen
    int64_t _currentScreenID;
    CGDirectDisplayID _displayLinkScreenID;
    CVDisplayLinkRef _displayLink;

    // Lock to protect internal state
    NSLock *_lock;
    NSConditionLock *_vsyncConditionLock;
    BOOL _isSleeping;
    __weak NSWindow *_window;

    // Predicted present time (nanoseconds, System.nanoTime clock) of the most recent vsync; published
    // by the display-link callback and read by waitVSync after it unblocks.
    _Atomic int64_t _outputTimeNanos;
}

- (instancetype)initWithWindow:(NSWindow *)window {
    self = [super init];

    if (self) {
        _currentScreenID = -1;
        _window = window;
        _displayLink = nil;
        _vsyncConditionLock = [[NSConditionLock alloc] initWithCondition: 1];
        _lock = [NSLock new];
        _isSleeping = NO;

        NSNotificationCenter *notificationCenter = [[NSWorkspace sharedWorkspace] notificationCenter];

        [notificationCenter addObserver:self
                            selector:@selector(systemWillSleep)
                            name:NSWorkspaceWillSleepNotification
                            object:nil];

        [notificationCenter addObserver:self
                            selector:@selector(systemDidWake)
                            name:NSWorkspaceDidWakeNotification
                            object:nil];

        __weak DisplayLinkThrottler *weakSelf = self;
        [window skiko_setupOnScreenChangeCallback:^{
            [weakSelf onScreenDidChange];
        }];

        if (NSThread.currentThread.isMainThread) {
            [self onScreenDidChange];
        } else {
            // In case of OpenJDK, EDT thread != NSThread main thread
            // There is one thing we should keep in mind. Postponing creation of vsync throttler will cause that for sometime there won't be any waiting:
            //
            // ```
            // create window
            // (10x) schedule render -> render
            // onScreenDidChange
            // schedule render -> wait vsync -> render
            // ```
            //
            // It is probably okay, but possible alternative would be to wait for throttler creation. It is difficult, so we can just keep the current code.
            __weak DisplayLinkThrottler *weakSelf = self;
            dispatch_async(dispatch_get_main_queue(), ^{
                [weakSelf onScreenDidChange];
            });
        }
    }

    return self;
}

- (void)onScreenDidChange {
    [_lock lock];

    assert(NSThread.currentThread.isMainThread);

    NSScreen *screen = _window.screen;
    NSDictionary* screenDescription = [screen deviceDescription];
    NSNumber* screenNumber = [screenDescription objectForKey:@"NSScreenNumber"];

    if (screenNumber) {
        _currentScreenID = [screenNumber longValue];
    } else {
        _currentScreenID = -1;
    }

    [self updateDisplayLink];

    [_lock unlock];
}

- (void)updateDisplayLink {
    // Assumed to be run inside _lock

    if (_isSleeping) {
        // invalidate or do nothing if no display link is present
        [self invalidateDisplayLink];
    } else {
        if (_displayLink) {
            // if display link is present, check if it's for the correct screen, avoid conversion shenanigans
            int64_t displayLinkScreenID = _displayLinkScreenID;

            if (displayLinkScreenID != _currentScreenID) {
                [self createDisplayLink];
            }
        } else {
            // no display link, create one
            [self createDisplayLink];
        }
    }
}

- (void)systemWillSleep {
    [_lock lock];

    _isSleeping = YES;
    [self updateDisplayLink];
    [self onVSync];

    [_lock unlock];
}

- (void)systemDidWake {
    [_lock lock];

    _isSleeping = NO;
    [self updateDisplayLink];

    [_lock unlock];
}

- (void)onVSync {
    // No predicted present time available (e.g. wake from sleep) -> publish 0 ("unknown").
    [self onVSyncWithOutputTimeNanos:0];
}

- (void)onVSyncWithOutputTimeNanos:(int64_t)outputTimeNanos {
    atomic_store(&_outputTimeNanos, outputTimeNanos);
    // Lock condition lock and immediately unlock setting condition variable to 1 (can render now)
    [_vsyncConditionLock lock];
    [_vsyncConditionLock unlockWithCondition:1];
}

// Returns the predicted present time (nanoseconds, System.nanoTime clock) of the vsync that unblocked
// this wait, or 0 if there is no display link / the time is unknown.
- (int64_t)waitVSync {
    BOOL hasDisplayLink;

    [_lock lock];
    hasDisplayLink = _displayLink != nil;
    [_lock unlock];

    // No display link to wait for
    if (!hasDisplayLink) {
        return 0;
    }

    // Wait until `onVSync` signals 1, then immediately lock, set it to 0 and unlock again.
    [_vsyncConditionLock lockWhenCondition:1];
    [_vsyncConditionLock unlockWithCondition:0];

    return atomic_load(&_outputTimeNanos);
}

- (void)invalidateDisplayLink {
    // Assumed to be run inside _lock, except for dealloc
    if (_displayLink) {
        CVDisplayLinkStop(_displayLink);
        CVDisplayLinkRelease(_displayLink);
        _displayLink = nil;

    }
}

- (void)createDisplayLink {
    // Assumed to be run inside _lock

    [self invalidateDisplayLink];

    if (_currentScreenID < 0) {
        return;
    }

    CVReturn result;

    result = CVDisplayLinkCreateWithCGDisplay(
        _currentScreenID /* truncated to uint32_t aka CGDirectDisplayID */,
        &_displayLink
    );

    if (result != kCVReturnSuccess) {
        _displayLink = nil;
        return;
    }

    result = CVDisplayLinkSetOutputCallback(_displayLink, &displayLinkCallback, (__bridge void *)(self));

    if (result != kCVReturnSuccess) {
        CVDisplayLinkRelease(_displayLink);
        _displayLink = nil;
        return;
    }

    result = CVDisplayLinkStart(_displayLink);

    if (result != kCVReturnSuccess) {
        CVDisplayLinkRelease(_displayLink);
        _displayLink = nil;
        return;
    }

    _displayLinkScreenID = _currentScreenID;

}

- (void)dealloc {
    [self invalidateDisplayLink];
}

@end

extern "C" {

JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_redrawer_DisplayLinkThrottler_create(JNIEnv *env, jobject obj, jlong windowPtr) {
    NSWindow *window = (__bridge NSWindow *) (void *) windowPtr;
    DisplayLinkThrottler *throttler = [[DisplayLinkThrottler alloc] initWithWindow:window];

    return (jlong) (__bridge_retained void *) throttler;
}

JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_DisplayLinkThrottler_dispose(JNIEnv *env, jobject obj, jlong throttlerPtr) {
    DisplayLinkThrottler *throttler = (__bridge_transfer DisplayLinkThrottler *) (void *) throttlerPtr;
    // throttler will be released by ARC and deallocated in the end of this scope.
}

JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_redrawer_DisplayLinkThrottler_waitVSync(JNIEnv *env, jobject obj, jlong throttlerPtr) {
    DisplayLinkThrottler *throttler = (__bridge DisplayLinkThrottler *) (void *) throttlerPtr;

    return (jlong) [throttler waitVSync];
}

}

#endif // SK_METAL