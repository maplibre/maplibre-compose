package org.maplibre.compose.location.desktop.windows

import java.awt.EventQueue
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout.structLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.maplibre.compose.location.LocationProviderAvailability

internal class SystemWindowsLocationClient : WindowsLocationClient {
  private val closed = AtomicBoolean()
  private val workerThread = arrayOfNulls<Thread>(1)
  private val executor: ExecutorService = Executors.newSingleThreadExecutor { action ->
    thread(start = false, isDaemon = true, name = "maplibre-compose-winrt-location") {
        val ownsInitialization = WinRt.initialize(WinRt.RO_INIT_MULTITHREADED)
        try {
          action.run()
        } finally {
          if (ownsInitialization) WinRt.uninitialize()
        }
      }
      .also { workerThread[0] = it }
  }
  private val sessions = ConcurrentHashMap.newKeySet<SystemWindowsLocationSession>()

  override val backendAvailability: LocationProviderAvailability =
    if (!isWindows(System.getProperty("os.name"))) {
      LocationProviderAvailability.Unsupported
    } else {
      try {
        blocking {
          createAppCapability().close()
          WinRt.activate(GEOLOCATOR_CLASS).use { inspectable ->
            WinRt.queryInterface(inspectable.value, IID_GEOLOCATOR).close()
          }
        }
        LocationProviderAvailability.Available
      } catch (error: Throwable) {
        LocationProviderAvailability.Misconfigured(error)
      }
    }

  override fun checkAccess(): WindowsAccessStatus {
    if (backendAvailability != LocationProviderAvailability.Available)
      return WindowsAccessStatus.Unknown
    return blocking(::checkAccessNative)
  }

  override fun observeAccess(onChanged: (WindowsAccessStatus) -> Unit): WindowsCloseable {
    checkAvailable()
    return blocking {
      val capability = createAppCapability()
      val callback =
        WinRtEventCallback.create(IID_APP_CAPABILITY_ACCESS_CHANGED_HANDLER) { _, _ ->
          executeSafely(onFailure = { onChanged(WindowsAccessStatus.Unknown) }) {
            onChanged(checkAccessNative())
          }
        }
      try {
        val token = addEventHandler(capability.value, APP_CAPABILITY_ADD_ACCESS_CHANGED, callback)
        val observationClosed = AtomicBoolean()
        WindowsCloseable {
          if (!observationClosed.compareAndSet(false, true)) return@WindowsCloseable
          blocking {
            try {
              removeEventHandler(capability.value, APP_CAPABILITY_REMOVE_ACCESS_CHANGED, token)
            } finally {
              callback.close()
              capability.close()
            }
          }
        }
      } catch (error: Throwable) {
        callback.close()
        capability.close()
        throw error
      }
    }
  }

  override fun requestAccess(onCompleted: (WindowsAccessStatus) -> Unit) {
    checkAvailable()
    EventQueue.invokeLater {
      try {
        WinRt.inApartment(WinRt.RO_INIT_SINGLETHREADED) {
          val operation = requestAccessOperation()
          val completed = AtomicBoolean()
          lateinit var callback: WinRtAsyncCallback
          callback =
            WinRtAsyncCallback.create(IID_GEOLOCATION_ACCESS_COMPLETED_HANDLER) { _, status ->
              if (!completed.compareAndSet(false, true)) return@create
              executeCompletion(
                onFailure = {
                  if (!closed.get()) onCompleted(WindowsAccessStatus.Unknown)
                }
              ) {
                try {
                  if (status == ASYNC_COMPLETED) {
                    WinRt.intResult(operation.value, ASYNC_OPERATION_GET_RESULTS)
                    if (!closed.get()) onCompleted(checkAccessNative())
                  } else if (!closed.get()) {
                    onCompleted(WindowsAccessStatus.Unknown)
                  }
                } finally {
                  operation.close()
                  callback.close()
                }
              }
            }
          try {
            WinRt.callHresult(
              operation.value,
              ASYNC_OPERATION_PUT_COMPLETED,
              FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
              callback.segment,
            )
          } catch (error: Throwable) {
            callback.close()
            operation.close()
            throw error
          }
        }
      } catch (_: Throwable) {
        onCompleted(WindowsAccessStatus.Unknown)
      }
    }
  }

  override fun createSession(
    configuration: WindowsLocationConfiguration,
    listener: WindowsLocationListener,
  ): WindowsCloseable {
    checkAvailable()
    val session = blocking {
      SystemWindowsLocationSession.create(this, configuration, listener).also { sessions += it }
    }
    return WindowsCloseable { session.close() }
  }

  @Synchronized
  override fun close() {
    if (closed.get()) return
    sessions.toList().forEach { runCatching(it::close) }
    closed.set(true)
    executor.shutdown()
  }

  internal fun removeSession(session: SystemWindowsLocationSession) {
    sessions.remove(session)
  }

  internal fun executeSafely(onFailure: (Throwable) -> Unit, action: () -> Unit): Boolean {
    if (closed.get()) return false
    return try {
      executor.execute {
        try {
          action()
        } catch (error: Throwable) {
          onFailure(error)
        }
      }
      true
    } catch (_: RejectedExecutionException) {
      false
    }
  }

  private fun executeCompletion(onFailure: (Throwable) -> Unit, action: () -> Unit) {
    val task = Runnable {
      try {
        action()
      } catch (error: Throwable) {
        onFailure(error)
      }
    }
    try {
      executor.execute(task)
    } catch (_: RejectedExecutionException) {
      thread(isDaemon = true, name = "maplibre-compose-winrt-location-cleanup") {
        WinRt.inApartment { task.run() }
      }
    }
  }

  internal fun <T> blocking(action: () -> T): T {
    check(!closed.get()) { "The Windows location client is closed" }
    if (Thread.currentThread() === workerThread[0]) return action()
    return executor.submit(Callable(action)).get()
  }

  private fun checkAccessNative(): WindowsAccessStatus =
    createAppCapability().use { capability ->
      when (WinRt.intResult(capability.value, APP_CAPABILITY_CHECK_ACCESS)) {
        0 -> WindowsAccessStatus.DeniedBySystem
        1 -> WindowsAccessStatus.NotDeclared
        2 -> WindowsAccessStatus.DeniedByUser
        3 -> WindowsAccessStatus.UserPromptRequired
        4 -> WindowsAccessStatus.Allowed
        else -> WindowsAccessStatus.Unknown
      }
    }

  private fun createAppCapability(): ComPtr =
    WinRt.activationFactory(APP_CAPABILITY_CLASS, IID_APP_CAPABILITY_STATICS).use { factory ->
      WinRtHString.create("location").use { location ->
        Arena.ofConfined().use { arena ->
          val output = arena.allocate(ADDRESS)
          WinRt.callHresult(
            factory.value,
            APP_CAPABILITY_CREATE,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            location.segment(),
            output,
          )
          ComPtr(output.get(ADDRESS, 0))
        }
      }
    }

  private fun requestAccessOperation(): ComPtr =
    WinRt.activationFactory(GEOLOCATOR_CLASS, IID_GEOLOCATOR_STATICS).use { factory ->
      WinRt.pointerResult(factory.value, GEOLOCATOR_REQUEST_ACCESS)
    }

  private fun checkAvailable() {
    check(backendAvailability == LocationProviderAvailability.Available) {
      "Windows Runtime geolocation is unavailable: $backendAvailability"
    }
  }
}

internal class SystemWindowsLocationSession
private constructor(
  private val client: SystemWindowsLocationClient,
  private val geolocator: ComPtr,
  private val positionCallback: WinRtEventCallback,
  private val positionToken: Long,
  private val statusCallback: WinRtEventCallback,
  private val statusToken: Long,
) : AutoCloseable {
  private val closed = AtomicBoolean()

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    client.blocking {
      try {
        removeEventHandler(geolocator.value, GEOLOCATOR_REMOVE_POSITION_CHANGED, positionToken)
      } finally {
        try {
          removeEventHandler(geolocator.value, GEOLOCATOR_REMOVE_STATUS_CHANGED, statusToken)
        } finally {
          positionCallback.close()
          statusCallback.close()
          geolocator.close()
          client.removeSession(this)
        }
      }
    }
  }

  companion object {
    fun create(
      client: SystemWindowsLocationClient,
      configuration: WindowsLocationConfiguration,
      listener: WindowsLocationListener,
    ): SystemWindowsLocationSession {
      val inspectable = WinRt.activate(GEOLOCATOR_CLASS)
      val geolocator =
        try {
          WinRt.queryInterface(inspectable.value, IID_GEOLOCATOR)
        } finally {
          inspectable.close()
        }
      var positionCallback: WinRtEventCallback? = null
      var statusCallback: WinRtEventCallback? = null
      var positionToken: Long? = null
      var statusToken: Long? = null
      try {
        configureGeolocator(geolocator, configuration)
        positionCallback =
          WinRtEventCallback.create(IID_POSITION_CHANGED_HANDLER) { _, arguments ->
            WinRt.addRef(arguments)
            val ownedArguments = ComPtr(arguments)
            if (
              !client.executeSafely(listener::onFailure) {
                ownedArguments.use { listener.onPosition(readPosition(it.value)) }
              }
            ) {
              ownedArguments.close()
            }
          }
        positionToken =
          addEventHandler(geolocator.value, GEOLOCATOR_ADD_POSITION_CHANGED, positionCallback)
        statusCallback =
          WinRtEventCallback.create(IID_STATUS_CHANGED_HANDLER) { _, arguments ->
            WinRt.addRef(arguments)
            val ownedArguments = ComPtr(arguments)
            if (
              !client.executeSafely(listener::onFailure) {
                ownedArguments.use { listener.onStatus(readStatus(it.value)) }
              }
            ) {
              ownedArguments.close()
            }
          }
        statusToken =
          addEventHandler(geolocator.value, GEOLOCATOR_ADD_STATUS_CHANGED, statusCallback)
        listener.onStatus(readPositionStatus(WinRt.intResult(geolocator.value, GEOLOCATOR_STATUS)))
        return SystemWindowsLocationSession(
          client,
          geolocator,
          positionCallback,
          positionToken,
          statusCallback,
          statusToken,
        )
      } catch (error: Throwable) {
        if (statusToken != null) {
          runCatching {
            removeEventHandler(geolocator.value, GEOLOCATOR_REMOVE_STATUS_CHANGED, statusToken)
          }
        }
        if (positionToken != null) {
          runCatching {
            removeEventHandler(geolocator.value, GEOLOCATOR_REMOVE_POSITION_CHANGED, positionToken)
          }
        }
        statusCallback?.close()
        positionCallback?.close()
        geolocator.close()
        throw error
      }
    }
  }
}

private fun configureGeolocator(
  geolocator: ComPtr,
  configuration: WindowsLocationConfiguration,
) {
  WinRt.activationFactory(PROPERTY_VALUE_CLASS, IID_PROPERTY_VALUE_STATICS).use { factory ->
    Arena.ofConfined().use { arena ->
      val boxedOutput = arena.allocate(ADDRESS)
      WinRt.callHresult(
        factory.value,
        PROPERTY_VALUE_CREATE_UINT32,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
        configuration.desiredAccuracyMeters,
        boxedOutput,
      )
      ComPtr(boxedOutput.get(ADDRESS, 0)).use { boxed ->
        WinRt.queryInterface(boxed.value, IID_REFERENCE_UINT32).use { reference ->
          geolocator.queryInterface(IID_GEOLOCATOR_SCALAR_ACCURACY).use { scalarAccuracy ->
            WinRt.callHresult(
              scalarAccuracy.value,
              GEOLOCATOR_SCALAR_PUT_ACCURACY,
              FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
              reference.value,
            )
          }
        }
      }
    }
  }
  WinRt.callHresult(
    geolocator.value,
    GEOLOCATOR_PUT_REPORT_INTERVAL,
    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
    configuration.reportIntervalMilliseconds,
  )
}

private fun readPosition(arguments: MemorySegment): WindowsLocationMeasurement =
  WinRt.queryInterface(arguments, IID_POSITION_CHANGED_ARGS).use { typedArguments ->
    WinRt.pointerResult(typedArguments.value, POSITION_CHANGED_ARGS_POSITION).use { position ->
      WinRt.queryInterface(position.value, IID_GEOPOSITION).use { typedPosition ->
        WinRt.pointerResult(typedPosition.value, GEOPOSITION_COORDINATE).use { coordinate ->
          WinRt.queryInterface(coordinate.value, IID_GEOCOORDINATE).use { typedCoordinate ->
            WindowsLocationMeasurement(
              latitude = WinRt.doubleResult(typedCoordinate.value, GEOCOORDINATE_LATITUDE),
              longitude = WinRt.doubleResult(typedCoordinate.value, GEOCOORDINATE_LONGITUDE),
              altitudeMeters = readOptionalDouble(typedCoordinate.value, GEOCOORDINATE_ALTITUDE),
              horizontalAccuracyMeters =
                WinRt.doubleResult(typedCoordinate.value, GEOCOORDINATE_ACCURACY),
              verticalAccuracyMeters =
                readOptionalDouble(typedCoordinate.value, GEOCOORDINATE_ALTITUDE_ACCURACY),
              headingDegrees = readOptionalDouble(typedCoordinate.value, GEOCOORDINATE_HEADING),
              speedMetersPerSecond = readOptionalDouble(typedCoordinate.value, GEOCOORDINATE_SPEED),
              windowsTimestampTicks =
                WinRt.longResult(typedCoordinate.value, GEOCOORDINATE_TIMESTAMP),
            )
          }
        }
      }
    }
  }

private fun readOptionalDouble(instance: MemorySegment, slot: Int): Double? =
  WinRt.nullablePointerResult(instance, slot)?.use { reference ->
    WinRt.doubleResult(reference.value, REFERENCE_VALUE)
  }

private fun readStatus(arguments: MemorySegment): WindowsPositionStatus =
  WinRt.queryInterface(arguments, IID_STATUS_CHANGED_ARGS).use { typedArguments ->
    readPositionStatus(WinRt.intResult(typedArguments.value, STATUS_CHANGED_ARGS_STATUS))
  }

private fun readPositionStatus(status: Int): WindowsPositionStatus =
  when (status) {
    0 -> WindowsPositionStatus.Ready
    1 -> WindowsPositionStatus.Initializing
    2 -> WindowsPositionStatus.NoData
    3 -> WindowsPositionStatus.Disabled
    4 -> WindowsPositionStatus.NotInitialized
    5 -> WindowsPositionStatus.NotAvailable
    else -> WindowsPositionStatus.Unknown
  }

private fun addEventHandler(
  instance: MemorySegment,
  slot: Int,
  callback: WinRtEventCallback,
): Long =
  Arena.ofConfined().use { arena ->
    val token = arena.allocate(JAVA_LONG)
    WinRt.callHresult(
      instance,
      slot,
      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
      callback.segment,
      token,
    )
    token.get(JAVA_LONG, 0)
  }

private fun removeEventHandler(instance: MemorySegment, slot: Int, token: Long) {
  Arena.ofConfined().use { arena ->
    val nativeToken = arena.allocate(EVENT_REGISTRATION_TOKEN)
    nativeToken.set(JAVA_LONG, 0, token)
    WinRt.callHresult(
      instance,
      slot,
      FunctionDescriptor.of(JAVA_INT, ADDRESS, EVENT_REGISTRATION_TOKEN),
      nativeToken,
    )
  }
}

private val EVENT_REGISTRATION_TOKEN = structLayout(JAVA_LONG)

private const val APP_CAPABILITY_CLASS =
  "Windows.Security.Authorization.AppCapabilityAccess.AppCapability"
private const val GEOLOCATOR_CLASS = "Windows.Devices.Geolocation.Geolocator"
private const val PROPERTY_VALUE_CLASS = "Windows.Foundation.PropertyValue"

private const val IID_APP_CAPABILITY_STATICS = "7c353e2a-46ee-44e5-af3d-6ad3fc49bd22"
private const val IID_GEOLOCATOR = "a9c3bf62-4524-4989-8aa9-de019d2e551f"
private const val IID_GEOLOCATOR_STATICS = "9a8e7571-2df5-4591-9f87-eb5fd894e9b7"
private const val IID_GEOLOCATOR_SCALAR_ACCURACY = "96f5d3c1-b80f-460a-994d-a96c47a51aa4"
private const val IID_PROPERTY_VALUE_STATICS = "629bdbc8-d932-4ff4-96b9-8d96c5c1e858"
private const val IID_REFERENCE_UINT32 = "513ef3af-e784-5325-a91e-97c2b8111cf3"
private const val IID_POSITION_CHANGED_ARGS = "37859ce5-9d1e-46c5-bf3b-6ad8cac1a093"
private const val IID_STATUS_CHANGED_ARGS = "3453d2da-8c93-4111-a205-9aecfc9be5c0"
private const val IID_GEOPOSITION = "c18d0454-7d41-4ff7-a957-9dffb4ef7f5b"
private const val IID_GEOCOORDINATE = "ee21a3aa-976a-4c70-803d-083ea55bcbc4"
private const val IID_APP_CAPABILITY_ACCESS_CHANGED_HANDLER = "6d923c95-7b83-5f59-8883-f44175284898"
private const val IID_POSITION_CHANGED_HANDLER = "df3c6164-4e7b-5e8e-9a7e-13da059dec1e"
private const val IID_STATUS_CHANGED_HANDLER = "97fcf582-de6b-5cd3-9690-e2ecbb66da4d"
private const val IID_GEOLOCATION_ACCESS_COMPLETED_HANDLER = "f3524c93-e5c7-5b88-bedb-d3e637cff271"

private const val APP_CAPABILITY_CREATE = 8
private const val APP_CAPABILITY_CHECK_ACCESS = 9
private const val APP_CAPABILITY_ADD_ACCESS_CHANGED = 10
private const val APP_CAPABILITY_REMOVE_ACCESS_CHANGED = 11
private const val GEOLOCATOR_REQUEST_ACCESS = 6
private const val ASYNC_OPERATION_PUT_COMPLETED = 6
private const val ASYNC_OPERATION_GET_RESULTS = 8
private const val ASYNC_COMPLETED = 1
private const val PROPERTY_VALUE_CREATE_UINT32 = 11
private const val GEOLOCATOR_SCALAR_PUT_ACCURACY = 7
private const val GEOLOCATOR_PUT_REPORT_INTERVAL = 11
private const val GEOLOCATOR_STATUS = 12
private const val GEOLOCATOR_ADD_POSITION_CHANGED = 15
private const val GEOLOCATOR_REMOVE_POSITION_CHANGED = 16
private const val GEOLOCATOR_ADD_STATUS_CHANGED = 17
private const val GEOLOCATOR_REMOVE_STATUS_CHANGED = 18
private const val POSITION_CHANGED_ARGS_POSITION = 6
private const val STATUS_CHANGED_ARGS_STATUS = 6
private const val GEOPOSITION_COORDINATE = 6
private const val GEOCOORDINATE_LATITUDE = 6
private const val GEOCOORDINATE_LONGITUDE = 7
private const val GEOCOORDINATE_ALTITUDE = 8
private const val GEOCOORDINATE_ACCURACY = 9
private const val GEOCOORDINATE_ALTITUDE_ACCURACY = 10
private const val GEOCOORDINATE_HEADING = 11
private const val GEOCOORDINATE_SPEED = 12
private const val GEOCOORDINATE_TIMESTAMP = 13
private const val REFERENCE_VALUE = 6
