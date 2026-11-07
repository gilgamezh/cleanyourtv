package io.gilgamezh.remotekeys;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only exists so InputManager can find the keyboard-layout metadata; never does anything. */
public class Layouts extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) { }
}
