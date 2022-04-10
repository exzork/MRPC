package com.kdt.mrpc;

import android.app.*;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.SSLParameters;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

import static com.kdt.mrpc.MainActivity.appendlnToLog;

public class BackgroundService extends Service {
    private Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private NotificationManager notificationManager;
    private int NOTIFICATION = R.string.app_name;

    private SharedPreferences pref;
    private Runnable heartbeatRunnable;
    public static WebSocketClient webSocketClient;
    private Thread heartbeatThr, wsThr, statusUpdateThr;
    private int heartbeat_interval, seq;
    private String authToken;
    private ArrayList<String> whitelist;

    public class LocalBinder extends Binder {
        public BackgroundService getService() {
            return BackgroundService.this;
        }
    }

    @Override
    public void onCreate() {
        notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(new NotificationChannel("notification", "notification", NotificationManager.IMPORTANCE_HIGH));
        }

        heartbeatRunnable = new Runnable(){
            @Override
            public void run() {
                try {
                    if (heartbeat_interval < 10000) throw new RuntimeException("invalid");
                    Thread.sleep(heartbeat_interval);
                    webSocketClient.send(/*encodeString*/("{\"op\":1, \"d\":" + (seq==0?"null":Integer.toString(seq)) + "}"));
                } catch (InterruptedException e) {}
            }
        };

        pref = getSharedPreferences("pref", MODE_PRIVATE);
        authToken = pref.getString("authToken", null);
        whitelist = new ArrayList<String>(pref.getStringSet("selected_app", null));
        Log.d("whitelist", whitelist.toString());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Display a notification about us starting.  We put an icon in the status bar.
        showNotification();

        Log.i("LocalService", "Received start id " + startId + ": " + intent);

        if(!extractToken()){
            Log.e("BackgroundService", "Failed to extract token");
        }else{
            wsThr = new Thread(new Runnable() {
                @Override
                public void run() {
                    createWebSocketClient();
                }
            });
            wsThr.start();


            final String[] last_app_name = {""};

            final Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (webSocketClient.isOpen()) {
                        String opened_app_name = getOpenedAppName();
                        if(whitelist.contains(opened_app_name)) {
                            if (!last_app_name[0].equals(opened_app_name)) {
                                String assetId = getAssetIdFromPackage(opened_app_name);
                                Log.d("BackgroundService", "Opened app: " + opened_app_name + " assetId: " + assetId);

                                last_app_name[0] = opened_app_name;
                                String app_name = getAppNameFromPackage(opened_app_name, getApplicationContext());
                                if (app_name != null) {
                                    setActivity(app_name, assetId);
                                } else {
                                    setActivity(opened_app_name, assetId);
                                }
                            }
                        }else{
                            setActivity("", "");
                        }
                    }
                    handler.postDelayed(this, 1000);
                }
            },1000);

        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // Cancel the persistent notification.
        notificationManager.cancel(NOTIFICATION);
        setActivity("", "");

        // Tell the user we stopped.
        Toast.makeText(this, R.string.app_name, Toast.LENGTH_SHORT).show();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // This is the object that receives interactions from clients.  See
    // RemoteService for a more complete example.
    private final IBinder mBinder = new LocalBinder();

    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = getText(R.string.app_name);

        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        // Set the info for the views that show in the notification panel.
        Notification notification = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, "notification")
                    .setTicker(text)  // the status text
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setWhen(System.currentTimeMillis())  // the time stamp
                    .setContentTitle(getText(R.string.app_name))  // the label of the entry
                    .setContentText(text)  // the contents of the entry
                    .setContentIntent(contentIntent)  // The intent to send when the entry is clicked
                    .build();
        }

        // Send the notification.
        notificationManager.notify(NOTIFICATION, notification);
    }

    private void setActivity(String name, String assetId){
        long current = System.currentTimeMillis();

        ArrayMap<String, Object> presence = new ArrayMap<>();
        ArrayMap<String, Object> activity = new ArrayMap<>();

        if(!name.equals("")){
            activity.put("name", name);
            activity.put("application_id", "962579538418749531");
            activity.put("type", 0);
            ArrayMap<String, Object> timestamps = new ArrayMap<>();
            timestamps.put("start", current);

            activity.put("timestamps", timestamps);


            ArrayMap<String, Object> assets = new ArrayMap<>();
            assets.put("large_image", assetId);
            activity.put("assets", assets);

            presence.put("activities", new Object[]{activity});
        }else{
            presence.put("activities", new Object[]{});
        }
        presence.put("afk", false);
        presence.put("since", current);
        presence.put("status", null);

        ArrayMap<String, Object> arr = new ArrayMap<>();
        arr.put("op", 3);
        arr.put("d", presence);
        Log.d("DATA", arr.toString());
        webSocketClient.send(gson.toJson(arr));
    }

    private void createWebSocketClient() {
        URI uri;
        try {
            uri = new URI("wss://gateway.discord.gg/?encoding=json&v=9"); // &compress=zlib-stream");
        }
        catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }

        ArrayMap<String, String> headerMap = new ArrayMap<>();
        //headerMap.put("Accept-Encoding", "gzip");
        //headerMap.put("Content-Type", "gzip");

        webSocketClient = new WebSocketClient(uri, headerMap) {
            @Override
            public void onOpen(ServerHandshake s) {
            }

            @Override
            public void onMessage(ByteBuffer message) {
                // onMessage(decodeString(message.array()));
            }

            @Override
            public void onMessage(String message) {
                // appendlnToLog("onTextReceived: " + message);

                ArrayMap<String, Object> map = gson.fromJson(
                        message, new TypeToken<ArrayMap<String, Object>>() {}.getType()
                );

                // obtain sequence number
                Object o = map.get("s");
                if (o != null) {
                    seq = ((Double)o).intValue();
                }

                int opcode = ((Double)map.get("op")).intValue();
                switch (opcode) {
                    case 0: // Dispatch event
                        if (((String)map.get("t")).equals("READY")) {
                            Map data = (Map) ((Map)map.get("d")).get("user");
                            appendlnToLog("Connected to " + data.get("username") + "#" + data.get("discriminator"));
                            return;
                        }
                        break;
                    case 10: // Hello
                        Map data = (Map) map.get("d");
                        heartbeat_interval = ((Double)data.get("heartbeat_interval")).intValue();
                        heartbeatThr = new Thread(heartbeatRunnable);
                        heartbeatThr.start();
                        sendIdentify();
                        break;
                    case 1: // Heartbeat request
                        if (!heartbeatThr.interrupted()) {
                            heartbeatThr.interrupt();
                        }
                        webSocketClient.send(/*encodeString*/("{\"op\":1, \"d\":" + (seq==0?"null":Integer.toString(seq)) + "}"));

                        break;
                    case 11: // Heartbeat ACK
                        if (!heartbeatThr.interrupted()) {
                            heartbeatThr.interrupt();
                        }
                        heartbeatThr = new Thread(heartbeatRunnable);
                        heartbeatThr.start();
                        break;
                }
                //appendlnToLog("Received op " + opcode + ": " + message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (!heartbeatThr.interrupted()) {
                    heartbeatThr.interrupt();
                }
                throw new RuntimeException("Interrupt");
            }

            @Override
            public void onError(Exception e) {
            }

            @Override
            protected void onSetSSLParameters(SSLParameters p) {
                try {
                    super.onSetSSLParameters(p);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
        };
        webSocketClient.connect();
    }

    private void sendIdentify() {
        ArrayMap<String, Object> prop = new ArrayMap<>();
        prop.put("$os", "linux");
        prop.put("$browser", "Discord Android");
        prop.put("$device", "unknown");

        ArrayMap<String, Object> data = new ArrayMap<>();
        data.put("token", authToken);
        data.put("properties", prop);
        data.put("compress", false);
        data.put("intents", 0);

        ArrayMap<String, Object> arr = new ArrayMap<>();
        arr.put("op", 2);
        arr.put("d", data);

        webSocketClient.send(gson.toJson(arr));
    }

    public boolean extractToken() {
        // ~~extract token in an ugly way :troll:~~
        try {
            File f = new File(getFilesDir().getParentFile(), "app_webview/Default/Local Storage/leveldb");
            File[] fArr = f.listFiles(new FilenameFilter(){
                @Override
                public boolean accept(File file, String name) {
                    return name.endsWith(".log");
                }
            });
            if (fArr.length == 0) {
                return false;
            }
            f = fArr[0];
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("token")) {
                    break;
                }
            }
            line = line.substring(line.indexOf("token") + 5);
            line = line.substring(line.indexOf("\"") + 1);
            authToken = line.substring(0, line.indexOf("\""));
            return true;
        } catch (Throwable e) {
            Log.e("Discord", "Failed to extract token", e);
            return false;
        }
    }

    private String getOpenedAppName(){
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long time = System.currentTimeMillis();
            List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 1000, time);
            if (appList != null && appList.size() > 0) {
                SortedMap<Long, UsageStats> mySortedMap = new TreeMap<Long, UsageStats>();
                for (UsageStats usageStats : appList) {
                    mySortedMap.put(usageStats.getLastTimeUsed(),
                            usageStats);
                }
                if (mySortedMap != null && !mySortedMap.isEmpty()) {
                    return mySortedMap.get(mySortedMap.lastKey()).getPackageName();
                }
            }
        } else {
            ActivityManager am = (ActivityManager) getBaseContext().getSystemService(ACTIVITY_SERVICE);
            return am.getRunningTasks(1).get(0).topActivity .getPackageName();
        }
        return "";
    }

    private String getAppNameFromPackage(String packageName, Context context) {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> pkgAppsList = context.getPackageManager()
                .queryIntentActivities(mainIntent, 0);

        for (ResolveInfo app : pkgAppsList) {
            if (app.activityInfo.packageName.equals(packageName)) {
                return app.activityInfo.loadLabel(context.getPackageManager()).toString();
            }
        }
        return null;
    }

    private String getAssetIdFromPackage(String packageName){
        final String[] assetId = {"962595958397481041"};
        final String packageNameTL = packageName.replace(".", "_");
        Thread checkAssetsId = new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    URL url = new URL("https://discord.com/api/v9/oauth2/applications/962579538418749531/assets");
                    InputStream in = url.openStream();
                    InputStreamReader reader = new InputStreamReader(in);
                    List assets = gson.fromJson(reader, List.class);
                    Log.d("Discord", assets.toString());
                    for (Object asset : assets) {
                        if (asset instanceof Map) {
                            Map<String, Object> assetMap = (Map<String, Object>) asset;
                            if (assetMap.get("name").equals(packageNameTL)) {
                                assetId[0] = assetMap.get("id").toString();
                            }
                        }
                    }

                }catch (Throwable e){
                    e.printStackTrace();
                }
            }
        });
        checkAssetsId.start();
        try {
            checkAssetsId.join();
        }catch (Throwable e){
            e.printStackTrace();
        }
        return assetId[0];
    }
}
