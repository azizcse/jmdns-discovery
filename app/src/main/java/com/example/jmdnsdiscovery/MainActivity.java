package com.example.jmdnsdiscovery;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.example.jmdnsdiscovery.dispatch.ConnectionListener;
import com.example.jmdnsdiscovery.protocol.Link;
import com.example.jmdnsdiscovery.protocol.MeshManager;
import com.example.jmdnsdiscovery.protocol.NsdServer;
import com.example.jmdnsdiscovery.protocol.WifiTransPort;

import java.util.ArrayList;
import java.util.List;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

public class MainActivity extends AppCompatActivity implements ConnectionListener {
    private JmDNS _jmdns;
    private WifiManager.MulticastLock _multicastLock;
    private String TAG = "BNGRESOLVE";

    private String myDeviceId;
    private List<Link> linkList;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        linkList = new ArrayList<>();
        setContentView(R.layout.activity_main);
        WifiTransPort transPort = MeshManager.configMesh(1053, toUuid(), this,getApplicationContext());
        transPort.start();
    }

    public void onClickButton(View view){
       for (Link item : linkList){
           item.sendFrame(new String("Hello ="+System.currentTimeMillis()).getBytes());
       }
    }

    public String toUuid() {

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Log.e(TAG, "Device id ="+deviceId);
        myDeviceId = deviceId;
        return deviceId;
    }

    @Override
    public void linkConnected(Link link) {
        linkList.add(link);
    }

    @Override
    public void linkDisconnected(Link link) {
        Toast.makeText(this,"Disconnect ="+link.getNodeId(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void linkDidReceiveFrame(Link link, byte[] frameData) {
        Toast.makeText(this,new String(frameData), Toast.LENGTH_SHORT).show();

    }
}
