package org.vontech.interdirect

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.support.wearable.activity.WearableActivity
import android.util.Log
import android.widget.TextView
import com.google.android.gms.wearable.DataClient
import kotlinx.coroutines.experimental.async
import java.util.*
import java.io.PrintWriter
import java.net.Socket
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataEventBuffer
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : WearableActivity(), DataClient.OnDataChangedListener {

    private val IP_KEY = "org.vontech.interdirect.key.ip"
    private val TIMER_KEY = "org.vontech.interdirect.key.timer"
    private val STATUS_KEY = "org.vontech.interdirect.key.status"
    private var dataClient: DataClient? = null

    private var sensorManager: SensorManager? = null
    private var sensorList: HashMap<String, Sensor> = HashMap()
    private var otherSocket: Socket? = null
    private var out: PrintWriter? = null

    private var PREFS_KEY = "org.vontech.swiper.prefs"
    private var prefs: SharedPreferences? = null
    private var timer: Timer? = null

    private val IP = "18.111.93.183"

    val numberMap = hashMapOf(
            "one" to 1,
            "two" to 2,
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Enables Always-on
        setAmbientEnabled()

        // Get relevant sensor information
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val linearAcceleration = sensorManager!!.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val magneticField = sensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val motionSignificant = sensorManager!!.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        val motionDetect = sensorManager!!.getDefaultSensor(Sensor.TYPE_MOTION_DETECT)

        prefs = this.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)

        sensorList = hashMapOf(
                "0" to accelerometer,
                "1" to gyroscope,
                "2" to linearAcceleration,
                "magneticField" to magneticField,
                "motionSignificant" to motionSignificant,
                "motionDetect" to motionDetect
        )

        fun getEncoding(type: Int, value: FloatArray) : String {
            val enc = when (type) {
                Sensor.TYPE_ACCELEROMETER -> "0,"
                Sensor.TYPE_GYROSCOPE -> "1,"
                Sensor.TYPE_LINEAR_ACCELERATION -> "2,"
                Sensor.TYPE_MAGNETIC_FIELD -> "3,"
                else -> "-1,"
            }
            val stringVals = value.joinToString(",")
            return enc + stringVals
        }

        val listener = object : SensorEventListener {

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.e("Acc change", accuracy.toString())
            }

            override fun onSensorChanged(sensorEvent: SensorEvent) {

                if (out != null) {
                    //Log.i("Sensor update " + sensorEvent.sensor.stringType + ":", Arrays.toString(sensorEvent.values))
                    val toSend = getEncoding(sensorEvent.sensor.type, sensorEvent.values)
                    async {
                        synchronized(out!!) {
                            out!!.println(toSend)
                            out!!.flush()
                        }
                    }
                }
            }

        }

        for (sensor in sensorList.values) {
            Log.e("reg", "register listener")
            sensorManager!!.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        val reconnectButton = findViewById<Button>(R.id.reconnectButton)
        reconnectButton.setOnClickListener {
            val editIp = findViewById<EditText>(R.id.ipEdit)
            val newIp = editIp.editableText.toString()
            with (prefs!!.edit()) {
                putString("IP_SAVED", newIp)
                commit()
            }
            connect(newIp)
        }

        val voiceButton = findViewById<Button>(R.id.voiceButton)
        voiceButton.setOnClickListener {
            displaySpeechRecognizer()
        }

        val editIp = findViewById<EditText>(R.id.ipEdit)
        editIp.setText(prefs!!.getString("IP_SAVED", ""))

        val startTimerButton = findViewById<Button>(R.id.startTimerButton)
        val timerEdit = findViewById<EditText>(R.id.timerEdit)
        timerEdit.setText(prefs!!.getString("TIMER_SAVED", "7"))
        startTimerButton.setOnClickListener {
            val time = timerEdit.editableText.toString().toLong()
            with (prefs!!.edit()) {
                putString("TIMER_SAVED", time.toString())
                commit()
            }
            timer = Timer()
            timer!!.schedule(object : TimerTask() {
                override fun run() {
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    val vibrationPattern = longArrayOf(0, 500, 50, 50)
                    val indexInPatternToRepeat = -1
                    vibrator.vibrate(vibrationPattern, indexInPatternToRepeat)
                }
            }, time * 1000 * 60)
        }

        resetTimerButton.setOnClickListener {
            if (timer != null) {
                timer!!.cancel()
            }
        }

    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.e("SOMETHING", dataEvents.toString())
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                // DataItem changed
                val item = event.dataItem
                if (item.uri.path.compareTo("/ip") == 0) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    connect(dataMap.getString(IP_KEY))
                }
            } else if (event.type == DataEvent.TYPE_DELETED) {
                // DataItem deleted
            }
        }
    }

    private fun connect(ip : String) {

        async {

            Log.e("SOCKET", "Creating socket")
            otherSocket = Socket(ip, 8080)
            out = PrintWriter(otherSocket!!.getOutputStream(), true)
            Log.e("SOCKET", "Create output stream: " + otherSocket!!.isConnected)

            // Vibrate when connected
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val vibrationPattern = longArrayOf(0, 300, 50, 50)
            val indexInPatternToRepeat = -1
            vibrator.vibrate(vibrationPattern, indexInPatternToRepeat)

        }

    }

    private val SPEECH_REQUEST_CODE = 0

    private fun displaySpeechRecognizer() {
        var intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        startActivityForResult(intent, SPEECH_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK) {
            var results = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS)
            var spokenText = results.get(0)

            spokenText = spokenText.toLowerCase()

            if (spokenText.startsWith("ip")) {
                val tempText = "" + spokenText
                tempText.replace("ip address", "")
                tempText.replace(" ", "")
                Log.e("NEW IP", tempText)
                connect(tempText)
            }

            val forwardRegex = "forward (\\w) slide".toRegex()
            val backRegex = "back (\\w) slide".toRegex()
            val forwardMatch = forwardRegex.find(spokenText)
            val backMatch = backRegex.find(spokenText)

            if (forwardMatch != null) {
                var (forAmount) = forwardMatch!!.destructured
                Log.e("FORWARD", forAmount)

                if (forAmount in numberMap) {
                    forAmount = numberMap.get(forAmount).toString()
                }

                async {
                    synchronized(out!!) {
                        out!!.println("5," + forAmount)
                        out!!.flush()
                    }
                }
            } else if (backMatch != null) {
                var (backAmount) = backMatch!!.destructured

                if (backAmount in numberMap) {
                    backAmount = numberMap.get(backAmount).toString()
                }

                Log.e("BACK", backAmount)
                async {
                    synchronized(out!!) {
                        out!!.println("6," + backAmount)
                        out!!.flush()
                    }
                }

            }

            if (spokenText.contains("restart") || spokenText.contains("first")) {
                Log.e("RESTART", "Yup")
                async {
                    synchronized(out!!) {
                        out!!.println("7,0")
                        out!!.flush()
                    }
                }
            }

            if (spokenText.contains("question") || spokenText.contains("answer") || spokenText.contains("questions") || spokenText.contains("finish") || spokenText.contains("end")) {
                Log.e("RESTART", "Yup")
                async {
                    synchronized(out!!) {
                        out!!.println("8,0")
                        out!!.flush()
                    }
                }
            }

            // Do something with spokenText
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

//    override fun getGestureSubscpitionList(): ArrayList<GestureConstants.SubscriptionGesture> {
//        val gestures = ArrayList<GestureConstants.SubscriptionGesture>()
//        gestures.add(GestureConstants.SubscriptionGesture.FLICK)
//        gestures.add(GestureConstants.SubscriptionGesture.SNAP)
//        gestures.add(GestureConstants.SubscriptionGesture.TWIST)
//        return gestures
//    }

//    override fun sendsGestureToPhone(): Boolean {
//        return false
//    }
//
//    override fun onSnap() {
//        Toast.makeText(this, "Snap it up", Toast.LENGTH_LONG).show()
//        Log.e("GESTURE", "SNAP")
//    }
//
//    override fun onFlick() {
//        Toast.makeText(this, "Got a flick!", Toast.LENGTH_LONG).show()
//        Log.e("GESTURE", "FLICK")
//    }
//
//    override fun onTwist() {
//        Toast.makeText(this, "Just twist it", Toast.LENGTH_LONG).show()
//        Log.e("GESTURE", "TWIST")
//    }
//
//    override fun onGestureWindowClosed() {
//        Toast.makeText(this, "Gesture window closed.", Toast.LENGTH_LONG).show()
//    }
//
//    override fun onTiltX(p0: Float) {
//        throw IllegalStateException("This function should not be called unless subscribed to TILT_X.")
//    }
//
//    override fun onTilt(p0: Float, p1: Float, p2: Float) {
//        throw IllegalStateException("This function should not be called unless subscribed to TILT.")
//    }

}
