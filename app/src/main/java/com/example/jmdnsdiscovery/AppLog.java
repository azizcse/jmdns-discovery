package com.example.jmdnsdiscovery;

/*
 *  ****************************************************************************
 *  * Created by : Md. Azizul Islam on 1/14/2019 at 5:09 PM.
 *  * Email : azizul@w3engineers.com
 *  *
 *  * Purpose:
 *  *
 *  * Last edited by : Md. Azizul Islam on 1/14/2019.
 *  *
 *  * Last Reviewed by : <Reviewer Name> on <mm/dd/yy>
 *  ****************************************************************************
 */

import android.util.Log;

public class AppLog {
    private static String TAG = "AppLog";

    public static void e(String msg){
        e(TAG, msg);
    }

    public static void e(String tag, String msg){
        Log.e(tag, msg);
    }

    public static void d(String msg){
        d(TAG, msg);
    }

    public static void d(String tag, String msg){
        Log.d(tag, msg);
    }

    public static void v(String msg){
        v(TAG, msg);
    }

    public static void v(String tag, String msg){
        Log.v(tag, msg);
    }
}
