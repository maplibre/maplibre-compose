package org.maplibre.compose.location.desktop.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.ObjCRuntime
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.desktop.macos.ObjectiveC.DELEGATE_CLASS_NAME

internal class SystemCoreLocationClient : CoreLocationClient {
  override val backendAvailability: LocationBackendAvailability

  init {
    ObjectiveC.loadFramework("Foundation")
    ObjectiveC.loadFramework("CoreLocation")
    CoreLocationDelegateClass.register()
    backendAvailability = onMain { readUsageDescriptionAvailability() }
  }

  override val locationServicesEnabled: Boolean
    get() = ObjectiveC.runInAutoreleasePool {
      ObjectiveC.sendLong(ObjectiveC.cls("CLLocationManager"), "locationServicesEnabled") != 0L
    }

  override fun createManager(): CoreLocationManager = SystemCoreLocationManager()

  override fun close() = Unit
}

internal class SystemCoreLocationManager : CoreLocationManager {
  private val manager: Long = onMain { ObjectiveC.allocInit("CLLocationManager") }
  private var objcDelegate: Long = NULL
  private val closed = AtomicBoolean()

  override var desiredAccuracy: Double
    get() = onMain { ObjectiveC.sendDouble(manager, "desiredAccuracy") }
    set(value) {
      onMain { ObjectiveC.sendVoidDouble(manager, "setDesiredAccuracy:", value) }
    }

  override var distanceFilter: Double
    get() = onMain { ObjectiveC.sendDouble(manager, "distanceFilter") }
    set(value) {
      onMain { ObjectiveC.sendVoidDouble(manager, "setDistanceFilter:", value) }
    }

  override val location: CoreLocationReading?
    get() = onMain {
      val value = ObjectiveC.sendPointer(manager, "location")
      if (value == NULL) null else readCoreLocation(value)
    }

  override val authorizationStatus: Long
    get() = onMain { ObjectiveC.sendLong(manager, "authorizationStatus") }

  override val accuracyAuthorization: Long
    get() = onMain {
      if (ObjectiveC.respondsTo(manager, "accuracyAuthorization")) {
        ObjectiveC.sendLong(manager, "accuracyAuthorization")
      } else {
        CL_ACCURACY_AUTHORIZATION_FULL
      }
    }

  override fun setDelegate(delegate: CoreLocationDelegate?) {
    onMain {
      if (objcDelegate != NULL) {
        ObjectiveC.sendVoid(manager, "setDelegate:", NULL)
        CoreLocationDelegateClass.unbind(objcDelegate)
        ObjectiveC.release(objcDelegate)
        objcDelegate = NULL
      }
      if (delegate != null) {
        objcDelegate = ObjectiveC.allocInit(DELEGATE_CLASS_NAME)
        CoreLocationDelegateClass.bind(objcDelegate, delegate)
        ObjectiveC.sendVoid(manager, "setDelegate:", objcDelegate)
      }
    }
  }

  override fun startUpdatingLocation() {
    onMain { ObjectiveC.sendVoid(manager, "startUpdatingLocation") }
  }

  override fun stopUpdatingLocation() {
    onMain { ObjectiveC.sendVoid(manager, "stopUpdatingLocation") }
  }

  override fun requestWhenInUseAuthorization() {
    onMain { ObjectiveC.sendVoid(manager, "requestWhenInUseAuthorization") }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    onMain {
      ObjectiveC.sendVoid(manager, "stopUpdatingLocation")
      if (objcDelegate != NULL) {
        ObjectiveC.sendVoid(manager, "setDelegate:", NULL)
        CoreLocationDelegateClass.unbind(objcDelegate)
        ObjectiveC.release(objcDelegate)
        objcDelegate = NULL
      }
      ObjectiveC.release(manager)
    }
  }
}

private fun <T> onMain(action: () -> T): T = CocoaMain.run {
  ObjectiveC.runInAutoreleasePool(action)
}

private val usageDescriptionKeys =
  listOf("NSLocationWhenInUseUsageDescription", "NSLocationUsageDescription")

private fun readUsageDescriptionAvailability(): LocationBackendAvailability {
  if (usageDescription() != null) return LocationBackendAvailability.Available
  return LocationBackendAvailability.Misconfigured(
    IllegalStateException(
      "The main bundle${ObjectiveC.mainBundlePath()?.let { " at $it" } ?: ""} " +
        "does not declare NSLocationWhenInUseUsageDescription. " +
        "Core Location reads that key from the process's app Info.plist."
    )
  )
}

private fun usageDescription(): String? {
  usageDescriptionKeys.firstNotNullOfOrNull(ObjectiveC::infoDictionaryString)?.let {
    return it
  }
  // The JVM launcher can replace the live Info.plist. Copy the usage description from disk.
  for (key in usageDescriptionKeys) {
    val value = ObjectiveC.infoPlistString(key) ?: continue
    if (ObjectiveC.putInfoDictionaryString(key, value)) return value
  }
  return null
}

internal object CoreLocationDelegateClass {
  private val linker = Linker.nativeLinker()
  private val stubs = Arena.ofShared()
  private val handlers = ConcurrentHashMap<Long, CoreLocationDelegate>()
  private val twoObjectArgs = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)
  private val oneObjectArg = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)

  @Synchronized
  fun register() {
    val existing = ObjCRuntime.objc_getClass(DELEGATE_CLASS_NAME)
    if (existing != NULL) return

    val cls = ObjCRuntime.objc_allocateClassPair(ObjectiveC.cls("NSObject"), DELEGATE_CLASS_NAME, 0)
    check(cls != NULL) { "Failed to allocate $DELEGATE_CLASS_NAME" }

    val protocol = ObjCRuntime.objc_getProtocol("CLLocationManagerDelegate")
    if (protocol != NULL) {
      ObjCRuntime.class_addProtocol(cls, protocol)
    }

    addMethod(
      cls,
      "locationManager:didUpdateLocations:",
      "v@:@@",
      twoObjectArgs,
      "didUpdateLocations",
    )
    addMethod(cls, "locationManager:didFailWithError:", "v@:@@", twoObjectArgs, "didFailWithError")
    addMethod(
      cls,
      "locationManagerDidChangeAuthorization:",
      "v@:@",
      oneObjectArg,
      "didChangeAuthorization",
    )

    ObjCRuntime.objc_registerClassPair(cls)
  }

  fun bind(objcDelegate: Long, delegate: CoreLocationDelegate) {
    handlers[objcDelegate] = delegate
  }

  fun unbind(objcDelegate: Long) {
    handlers.remove(objcDelegate)
  }

  private fun addMethod(
    cls: Long,
    selectorName: String,
    types: String,
    descriptor: FunctionDescriptor,
    methodName: String,
  ) {
    val parameterCount = descriptor.argumentLayouts().size
    val parameterTypes = Array(parameterCount) { MemorySegment::class.java }
    val handle =
      MethodHandles.lookup()
        .findStatic(
          CoreLocationDelegateClass::class.java,
          methodName,
          MethodType.methodType(
            Void.TYPE,
            parameterTypes[0],
            *parameterTypes.drop(1).toTypedArray(),
          ),
        )
    val stub = linker.upcallStub(handle, descriptor, stubs)
    check(
      ObjCRuntime.class_addMethod(cls, ObjectiveC.selector(selectorName), stub.address(), types)
    ) {
      "Failed to add $selectorName to $DELEGATE_CLASS_NAME"
    }
  }

  @JvmStatic
  fun didUpdateLocations(
    self: MemorySegment,
    @Suppress("UNUSED_PARAMETER") cmd: MemorySegment,
    @Suppress("UNUSED_PARAMETER") manager: MemorySegment,
    locations: MemorySegment,
  ) {
    invokeHandler(self) { delegate ->
      delegate.didUpdateLocations(readLocations(locations.address()))
    }
  }

  @JvmStatic
  fun didFailWithError(
    self: MemorySegment,
    @Suppress("UNUSED_PARAMETER") cmd: MemorySegment,
    @Suppress("UNUSED_PARAMETER") manager: MemorySegment,
    error: MemorySegment,
  ) {
    invokeHandler(self) { delegate -> delegate.didFailWithError(readError(error.address())) }
  }

  @JvmStatic
  fun didChangeAuthorization(
    self: MemorySegment,
    @Suppress("UNUSED_PARAMETER") cmd: MemorySegment,
    @Suppress("UNUSED_PARAMETER") manager: MemorySegment,
  ) {
    invokeHandler(self) { delegate -> delegate.didChangeAuthorization() }
  }

  private inline fun invokeHandler(
    self: MemorySegment,
    crossinline action: (CoreLocationDelegate) -> Unit,
  ) {
    val delegate = handlers[self.address()] ?: return
    try {
      ObjectiveC.runInAutoreleasePool { action(delegate) }
    } catch (_: Throwable) {
      // Delegate IMPs must not throw into Core Location.
    }
  }
}

internal fun readLocations(array: Long): List<CoreLocationReading> {
  if (array == NULL) return emptyList()
  val count = ObjectiveC.sendLong(array, "count")
  return (0L until count).map { index ->
    readCoreLocation(ObjectiveC.sendPointer(array, "objectAtIndex:", index))
  }
}

internal fun readCoreLocation(location: Long): CoreLocationReading {
  check(location != NULL) { "CLLocation is null" }
  val coordinate = ObjectiveC.sendCoordinate(location, "coordinate")
  val timestamp = ObjectiveC.sendPointer(location, "timestamp")
  val ageSeconds =
    if (timestamp == NULL) 0.0
    else (-ObjectiveC.sendDouble(timestamp, "timeIntervalSinceNow")).coerceAtLeast(0.0)
  return CoreLocationReading(
    latitude = coordinate.latitude,
    longitude = coordinate.longitude,
    altitude = ObjectiveC.sendDouble(location, "altitude"),
    horizontalAccuracy = ObjectiveC.sendDouble(location, "horizontalAccuracy"),
    verticalAccuracy = ObjectiveC.sendDouble(location, "verticalAccuracy"),
    course = ObjectiveC.sendDouble(location, "course"),
    courseAccuracy = optionalDouble(location, "courseAccuracy"),
    speed = ObjectiveC.sendDouble(location, "speed"),
    speedAccuracy = optionalDouble(location, "speedAccuracy"),
    ageSeconds = ageSeconds,
  )
}

internal fun readError(error: Long): CoreLocationError {
  if (error == NULL) return CoreLocationError(domain = "", code = 0)
  return CoreLocationError(
    domain = ObjectiveC.utf8String(ObjectiveC.sendPointer(error, "domain")),
    code = ObjectiveC.sendLong(error, "code"),
  )
}

private fun optionalDouble(receiver: Long, selectorName: String): Double =
  if (ObjectiveC.respondsTo(receiver, selectorName)) {
    ObjectiveC.sendDouble(receiver, selectorName)
  } else {
    -1.0
  }
