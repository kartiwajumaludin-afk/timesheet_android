package com.muktimandiri.timesheet;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TsDevicePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
