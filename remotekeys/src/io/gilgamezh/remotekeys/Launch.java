package io.gilgamezh.remotekeys;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/** Trampoline: the Calendar key opens Plex, the Contacts key (alias .Ziggo) opens Ziggo GO. */
public class Launch extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String cls = getIntent().getComponent() != null ? getIntent().getComponent().getClassName() : "";
        String target = cls.endsWith(".Ziggo") ? "com.ziggo.tv" : "com.plexapp.android";
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(target);
        if (i == null) i = pm.getLaunchIntentForPackage(target);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } else {
            Log.w("RemoteKeys", "no launch intent for " + target);
        }
        finish();
    }
}
