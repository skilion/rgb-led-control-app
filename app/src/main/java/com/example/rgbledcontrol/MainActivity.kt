package com.example.rgbledcontrol

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import com.example.rgbledcontrol.databinding.ActivityMainBinding

import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.SeekBar
import java.util.*


private const val tag = "MainActivity"
private const val scanPeriodMs: Long = 10000
private const val REQUEST_ENABLE_BT = 1
private val rgbColorUuid = UUID.fromString("3d09ac99-1201-63ad-b8ab-ff18f6545762")

private val colors = listOf(
    doubleArrayOf(0.0, 0.0, 1.0),
    doubleArrayOf(0.0, 1.0, 0.0),
    doubleArrayOf(0.0, 1.0, 0.5),
    doubleArrayOf(1.0, 0.0, 0.0),
    doubleArrayOf(1.0, 0.0, 1.0),
    doubleArrayOf(0.5, 1.0, 0.0),
    doubleArrayOf(0.5, 1.0, 0.5),
)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var rgbColorCharacteristic: BluetoothGattCharacteristic? = null

    private val stopScanHandler = Handler()
    private val lightColorTimer = Timer()
    private val colorUpdateFreqHz = 50
    private val colorUpdateRateSec = 1.0 / colorUpdateFreqHz
    private var connected = false
    private var scanning = false
    private var currentColor = DoubleArray(3)
    private var targetColor = DoubleArray(3)
    private var prevColor = DoubleArray(3)
    private var smoothing = 0.0
    private var transitionStep = 0.0
    private var flashing = 0.0
    private var flashingStep = 0.0 // <0.5 = light off
    private var intensity = 1.0
    private var autoSpeed = 0.0
    private var autoStep = 0.0
    private var prevAutoIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener { this.onConnectButtonClick() }
        setLightColorButtonListeners()
        setSeekBarListeners()

        bluetoothInit()
        initColorTimer()
    }

    private fun setLightColorButtonListeners() {
        binding.lightOffButton.setOnClickListener { setTargetColor(doubleArrayOf(0.0, 0.0, 0.0)) }
        binding.whiteButton.setOnClickListener { setTargetColor(doubleArrayOf(.5, 1.0, .5)) }
        binding.redButton.setOnClickListener { setTargetColor(doubleArrayOf(1.0, 0.0, 0.0)) }
        binding.greenButton.setOnClickListener { setTargetColor(doubleArrayOf(0.0, 1.0, 0.0)) }
        binding.blueButton.setOnClickListener { setTargetColor(doubleArrayOf(0.0, 1.0, 1.0)) }
    }

    private fun setSeekBarListeners() {
        binding.autoSpeedSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                autoSpeed = progress / 100.0
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.smoothingSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                smoothing = progress / 100.0
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.flashSpeedSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                flashing = progress / 100.0
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.intensitySeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                intensity = progress / 100.0
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun initColorTimer() {
        lightColorTimer.scheduleAtFixedRate(object: TimerTask() {
            override fun run() {
                lightUpdateLoop()

            }
        }, 0, (1000 / colorUpdateFreqHz).toLong())
    }

    private fun lightUpdateLoop() {
        if (!connected) return
        rgbColorInterpolation()
        autoColorChange()
        lightFlash()
    }

    private fun rgbColorInterpolation() {
        for (i in 0..2) {
            val a = prevColor[i]
            val b = targetColor[i]
            currentColor[i] = a * (1 - transitionStep) + (b * transitionStep)
            if (smoothing == 0.0) {
                transitionStep = 1.0
            } else {
                transitionStep =
                    minOf(1.0, transitionStep + ((colorUpdateRateSec / (smoothing * 10.0))))
            }
        }
    }

    private fun autoColorChange() {
        if (autoSpeed > 0.0) {
            autoStep += autoSpeed * colorUpdateRateSec * 5.0
            if (autoStep > 1.0) {
                var newColorIndex: Int
                do {
                    newColorIndex = (Math.random() * colors.size).toInt()
                } while (newColorIndex == prevAutoIndex)
                autoStep = 0.0
                prevAutoIndex = newColorIndex
                setTargetColor(colors[newColorIndex])
            }
        }
    }

    private fun lightFlash() {
        if (flashing == 0.0) {
            flashingStep = 1.0
        } else {
            flashingStep += flashing * 0.2
            if (flashingStep > 1.0) {
                flashingStep = 0.0
            }
        }
        if (flashingStep >= 0.5) {
            setLedColor(
                (currentColor[0] * intensity * 255).toUInt().toUByte(),
                (currentColor[1] * intensity * 255).toUInt().toUByte(),
                (currentColor[2] * intensity * 255).toUInt().toUByte()
            )
        } else {
            setLedColor(0u, 0u, 0u)
        }
    }

    private fun onConnectButtonClick() {
        if (!connected) {
            if (!scanning) {
                val scanSettings =
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                val scanFilters = listOf(ScanFilter.Builder().setDeviceName("RgbLedTest").build())
                if (bluetoothAdapter?.isEnabled != true) {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                } else {
                    bluetoothAdapter?.bluetoothLeScanner?.startScan(
                        scanFilters,
                        scanSettings,
                        bluetoothScanCallback
                    )
                    stopScanHandler.postDelayed({
                        if (!connected) {
                            binding.connectButton.text = "Connect"
                            bluetoothScanStop()
                        }
                    }, scanPeriodMs)
                    scanning = true
                    binding.connectButton.text = "Scanning"
                }
            } else {
                binding.connectButton.text = "Connect"
                bluetoothScanStop()
            }
        }
    }

    private fun setLedColor(r: UByte, g: UByte, b: UByte) {
        if ((bluetoothGatt != null) and (rgbColorCharacteristic != null)) {
            rgbColorCharacteristic!!.value = byteArrayOf(r.toByte(), g.toByte(), b.toByte(), 0)
            bluetoothGatt!!.writeCharacteristic(rgbColorCharacteristic)
        }
    }

    private fun setTargetColor(newColor: DoubleArray) {
        prevColor = targetColor
        targetColor = newColor
        transitionStep = 0.0
    }

    private fun bluetoothInit() {
        binding.connectButton.isEnabled = false
        if (bluetoothManager == null) {
            bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            if (bluetoothManager == null) {
                Log.e(this.localClassName, "Unable to initialize BluetoothManager")
                return
            }
        }

        bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter == null) {
            Log.e(this.localClassName, "Unable to obtain a BluetoothAdapter")
            return
        }

        binding.connectButton.isEnabled = true
    }

    private fun bluetoothScanStop() {
        scanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(bluetoothScanCallback)
    }

    private val bluetoothScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.i(tag, result.toString())
            bluetoothScanStop()
            bluetoothConnect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.i(tag, "Scan failed $errorCode")
        }
    }

    private fun bluetoothConnect(device: BluetoothDevice) {
        connected = true
        binding.connectButton.text = "Connected"
        bluetoothGatt = device.connectGatt(this, false, bluetoothGattCallback)
    }

    private val bluetoothGattCallback: BluetoothGattCallback = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(tag, "Connected")
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(tag, "Disconnected")
                connected = false
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(tag, "BL Services discovered")
                for (svc in gatt!!.services) {
                    Log.i(tag, svc.uuid.toString())
                    for (chr in svc.characteristics) {
                        Log.i(tag, "chr ${chr.uuid} ${chr.permissions} ${chr.properties}")
                        if (chr.uuid == rgbColorUuid) {
                            chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            rgbColorCharacteristic = chr
                            Log.i(tag, "rgbColorCharacteristic found")
                        }
                    }
                }
            } else {
                Log.w(tag, "onServicesDiscovered received: $status")
            }
        }
    }
}