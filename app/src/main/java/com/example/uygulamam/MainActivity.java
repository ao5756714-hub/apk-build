package com.example.uygulamam;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final boolean LOCAL = true;
    private static final int FILE_REQ = 1001;
    private static final int BRIDGE_REQ = 1002;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    private Intent buildPicker(String[] accept, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        ArrayList<String> mimes = new ArrayList<String>();
        boolean any = false;
        if (accept != null) {
            for (String a : accept) {
                if (a == null) continue;
                a = a.trim();
                if (a.length() == 0) { any = true; continue; }
                if (a.indexOf('/') > 0) mimes.add(a);
            }
        }
        if (!any && mimes.size() > 0) {
            if (mimes.size() == 1) i.setType(mimes.get(0));
            else i.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return Intent.createChooser(i, null);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            public void run() { web.evaluateJavascript(code, null); }
        });
    }

    public class Bridge {
        @JavascriptInterface
        public void pick(final String accept, final boolean multiple) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        String[] acc = (accept == null || accept.length() == 0) ? new String[0] : accept.split(",");
                        startActivityForResult(buildPicker(acc, multiple), BRIDGE_REQ);
                    } catch (Exception e) {
                        toast("Dosya secici acilamadi: " + e);
                        js("window.__nativeDone()");
                    }
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (LOCAL && (url.startsWith("http://") || url.startsWith("https://"))) {
                    openExternal(url);
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    boolean multi = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                    startActivityForResult(buildPicker(params.getAcceptTypes(), multi), FILE_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("Dosya secici acilamadi: " + e);
                    return false;
                }
                return true;
            }
        });
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                openExternal(url);
            }
        });
        if (LOCAL) web.addJavascriptInterface(new Bridge(), "AndroidFiles");
        web.loadUrl("file:///android_asset/index.html");
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) { }
    }

    private void sendFiles(ArrayList<Uri> uris) {
        for (Uri u : uris) {
            try {
                String name = "dosya";
                Cursor c = getContentResolver().query(u, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (ix >= 0) name = c.getString(ix);
                        }
                    } finally { c.close(); }
                }
                String mime = getContentResolver().getType(u);
                if (mime == null) mime = "";
                InputStream in = getContentResolver().openInputStream(u);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                js("window.__nativeFile(" + JSONObject.quote(name) + "," + JSONObject.quote(mime) + ",'" + b64 + "')");
            } catch (Exception e) {
                toast("Dosya okunamadi: " + e);
            }
        }
        js("window.__nativeDone()");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQ && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
            return;
        }
        if (requestCode == BRIDGE_REQ) {
            final ArrayList<Uri> uris = new ArrayList<Uri>();
            if (resultCode == RESULT_OK && data != null) {
                ClipData cd = data.getClipData();
                if (cd != null) {
                    for (int k = 0; k < cd.getItemCount(); k++) uris.add(cd.getItemAt(k).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
            }
            new Thread(new Runnable() {
                public void run() { sendFiles(uris); }
            }).start();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
