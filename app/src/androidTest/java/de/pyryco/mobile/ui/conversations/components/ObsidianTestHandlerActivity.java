package de.pyryco.mobile.ui.conversations.components;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Uses only platform classes: the separately launched test APK has no Kotlin runtime. */
public class ObsidianTestHandlerActivity extends Activity {
    public static final String DELIVERY_ACTION = "de.pyryco.mobile.test.OBSIDIAN_DELIVERED";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String testPackage = getPackageName();
        String targetPackage = testPackage.substring(0, testPackage.length() - ".test".length());
        Intent received = getIntent();
        sendBroadcast(new Intent(DELIVERY_ACTION).setPackage(targetPackage)
                .putExtra("action", received.getAction()).putExtra("uri", received.getDataString()));
        finish();
    }
}
