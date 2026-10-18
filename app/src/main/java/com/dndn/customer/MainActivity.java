package com.dndn.customer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.*;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int PORT = 8080, UDP_PORT = 45454;
    LinearLayout box;
    TextView status;
    EditText ipIn;
    Button btn;
    WebView wv;
    ValueCallback<Uri[]> fileCb;
    SharedPreferences sp;
    volatile boolean scanning, started, listening;
    volatile String foundIp;
    NsdManager nsd;
    NsdManager.DiscoveryListener dl;
    WifiManager.MulticastLock mlock;
    java.net.DatagramSocket udp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("c", 0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 120, 48, 48);
        TextView t = new TextView(this);
        t.setText("البحث عن البائع");
        t.setTextSize(24);
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 30, 0, 30);
        ipIn = new EditText(this);
        ipIn.setHint("عنوان البائع (اختياري) مثل 192.168.1.5");
        ipIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipIn.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        btn = new Button(this);
        btn.setText("ابحث / اتصل");
        btn.setOnClickListener(v -> find());
        box.addView(t); box.addView(status); box.addView(ipIn); box.addView(btn);

        wv = new WebView(this);
        wv.setVisibility(View.GONE);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        wv.addJavascriptInterface(new Bridge(), "App");
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) lostConn();
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), 9); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        root.addView(box, new LinearLayout.LayoutParams(-1, -1));
        root.addView(wv, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        status.setText("جاري تجهيز الاتصال بالواي فاي...");
        bindWifi();
    }

    /** يجبر التطبيق على استعمال الواي فاي حتى لو بدون إنترنت */
    void bindWifi() {
        try {
            final ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network n) {
                        cm.bindProcessToNetwork(n);
                        if (!started) { started = true; runOnUiThread(() -> find()); }
                    }
                });
        } catch (Exception e) { }
        new Handler(Looper.getMainLooper()).postDelayed(() -> { if (!started) { started = true; find(); } }, 3000);
    }

    // ---------- الاكتشاف التلقائي ----------
    void startListeners() {
        foundIp = null;
        listening = true;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            mlock = wm.createMulticastLock("dndn");
            mlock.setReferenceCounted(false);
            mlock.acquire();
        } catch (Exception e) { }
        // 1) NSD / mDNS
        try {
            nsd = (NsdManager) getSystemService(NSD_SERVICE);
            dl = new NsdManager.DiscoveryListener() {
                public void onStartDiscoveryFailed(String t, int e) { }
                public void onStopDiscoveryFailed(String t, int e) { }
                public void onDiscoveryStarted(String t) { }
                public void onDiscoveryStopped(String t) { }
                public void onServiceLost(NsdServiceInfo i) { }
                public void onServiceFound(NsdServiceInfo i) {
                    try {
                        nsd.resolveService(i, new NsdManager.ResolveListener() {
                            public void onResolveFailed(NsdServiceInfo s, int e) { }
                            public void onServiceResolved(NsdServiceInfo s) {
                                if (s.getHost() != null) accept(s.getHost().getHostAddress());
                            }
                        });
                    } catch (Exception e) { }
                }
            };
            nsd.discoverServices("_dndn._tcp.", NsdManager.PROTOCOL_DNS_SD, dl);
        } catch (Exception e) { }
        // 2) بث UDP
        new Thread(() -> {
            java.net.DatagramSocket ds = null;
            try {
                ds = new java.net.DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.bind(new java.net.InetSocketAddress(UDP_PORT));
                ds.setSoTimeout(500);
                byte[] buf = new byte[256];
                while (listening) {
                    try {
                        java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String s = new String(p.getData(), 0, p.getLength(), "UTF-8");
                        if (s.startsWith("DNDN|")) accept(p.getAddress().getHostAddress());
                    } catch (java.net.SocketTimeoutException e) { }
                }
            } catch (Exception e) {
            } finally { try { if (ds != null) ds.close(); } catch (Exception e) { } }
        }).start();
    }

    void accept(String ip) { if (foundIp == null && probe(ip)) foundIp = ip; }

    void stopListeners() {
        listening = false;
        try { if (nsd != null && dl != null) nsd.stopServiceDiscovery(dl); } catch (Exception e) { }
        dl = null;
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Exception e) { }
    }

    void find() {
        if (scanning || wv.getVisibility() == View.VISIBLE) return;
        scanning = true;
        btn.setEnabled(false);
        final String manual = ipIn.getText().toString().trim();
        new Thread(() -> {
            ui("جاري البحث عن البائع...");
            startListeners();
            String ip = direct(manual);
            long t0 = System.currentTimeMillis();
            while (ip == null && System.currentTimeMillis() - t0 < 5000) {
                ip = foundIp;
                try { Thread.sleep(150); } catch (Exception e) { }
            }
            if (ip == null) ip = scanPrefixes();
            if (ip == null) ip = foundIp;
            stopListeners();
            final String r = ip;
            runOnUiThread(() -> {
                scanning = false;
                btn.setEnabled(true);
                if (r != null) connect(r);
                else {
                    status.setText("لم أجد البائع. تأكد أنك متصل بواي فاي البائع وأن تطبيق dndn شغّال عنده، أو اكتب عنوانه يدويًا.");
                    new Handler(Looper.getMainLooper()).postDelayed(this::find, 4000);
                }
            });
        }).start();
    }

    /** العنوان المكتوب يدويًا ثم آخر عنوان محفوظ: اتصال فوري */
    String direct(String manual) {
        List<String> l = new ArrayList<>();
        if (!manual.isEmpty()) l.add(manual);
        String sv = sp.getString("ip", "");
        if (!sv.isEmpty() && !l.contains(sv)) l.add(sv);
        for (String ip : l) if (probe(ip)) return ip;
        return null;
    }

    /** الخطة الأخيرة: فحص الشبكات الشائعة */
    String scanPrefixes() {
        LinkedHashSet<String> prefixes = new LinkedHashSet<>(ownPrefixes());
        prefixes.addAll(Arrays.asList("192.168.0.", "192.168.1.", "192.168.43.", "192.168.2.",
                "192.168.100.", "10.0.0.", "10.0.1.", "172.20.10."));
        for (String pre : prefixes) {
            if (foundIp != null) return foundIp;
            ui("جاري البحث في " + pre + "x ...");
            ExecutorService ex = Executors.newFixedThreadPool(64);
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 1; i < 255; i++) {
                final String ip = pre + i;
                fs.add(ex.submit(() -> probe(ip) ? ip : null));
            }
            String hit = null;
            for (Future<String> f : fs) {
                try { String r = f.get(); if (r != null) { hit = r; break; } } catch (Exception e) { }
            }
            ex.shutdownNow();
            if (hit != null) return hit;
        }
        return null;
    }

    boolean probe(String ip) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://" + ip + ":" + PORT + "/ping").openConnection();
            c.setConnectTimeout(400);
            c.setReadTimeout(800);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return false;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String s = r.readLine();
            r.close();
            return s != null && s.contains("\"app\":\"dndn\"");
        } catch (Exception e) { return false; }
    }

    List<String> ownPrefixes() {
        List<String> l = new ArrayList<>();
        try {
            for (java.net.NetworkInterface n : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                String nm = n.getName();
                if (!n.isUp() || n.isLoopback()) continue;
                if (!(nm.startsWith("wlan") || nm.startsWith("ap") || nm.startsWith("swlan") || nm.startsWith("eth"))) continue;
                for (java.net.InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address) {
                        String h = a.getHostAddress();
                        String pre = h.substring(0, h.lastIndexOf('.') + 1);
                        if (!l.contains(pre)) l.add(pre);
                    }
                }
            }
        } catch (Exception e) { }
        return l;
    }

    void ui(final String s) { runOnUiThread(() -> status.setText(s)); }

    void connect(String ip) {
        sp.edit().putString("ip", ip).apply();
        box.setVisibility(View.GONE);
        wv.setVisibility(View.VISIBLE);
        wv.loadUrl("http://" + ip + ":" + PORT + "/");
    }

    void lostConn() {
        wv.setVisibility(View.GONE);
        box.setVisibility(View.VISIBLE);
        status.setText("انقطع الاتصال، جاري البحث من جديد...");
        find();
    }

    class Bridge {
        @JavascriptInterface public String getId() { return sp.getString("id", ""); }
        @JavascriptInterface public void setId(String v) { sp.edit().putString("id", v).apply(); }
        @JavascriptInterface public String getName() { return sp.getString("name", ""); }
        @JavascriptInterface public void setName(String v) { sp.edit().putString("name", v).apply(); }
        @JavascriptInterface public void lost() { runOnUiThread(() -> lostConn()); }
    }

    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq == 9 && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rs, d));
            fileCb = null;
        }
    }
}
