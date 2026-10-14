package com.sinux.pocketboard.utils;

import android.content.Context;
import android.content.res.Resources;
import android.widget.Toast;

import com.sinux.pocketboard.R;

public class ToastMessageUtils {

    private static Toast toastMessage;

    public static void showMessage(Context context, int resId) {
        // NOTE: a plain Toast.makeText(...) (shown below) does not need
        // POST_NOTIFICATIONS -- that permission gates NotificationManager's
        // persistent tray notifications, an unrelated API. A previous
        // check here required it anyway, which silently dropped every
        // message this method is used for (overlay-permission-required,
        // app-not-found, mapping load/save failures, voice IME not
        // configured...) on API 33+ whenever the user hadn't granted
        // notifications -- unrelated to whether they'd granted it. The
        // one caller that IS meant to be optional (the layout-change
        // indicator) already guards itself with
        // preferencesHolder.isLayoutChangeIndicationEnabled() before
        // calling this method, so no equivalent check belongs here.

        try {
            showMessage(context, context.getString(resId));
        } catch (Resources.NotFoundException e) {
            showMessage(context, context.getString(R.string.pocketboard_disabled));
        }
    }

    private static void showMessage(Context context, String message) {
        if (toastMessage != null) {
            toastMessage.cancel();
        }
        toastMessage = Toast.makeText(context, message, Toast.LENGTH_SHORT);
        toastMessage.show();
    }
}
