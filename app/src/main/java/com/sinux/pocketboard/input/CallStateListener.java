package com.sinux.pocketboard.input;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import androidx.annotation.RequiresApi;

import com.sinux.pocketboard.PocketBoardIME;

public final class CallStateListener {

    private final CallListener listenerDelegate;

    private boolean registered;

    public CallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listenerDelegate = new Sdk35CallStateListener();
        } else {
            listenerDelegate = new LegacyCallStateListener();
        }
    }

    public void register(PocketBoardIME pocketBoardIME, TelephonyManager telephonyManager) {
        if (registered)
            return;

        // READ_PHONE_STATE is a dangerous permission requested lazily
        // from PreferencesFragment when the user turns phone control
        // on, so it is very often NOT granted yet the first time the
        // IME starts. telephonyManager.listen(..., LISTEN_CALL_STATE)
        // throws SecurityException without it on these API levels
        // (not just silently no-op), which used to crash the whole
        // keyboard on first use -- this guard is why the >= S branch
        // already checked first.
        if (pocketBoardIME.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(pocketBoardIME.getMainExecutor(), (Sdk35CallStateListener) listenerDelegate);
        } else {
            telephonyManager.listen((LegacyCallStateListener) listenerDelegate, PhoneStateListener.LISTEN_CALL_STATE);
        }

        registered = true;
    }

    public void unregister(TelephonyManager telephonyManager) {
        if (!registered)
            return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.unregisterTelephonyCallback((Sdk35CallStateListener) listenerDelegate);
            registered = false;
        } else {
            telephonyManager.listen((LegacyCallStateListener) listenerDelegate, PhoneStateListener.LISTEN_NONE);
            registered = false;
        }
    }

    public boolean isCalling() {
        return listenerDelegate.isCalling();
    }

    /**
     * Common interface
     */
    private interface CallListener {

        boolean isCalling();

    }

    /**
     * Legacy API < S (31) level implementation
     */
    private static final class LegacyCallStateListener extends PhoneStateListener implements CallListener {

        private boolean calling;

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            calling = state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK;
        }

        @Override
        public boolean isCalling() {
            return calling;
        }
    }

    /**
     * API >= S (31) level implementation
     */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private static final class Sdk35CallStateListener extends TelephonyCallback implements TelephonyCallback.CallStateListener, CallListener {

        private boolean calling;

        @Override
        public void onCallStateChanged(int state) {
            calling = state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK;
        }

        @Override
        public boolean isCalling() {
            return calling;
        }
    }
}
