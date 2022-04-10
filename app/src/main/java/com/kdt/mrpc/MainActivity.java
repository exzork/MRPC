package com.kdt.mrpc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.util.*;
import java.util.stream.Collectors;

import org.java_websocket.client.WebSocketClient;

public class MainActivity extends Activity 
{
    private SharedPreferences pref;

    private Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private Runnable heartbeatRunnable;
    public static WebSocketClient webSocketClient;
    public static ArrayList<String> whiteList = new ArrayList<>();
    private Thread heartbeatThr, wsThr;
    private int heartbeat_interval, seq;
    private String authToken;

    private WebView webView;
    private static TextView textviewLog;
    private Button buttonConnect, buttonSetActivity, selectApp;
    private EditText editActivityName, editActivityState, editActivityDetails;
    private ImageButton imageIcon;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);


        webView = (WebView) findViewById(R.id.mainWebView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAppCacheEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                Log.d("Web", "Attempt to enter " + url);
                webView.stopLoading();
                if (url.endsWith("/app")) {
                    webView.setVisibility(View.GONE);
                    extractToken();
                    login(v);
                }
                return false;
                // super.shouldOverrideUrlLoading(v, url);
            }
        });

        textviewLog = (TextView) findViewById(R.id.textviewLog);
        buttonConnect = (Button) findViewById(R.id.buttonConnect);
        buttonSetActivity = (Button) findViewById(R.id.buttonSetActivity);
        buttonSetActivity.setEnabled(true);
        selectApp = (Button) findViewById(R.id.select_app);

        pref = getSharedPreferences("pref", MODE_PRIVATE);
        authToken = pref.getString("authToken", null);
    }

    public void setSelectApp(View v) {
        ArrayList<String> checkedApp = new ArrayList<String>();
        String[] appList = packageNameAndAppName().keySet().toArray(new String[0]);
        String[] appNames = packageNameAndAppName().values().toArray(new String[0]);
        boolean[] checkedItems = new boolean[appList.length];

        pref = getSharedPreferences("pref", MODE_PRIVATE);
        if (pref.getStringSet("selected_app", null) != null) {
            checkedApp = new ArrayList<String>(pref.getStringSet("selected_app", null));
            whiteList = checkedApp;
            if (checkedApp.size() > 0) {
                for (int i = 0; i < appList.length; i++) {
                    if (checkedApp.contains(appList[i])) {
                        checkedItems[i] = true;
                    }
                }
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select App");
        builder.setCancelable(true);
        ArrayList<String> finalCheckedApp = checkedApp;
        builder.setMultiChoiceItems(appNames, checkedItems, new DialogInterface.OnMultiChoiceClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i, boolean b) {
                if(b) {
                    finalCheckedApp.add(appList[i]);
                }else{
                    finalCheckedApp.remove(appList[i]);
                }
            }
        });


        ArrayList<String> finalCheckedApp1 = checkedApp;
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                Log.d("App", "Selected app: " + finalCheckedApp1);
                Set<String> selectedApp = new HashSet<String>(finalCheckedApp1);
                pref.edit().putStringSet("selected_app", selectedApp).apply();
                whiteList = finalCheckedApp1;
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });

        ArrayList<String> finalCheckedApp2 = checkedApp;
        builder.setNeutralButton("Clear", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                finalCheckedApp2.clear();
                Arrays.fill(checkedItems, false);
            }
        });
        builder.show();
    }

    public void disconnect(View v) {

        stopService(new Intent(this, BackgroundService.class));
        doUnbindService();
    }

    public static void appendlnToLog(final String msg) {
        textviewLog.append(msg + "\n");
    }

    public void login(View v) {
        if (webView.getVisibility() == View.VISIBLE) {
            webView.stopLoading();
            webView.setVisibility(View.GONE);
            return;
        }
        if (authToken != null) {
            appendlnToLog("Logged in");
            return;
        }
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl("https://discord.com/login");
    }

    public void connect(View v) {
        if(authToken != null) {
            doBindService();
            startService(new Intent(this, BackgroundService.class));
        }else{
            Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show();
        }
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
            pref.edit().putString("authToken", authToken).apply();
            return true;
        } catch (Throwable e) {
            appendlnToLog("Failed extracting token");
            return false;
        }
    }




    private boolean mShouldUnbind;

    // To invoke the bound service, first make sure that this value
    // is not null.
    private BackgroundService mBoundService;

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            mBoundService = ((BackgroundService.LocalBinder)service).getService();

            // Tell the user about this for our demo.
            Toast.makeText(MainActivity.this, "Service connected",
                    Toast.LENGTH_SHORT).show();
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            mBoundService = null;
            Toast.makeText(MainActivity.this, "Service disconnected",
                    Toast.LENGTH_SHORT).show();
        }
    };

    void doBindService() {
        // Attempts to establish a connection with the service.  We use an
        // explicit class name because we want a specific service
        // implementation that we know will be running in our own process
        // (and thus won't be supporting component replacement by other
        // applications).
        if (bindService(new Intent(MainActivity.this, BackgroundService.class),
                mConnection, Context.BIND_AUTO_CREATE)) {
            mShouldUnbind = true;
        } else {
            Log.e("MY_APP_TAG", "Error: The requested service doesn't " +
                    "exist, or this client isn't allowed access to it.");
        }
    }

    void doUnbindService() {
        if (mShouldUnbind) {
            // Release information about the service's state.
            unbindService(mConnection);
            mShouldUnbind = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        doUnbindService();
    }


    private Map<String, String> packageNameAndAppName(){
        Map<String, String> map = new HashMap<>();
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo packageInfo : packages) {
            map.put(packageInfo.packageName, packageInfo.loadLabel(pm).toString());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return map.entrySet().stream().sorted(Map.Entry.comparingByValue()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
        }else{
            return map;
        }
    }
}
