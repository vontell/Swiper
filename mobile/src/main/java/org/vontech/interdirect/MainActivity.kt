package org.vontech.interdirect

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.google.android.gms.wearable.*
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity(), DataClient.OnDataChangedListener{

    private val IP_KEY = "org.vontech.interdirect.key.ip"
    private val TIMER_KEY = "org.vontech.interdirect.key.timer"
    private val STATUS_KEY = "org.vontech.interdirect.key.status"
    private var dataClient: DataClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dataClient = Wearable.getDataClient(this)

        ipButton.setOnClickListener {

            val ip = ipEdit.editableText.toString()
            val putDataMapReq = PutDataMapRequest.create("/ip")
            putDataMapReq.getDataMap().putString(IP_KEY, ip)
            val putDataReq = putDataMapReq.asPutDataRequest()
            val putDataTask = dataClient!!.putDataItem(putDataReq)

            Log.e("SENDING", "About to send new IP: " + ip)
            putDataTask.addOnCompleteListener {
                Log.e("SENT", "Sent new IP: " + ip)
            }

        }

        timeButton.setOnClickListener {



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
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                // DataItem changed
                val item = event.dataItem
                if (item.uri.path.compareTo("/ip") == 0) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    Log.e("RECEIVED", dataMap.getString(IP_KEY))
                }
            } else if (event.type == DataEvent.TYPE_DELETED) {
                // DataItem deleted
            }
        }
    }
}
