package com.example.quick_blue

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*


private const val TAG = "QuickBluePlugin"

/** QuickBluePlugin */
@SuppressLint("MissingPermission")
class QuickBluePlugin: FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var method : MethodChannel
  private lateinit var eventAvailabilityChange : EventChannel
  private lateinit var eventScanResult : EventChannel
  private lateinit var messageConnector: BasicMessageChannel<Any>

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    method = MethodChannel(flutterPluginBinding.binaryMessenger, "quick_blue/method")
    eventAvailabilityChange = EventChannel(flutterPluginBinding.binaryMessenger, "quick_blue/event.availabilityChange")
    eventScanResult = EventChannel(flutterPluginBinding.binaryMessenger, "quick_blue/event.scanResult")
    messageConnector = BasicMessageChannel(flutterPluginBinding.binaryMessenger, "quick_blue/message.connector", StandardMessageCodec.INSTANCE)
    method.setMethodCallHandler(this)
    eventAvailabilityChange.setStreamHandler(this)
    eventScanResult.setStreamHandler(this)

    context = flutterPluginBinding.applicationContext
    mainThreadHandler = Handler(Looper.getMainLooper())
    bluetoothManager = flutterPluginBinding.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    context.registerReceiver(
      broadcastReceiver,
      IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    )
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    context.unregisterReceiver(broadcastReceiver)
    eventAvailabilityChange.setStreamHandler(null)
    eventScanResult.setStreamHandler(null)
    method.setMethodCallHandler(null)
  }

  private lateinit var context: Context
  private lateinit var mainThreadHandler: Handler
  private lateinit var bluetoothManager: BluetoothManager

  private val knownGatts = mutableListOf<BluetoothGatt>()

  private fun sendMessage(messageChannel: BasicMessageChannel<Any>, message: Map<String, Any>) {
    mainThreadHandler.post { messageChannel.send(message) }
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "isBluetoothAvailable" -> {
        result.success(bluetoothManager.adapter?.isEnabled?:false)
      }
      "startScan" -> {
        var btDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        if (btDevices.isNotEmpty()) {
        //https://stackoverflow.com/questions/73107781/devices-with-android-12-keep-bluetooth-le-connection-even-when-app-is-closed
          var gatt: BluetoothGatt? = null
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              gatt = btDevices.first().connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
          } else {
              gatt = btDevices.first().connectGatt(context, true, gattCallback)
          }
          gatt?.let { knownGatts.add(it) }
          result.success(null)
        } else if (knownGatts.isNotEmpty()) {
          var gatt: BluetoothGatt? = null
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = knownGatts.first().device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
          } else {
            gatt = knownGatts.first().device.connectGatt(context, true, gattCallback)
          }
          if (gatt == null) {
            bluetoothManager.adapter?.bluetoothLeScanner?.startScan(scanCallback)
          }
          result.success(null)
        } else {
          bluetoothManager.adapter?.bluetoothLeScanner?.startScan(scanCallback)
          result.success(null)
        }
      }
      "stopScan" -> {
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        result.success(null)
      }
      "connect" -> {
        val deviceId = call.argument<String>("deviceId")!!
        if (knownGatts.find { it.device.address == deviceId } != null) {
          return result.success(null)
        }
        val remoteDevice = bluetoothManager.adapter.getRemoteDevice(deviceId as String)
        var gatt: BluetoothGatt? = null
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
             gatt = remoteDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
             gatt = remoteDevice.connectGatt(context, false, gattCallback)
        }
        gatt?.let { knownGatts.add(it) }
        result.success(null)
      }
      "disconnect" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
                ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        stopConnection(gatt)
        result.success(null)
      }
      "discoverServices" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
                ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        gatt.discoverServices()
        result.success(null)
      }
      "setNotifiable" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val service = call.argument<String>("service")!!
        val characteristic = call.argument<String>("characteristic")!!
        val bleInputProperty = call.argument<String>("bleInputProperty")!!
        Log.v(TAG, "setNotifiable, deviceId: $deviceId, service: $service, characteristic: $characteristic")
        val gatt = knownGatts.find { it.device.address == deviceId }
                ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        val c = gatt.getCharacteristic(service, characteristic)
                ?: return result.error("IllegalArgument", "Unknown characteristic: $characteristic", null)
        gatt.setNotifiable(c, bleInputProperty)
        result.success(null)
      }
      "readValue" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val service = call.argument<String>("service")!!
        val characteristic = call.argument<String>("characteristic")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
                ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        val c = gatt.getCharacteristic(service, characteristic)
                ?: return result.error("IllegalArgument", "Unknown characteristic: $characteristic", null)
        if (gatt.readCharacteristic(c))
          result.success(null)
        else
          result.error("Characteristic unavailable", null, null)
      }
      "writeValue" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val service = call.argument<String>("service")!!
        val characteristic = call.argument<String>("characteristic")!!
        val value = call.argument<ByteArray>("value")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
                ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        val c = gatt.getCharacteristic(service, characteristic)
                ?: return result.error("IllegalArgument", "Unknown characteristic: $characteristic", null)

        var writeResult = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          writeResult = gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        }else{
          c.value = value
          writeResult = gatt.writeCharacteristic(c)
        }
        if (writeResult)
          result.success(null)
        else
          result.error("Characteristic unavailable", null, null)
      }
      "requestMtu" -> {
        val deviceId = call.argument<String>("deviceId")!!
        val expectedMtu = call.argument<Int>("expectedMtu")!!
        val gatt = knownGatts.find { it.device.address == deviceId }
                ?: return result.error("IllegalArgument", "Unknown deviceId: $deviceId", null)
        gatt.requestMtu(expectedMtu)
        result.success(null)
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  private fun stopConnection(gatt: BluetoothGatt) {
    try {
      gatt.disconnect()
    } catch (e: Exception) {
      Log.v(TAG, "Error during BLE cleanConnection:", e)
    } finally {
      gatt.close()
      Log.v(TAG, "BLE Gatt free up resources success")
    }
  }

  @SuppressLint("MissingPermission")
  private fun getConnectedDevices(bluetoothManager: BluetoothManager): List<BluetoothDevice> {
    var btDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
    Log.v(TAG, "Currently BLE connected devices: $btDevices")
    return btDevices
  }

  enum class AvailabilityState(val value: Int) {
    unknown(0),
    resetting(1),
    unsupported(2),
    unauthorized(3),
    poweredOff(4),
    poweredOn(5),
  }

  fun BluetoothManager.getAvailabilityState(): AvailabilityState {
    val state = adapter?.state ?: return AvailabilityState.unsupported
    return when(state) {
      BluetoothAdapter.STATE_OFF -> AvailabilityState.poweredOff
      BluetoothAdapter.STATE_ON -> AvailabilityState.poweredOn
      BluetoothAdapter.STATE_TURNING_ON -> AvailabilityState.resetting
      BluetoothAdapter.STATE_TURNING_OFF -> AvailabilityState.resetting
      else -> AvailabilityState.unknown
    }
  }

  private val broadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
          availabilityChangeSink?.success(bluetoothManager.getAvailabilityState().value)
        }
    }
  }

  private val scanCallback = object : ScanCallback() {
    override fun onScanFailed(errorCode: Int) {
      Log.v(TAG, "onScanFailed: $errorCode")
    }

    override fun onScanResult(callbackType: Int, result: ScanResult) {
//      Log.v(TAG, "onScanResult: $callbackType + $result")
        scanResultSink?.success(mapOf<String, Any>(
          "name" to (result.device.name ?: ""),
          "deviceId" to result.device.address,
          "manufacturerDataHead" to (result.manufacturerDataHead ?: byteArrayOf()),
          "rssi" to result.rssi
        ))
      }

    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
      Log.v(TAG, "onBatchScanResults: $results")
    }
  }

  private var availabilityChangeSink: EventChannel.EventSink? = null
  private var scanResultSink: EventChannel.EventSink? = null

  override fun onListen(args: Any?, eventSink: EventChannel.EventSink?) {
    val map = args as? Map<String, Any> ?: return
    when (map["name"]) {
      "availabilityChange" -> {
        availabilityChangeSink = eventSink
        availabilityChangeSink?.success(bluetoothManager.getAvailabilityState().value)
      }
      "scanResult" -> scanResultSink = eventSink
    }
  }

  override fun onCancel(args: Any?) {
    val map = args as? Map<String, Any> ?: return
    when (map["name"]) {
      "availabilityChange" -> availabilityChangeSink = null
      "scanResult" -> scanResultSink = null
    }
  }

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      Log.v(TAG, "onConnectionStateChange: device(${gatt.device.address}) status($status), newState($newState)")
      if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
        sendMessage(messageConnector, mapOf(
          "deviceId" to gatt.device.address,
          "ConnectionState" to "connected"
        ))
      } else {
        knownGatts.remove(gatt)
        sendMessage(messageConnector, mapOf(
          "deviceId" to gatt.device.address,
          "ConnectionState" to "disconnected"
        ))
      }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      Log.v(TAG, "onServicesDiscovered ${gatt.device.address} $status")
      if (status != BluetoothGatt.GATT_SUCCESS) return

      gatt.services?.forEach { service ->
        Log.v(TAG, "Service " + service.uuid)
        service.characteristics.forEach { characteristic ->
          Log.v(TAG, "    Characteristic ${characteristic.uuid}")
          characteristic.descriptors.forEach {
            Log.v(TAG, "        Descriptor ${it.uuid}")
          }
        }

        sendMessage(messageConnector, mapOf(
          "deviceId" to gatt.device.address,
          "ServiceState" to "discovered",
          "service" to service.uuid.toString(),
          "characteristics" to service.characteristics.map { it.uuid.toString() }
        ))
      }
    }

    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        sendMessage(messageConnector, mapOf(
          "mtuConfig" to mtu
        ))
      }
    }

    override fun onCharacteristicRead(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
      status: Int,
    ) {
      Log.v(TAG, "onCharacteristicRead (new) ${characteristic.uuid}, ${value.contentToString()}")
      sendMessage(messageConnector, mapOf(
        "deviceId" to gatt.device.address,
        "characteristicValue" to mapOf(
          "characteristic" to characteristic.uuid.toString(),
          "value" to value
        )
      ))
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic, status: Int) {
      Log.v(TAG, "onCharacteristicWrite ${characteristic.uuid}, ${characteristic.value.contentToString()} $status")
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt?,
      characteristic: BluetoothGattCharacteristic?,
    ) {
      Log.v(TAG, "onCharacteristicChanged (old) ${characteristic?.uuid}, ${characteristic?.value?.contentToString()}")
      val deviceAddress = gatt?.device?.address
      if (deviceAddress!=null && characteristic!=null) {
        sendMessage(
          messageConnector, mapOf(
            "deviceId" to deviceAddress,
            "characteristicValue" to mapOf(
              "characteristic" to characteristic.uuid.toString(),
              "value" to characteristic.value
            )
          )
        )
      }
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
    ) {
//      Log.v(TAG, "onCharacteristicChanged ${characteristic.uuid}, ${characteristic.value.contentToString()}")
      sendMessage(messageConnector, mapOf(
        "deviceId" to gatt.device.address,
        "characteristicValue" to mapOf(
          "characteristic" to characteristic.uuid.toString(),
          "value" to value
        )
      ))
    }
  }
}

val ScanResult.manufacturerDataHead: ByteArray?
  get() {
    val sparseArray = scanRecord?.manufacturerSpecificData ?: return null
    if (sparseArray.size() == 0) return null

    return sparseArray.keyAt(0).toShort().toByteArray() + sparseArray.valueAt(0)
  }

fun Short.toByteArray(byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray =
        ByteBuffer.allocate(2 /*Short.SIZE_BYTES*/).order(byteOrder).putShort(this).array()

fun BluetoothGatt.getCharacteristic(service: String, characteristic: String): BluetoothGattCharacteristic? =
        getService(UUID.fromString(service)).getCharacteristic(UUID.fromString(characteristic))

private val DESC__CLIENT_CHAR_CONFIGURATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
fun BluetoothGatt.setNotifiable(gattCharacteristic: BluetoothGattCharacteristic, bleInputProperty: String) {
  val descriptor = gattCharacteristic.getDescriptor(DESC__CLIENT_CHAR_CONFIGURATION)
  val (value, enable) = when (bleInputProperty) {
    "notification" -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE to true
    "indication" -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE to true
    else -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE to false
  }
  // Enable or disable notifications/indications for the characteristic
  val isNotificationSet = setCharacteristicNotification(descriptor.characteristic, enable)
  Log.v(TAG, "setNotifiable, setCharacteristicNotification success: $isNotificationSet")
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // If notifications were successfully set, write the descriptor back to the server
        writeDescriptor(descriptor, value)
  } else {
    Log.v(TAG, "setNotifiable, for old android version: ${Build.VERSION.SDK_INT}")
    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    var isWriteDescriptorSuccess = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      isWriteDescriptorSuccess = writeDescriptor(descriptor);
    } else {
      isWriteDescriptorSuccess = internalWriteDescriptorWorkaround(descriptor);
    }
    Log.v(TAG, "writeDescriptor, isWriteDescriptorSuccess: $isWriteDescriptorSuccess")
  }
}

/**
 * There was a bug in Android up to 6.0 where the descriptor was written using parent
 * characteristic's write type, instead of always Write With Response, as the spec says.
 *
 *
 * See: [
 * https://android.googlesource.com/platform/frameworks/base/+/942aebc95924ab1e7ea1e92aaf4e7fc45f695a6c%5E%21/#F0](https://android.googlesource.com/platform/frameworks/base/+/942aebc95924ab1e7ea1e92aaf4e7fc45f695a6c%5E%21/#F0)
 *
 * @param descriptor the descriptor to be written
 * @return the result of [BluetoothGatt.writeDescriptor]
 */
@SuppressLint("MissingPermission")
fun BluetoothGatt.internalWriteDescriptorWorkaround(descriptor: BluetoothGattDescriptor?): Boolean {
  if (descriptor == null) return false
  val parentCharacteristic = descriptor.characteristic
  val originalWriteType = parentCharacteristic.writeType
  parentCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
  val result = writeDescriptor(descriptor)
  parentCharacteristic.writeType = originalWriteType
  return result
}