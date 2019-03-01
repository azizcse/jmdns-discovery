package com.example.jmdnsdiscovery;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

public class MainActivity extends AppCompatActivity {
    private JmDNS _jmdns;
    private WifiManager.MulticastLock _multicastLock;
    private String TAG = "BNGRESOLVE";

    private String myDeviceId;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        _multicastLock = ((WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE)).createMulticastLock("MultiCastLock");
        _multicastLock.setReferenceCounted(true);
        _multicastLock.acquire();

        // Setup JmDNS

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    _jmdns = JmDNS.create();

                } catch (Exception e) {
                    e.printStackTrace();
                }
                create();
                listen();
            }
        }).start();

    }

    private ServiceListener _serviceListener;

    public void listen() {

        // Set the Service Listeners
        _serviceListener = new ServiceListener() {

            public void serviceResolved(ServiceEvent event) {
                final int port = event.getInfo().getPort();
                Log.e(TAG, "serviceResolved() Event ="+event.getName()+" address ="+event.getInfo().getHostAddress()+" port="+port);
            }

            public void serviceRemoved(ServiceEvent event) {
                // Handle the event
                Log.e(TAG, "serviceRemoved() Event ="+event.getName()+" Type ="+event.getInfo().toString());
            }

            public void serviceAdded(ServiceEvent event) {
                /*// Any thread.
                if (event.getName().equals(myDeviceId))
                    return;
*/
                //Logger.debug("jmd serviceAdded '{}' '{}'", event.getName(), event.getType());
                //jmdns.requestServiceInfo(event.getType(), event.getName());
                // Resolve the added service
                _jmdns.requestServiceInfo(event.getType(), event.getName(), 500);
                Log.e(TAG, "serviceAdded() Event ="+event.getName()+" Type ="+event.getInfo().getHostAddress());
            }
        };

        _jmdns.addServiceListener("_testService._tcp.local.", _serviceListener);
    }

    public void create() {

        try {

            ServiceInfo info = ServiceInfo.create("_testService._tcp.local.", toUuid(), 5555, "Extra Text");
            _jmdns.registerService(info);

        } catch (Exception e) {
            // TODO: Handle exception
        }

    }

    public String toUuid() {

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Log.e(TAG, "Device id ="+deviceId);
        myDeviceId = deviceId;
        return deviceId;
    }
}
