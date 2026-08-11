package br.com.fichariopokemon.pokedex;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.provider.MediaStore;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.Manifest;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import androidx.core.content.ContextCompat;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.text.Text;

public final class MainActivity extends Activity {
    private static final int CREATE_BACKUP = 1001;
    private static final int OPEN_BACKUP = 1002;
    private static final int PICK_CARD_IMAGE = 1003;
    private static final int SCAN_CARD_IMAGE = 1004;
    private static final int CREATE_CSV_EXPORT = 1005;
    private static final String UPDATE_API_URL = "https://api.github.com/repos/fernandossb/POKECARD/releases/latest";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private long updateDownloadId = -1L;
    private File pendingInstallFile;
    private FrameLayout rootView;
    private WebView webView;
    private String pendingBackup;
    private String pendingCsvExport;
    private String pendingCsvExportName;
    private ValueCallback<Uri[]> pendingImageChooser;
    private Uri pendingCameraImageUri;
    private Uri pendingScannerImageUri;
    private String pendingScannerFinish = "comum";
    private double topInsetCss;
    private double bottomInsetCss;
    private FirebaseAnalytics firebaseAnalytics;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        firebaseAnalytics = FirebaseAnalytics.getInstance(this);
        // O conteúdo ocupa também a área da barra de status. O cabeçalho Web ajusta
        // internamente o safe-area para que títulos e botões nunca fiquem sob os ícones.
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.rgb(8, 5, 13));
        int systemUiFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Barra de navegação escura do Tema Gengar: ícones claros.
            systemUiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUiFlags);
        topInsetCss = systemBarHeightCss("status_bar_height");
        bottomInsetCss = systemBarHeightCss("navigation_bar_height");

        rootView = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(255, 248, 220));
        configureWebView(webView, false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (pendingImageChooser != null) pendingImageChooser.onReceiveValue(null);
                pendingImageChooser = filePathCallback;
                try {
                    launchImageChooser(fileChooserParams != null && fileChooserParams.isCaptureEnabled());
                    return true;
                } catch (Exception error) {
                    pendingImageChooser = null;
                    Toast.makeText(MainActivity.this, "Não foi possível abrir câmera ou galeria", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });
        webView.addJavascriptInterface(new AppBridge(), "Android");
        rootView.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootView);
        registerUpdateReceiver();
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView target, boolean marketProbe) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDefaultTextEncodingName("utf-8");
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(marketProbe ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        if (marketProbe) {
            settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");
        }
    }

    private double systemBarHeightCss(String resourceName) {
        int resourceId = getResources().getIdentifier(resourceName, "dimen", "android");
        if (resourceId == 0) return 0;
        int pixels = getResources().getDimensionPixelSize(resourceId);
        float density = getResources().getDisplayMetrics().density;
        return density > 0 ? pixels / density : 0;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstallFile != null && canInstallUnknownApps()) {
            File file = pendingInstallFile;
            pendingInstallFile = null;
            installDownloadedApk(file);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()", new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    // Na tela inicial o botão Voltar apenas envia o app para o
                    // segundo plano. A Activity continua viva e o estado local
                    // da coleção não é descartado.
                    if (!"true".equals(value)) MainActivity.this.moveTaskToBack(true);
                }
            });
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
        backgroundExecutor.shutdownNow();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void launchImageChooser(boolean cameraOnly) throws Exception {
        Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
        galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
        galleryIntent.setType("image/*");

        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File cameraFile = File.createTempFile("card-photo-", ".jpg", getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir());
        pendingCameraImageUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                cameraFile);
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraImageUri);
        cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        Intent chooser;
        if (cameraOnly && cameraIntent.resolveActivity(getPackageManager()) != null) {
            chooser = cameraIntent;
        } else {
            chooser = Intent.createChooser(galleryIntent, "Escolher imagem da carta");
            if (cameraIntent.resolveActivity(getPackageManager()) != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
            }
        }
        startActivityForResult(chooser, PICK_CARD_IMAGE);
    }

    private Uri[] imageChooserResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK) return null;
        if (data == null || (data.getData() == null && data.getClipData() == null)) {
            return pendingCameraImageUri == null ? null : new Uri[]{pendingCameraImageUri};
        }
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            Uri[] uris = new Uri[count];
            for (int index = 0; index < count; index++) uris[index] = data.getClipData().getItemAt(index).getUri();
            return uris;
        }
        return data.getData() == null ? null : new Uri[]{data.getData()};
    }

    private void launchScannerCamera(String finish) {
        try {
            pendingScannerFinish = finish == null || finish.isEmpty() ? "comum" : finish;
            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            File cameraFile = File.createTempFile("card-scan-", ".jpg", getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir());
            pendingScannerImageUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraFile);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingScannerImageUri);
            cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (cameraIntent.resolveActivity(getPackageManager()) == null) throw new IllegalStateException("Câmera indisponível");
            startActivityForResult(cameraIntent, SCAN_CARD_IMAGE);
        } catch (Exception error) {
            pendingScannerImageUri = null;
            runJavascript("window.receiveScannerError&&window.receiveScannerError(" + JSONObject.quote(error.getMessage() == null ? "Não foi possível abrir a câmera." : error.getMessage()) + ");");
        }
    }

    private void recognizeScannerImage(final Uri uri, final String finish) {
        try {
            InputImage fullImage = InputImage.fromFilePath(this, uri);
            InputStream bitmapInput = getContentResolver().openInputStream(uri);
            Bitmap decoded = bitmapInput == null ? null : BitmapFactory.decodeStream(bitmapInput);
            if (bitmapInput != null) bitmapInput.close();
            final Bitmap original = orientScannerBitmap(uri, decoded);
            Bitmap bottom = original == null ? null : enhancedScannerCrop(original, 0f, 0.62f, 1f, 0.38f);
            Bitmap bottomLeft = original == null ? null : enhancedScannerCrop(original, 0f, 0.58f, 0.62f, 0.42f);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            recognizer.process(fullImage).addOnSuccessListener(fullResult -> {
                final String fullText = fullResult == null ? "" : fullResult.getText();
                if (bottom == null) {
                    deliverScannerText(fullText, finish);
                    recognizer.close();
                    return;
                }
                recognizer.process(InputImage.fromBitmap(bottom, 0)).addOnSuccessListener(bottomResult -> {
                    final String bottomText = bottomResult == null ? "" : bottomResult.getText();
                    if (bottomLeft == null) {
                        deliverScannerText(fullText + "\n[NUMERO AMPLIADO]\n" + bottomText, finish);
                        recognizer.close();
                        bottom.recycle();
                        if (original != null) original.recycle();
                        return;
                    }
                    recognizer.process(InputImage.fromBitmap(bottomLeft, 0)).addOnSuccessListener(leftResult -> {
                        String leftText = leftResult == null ? "" : leftResult.getText();
                        deliverScannerText(fullText + "\n[FAIXA INFERIOR AMPLIADA]\n" + bottomText + "\n[CANTO INFERIOR AMPLIADO]\n" + leftText, finish);
                        recognizer.close();
                        bottom.recycle();
                        bottomLeft.recycle();
                        if (original != null) original.recycle();
                    }).addOnFailureListener(error -> {
                        deliverScannerText(fullText + "\n[NUMERO AMPLIADO]\n" + bottomText, finish);
                        recognizer.close();
                    });
                }).addOnFailureListener(error -> {
                    deliverScannerText(fullText, finish);
                    recognizer.close();
                });
            }).addOnFailureListener(error -> {
                runJavascript("window.receiveScannerError&&window.receiveScannerError(" + JSONObject.quote(error.getMessage() == null ? "Não foi possível ler a carta." : error.getMessage()) + ");");
                recognizer.close();
            });
        } catch (Exception error) {
            runJavascript("window.receiveScannerError&&window.receiveScannerError(" + JSONObject.quote(error.getMessage() == null ? "Não foi possível processar a foto." : error.getMessage()) + ");");
        }
    }

    private Bitmap enhancedScannerCrop(Bitmap source, float leftRatio, float topRatio, float widthRatio, float heightRatio) {
        int left = Math.max(0, Math.min(source.getWidth() - 1, Math.round(source.getWidth() * leftRatio)));
        int top = Math.max(0, Math.min(source.getHeight() - 1, Math.round(source.getHeight() * topRatio)));
        int width = Math.max(1, Math.min(source.getWidth() - left, Math.round(source.getWidth() * widthRatio)));
        int height = Math.max(1, Math.min(source.getHeight() - top, Math.round(source.getHeight() * heightRatio)));
        Bitmap crop = Bitmap.createBitmap(source, left, top, width, height);
        float scale = Math.min(4f, Math.max(1f, 3000f / Math.max(1, crop.getWidth())));
        Bitmap enlarged = Bitmap.createScaledBitmap(crop, Math.round(crop.getWidth() * scale), Math.round(crop.getHeight() * scale), true);
        if (crop != enlarged) crop.recycle();
        Bitmap enhanced = Bitmap.createBitmap(enlarged.getWidth(), enlarged.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(enhanced);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        ColorMatrix contrast = new ColorMatrix(new float[]{
                1.75f, 0, 0, 0, -72,
                0, 1.75f, 0, 0, -72,
                0, 0, 1.75f, 0, -72,
                0, 0, 0, 1, 0
        });
        matrix.postConcat(contrast);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(enlarged, 0, 0, paint);
        enlarged.recycle();
        return enhanced;
    }

    private Bitmap orientScannerBitmap(Uri uri, Bitmap source) {
        if (source == null) return null;
        InputStream exifInput = null;
        try {
            exifInput = getContentResolver().openInputStream(uri);
            if (exifInput == null) return source;
            ExifInterface exif = new ExifInterface(exifInput);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            float rotation = 0f;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90f;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180f;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270f;
            if (rotation == 0f) return source;
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
            if (rotated != source) source.recycle();
            return rotated;
        } catch (Exception ignored) {
            return source;
        } finally {
            if (exifInput != null) try { exifInput.close(); } catch (Exception ignored) {}
        }
    }

    private void deliverScannerText(String text, String finish) {
        runJavascript("window.receiveScannerText&&window.receiveScannerText(" + JSONObject.quote(text == null ? "" : text) + "," + JSONObject.quote(finish) + ");");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_CARD_IMAGE) {
            if (pendingImageChooser != null) {
                pendingImageChooser.onReceiveValue(imageChooserResult(resultCode, data));
                pendingImageChooser = null;
            }
            pendingCameraImageUri = null;
            return;
        }

        if (requestCode == SCAN_CARD_IMAGE) {
            Uri scanUri = pendingScannerImageUri;
            String finish = pendingScannerFinish;
            pendingScannerImageUri = null;
            if (resultCode == RESULT_OK && scanUri != null) recognizeScannerImage(scanUri, finish);
            else runJavascript("window.receiveScannerCancelled&&window.receiveScannerCancelled();");
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == CREATE_BACKUP && pendingBackup != null) {
                OutputStream output = null;
                try {
                    output = getContentResolver().openOutputStream(uri);
                    if (output == null) throw new IllegalStateException("Arquivo indisponível");
                    output.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                } finally {
                    if (output != null) try { output.close(); } catch (Exception ignored) {}
                }
                pendingBackup = null;
                Toast.makeText(this, "Backup salvo", Toast.LENGTH_SHORT).show();
            } else if (requestCode == CREATE_CSV_EXPORT && pendingCsvExport != null) {
                OutputStream output = null;
                try {
                    output = getContentResolver().openOutputStream(uri);
                    if (output == null) throw new IllegalStateException("Arquivo indisponível");
                    output.write(pendingCsvExport.getBytes(StandardCharsets.UTF_8));
                } finally {
                    if (output != null) try { output.close(); } catch (Exception ignored) {}
                }
                pendingCsvExport = null;
                pendingCsvExportName = null;
                Toast.makeText(this, "Planilha salva", Toast.LENGTH_SHORT).show();
            } else if (requestCode == OPEN_BACKUP) {
                InputStream input = null;
                ByteArrayOutputStream output = null;
                String json;
                try {
                    input = getContentResolver().openInputStream(uri);
                    output = new ByteArrayOutputStream();
                    if (input == null) throw new IllegalStateException("Arquivo indisponível");
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                    json = output.toString(StandardCharsets.UTF_8.name());
                } finally {
                    if (input != null) try { input.close(); } catch (Exception ignored) {}
                    if (output != null) try { output.close(); } catch (Exception ignored) {}
                }
                webView.evaluateJavascript(
                        "window.receiveImportedBackup(" + JSONObject.quote(json) + ")", null);
            }
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível usar o arquivo", Toast.LENGTH_LONG).show();
        }
    }

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id != updateDownloadId) return;
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
            android.database.Cursor cursor = null;
            try {
                cursor = manager.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL && pendingInstallFile != null && pendingInstallFile.exists()) {
                        runJavascript("window.receiveUpdateDownload && window.receiveUpdateDownload(true,'Download concluído.');");
                        installDownloadedApk(pendingInstallFile);
                    } else {
                        runJavascript("window.receiveUpdateDownload && window.receiveUpdateDownload(false,'Não foi possível baixar a atualização.');");
                    }
                }
            } catch (Exception error) {
                runJavascript("window.receiveUpdateDownload && window.receiveUpdateDownload(false,'Falha ao verificar o download.');");
            } finally {
                if (cursor != null) cursor.close();
            }
        }
    };

    private void registerUpdateReceiver() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updateReceiver, filter);
    }

    private void runJavascript(final String script) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView != null) webView.evaluateJavascript(script, null);
            }
        });
    }

    private String readText(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) result.append(line).append('\n');
        reader.close();
        return result.toString();
    }

    private int releaseBuildNumber(String tag) {
        if (tag == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)$").matcher(tag.trim());
        if (!matcher.find()) return 0;
        try { return Integer.parseInt(matcher.group(1)); } catch (Exception ignored) { return 0; }
    }

    private void checkForUpdateNative() {
        backgroundExecutor.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(UPDATE_API_URL).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(20000);
                    connection.setRequestProperty("Accept", "application/vnd.github+json");
                    connection.setRequestProperty("User-Agent", "Fichario-Pokemon-Android");
                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) throw new IllegalStateException("GitHub respondeu HTTP " + code);
                    JSONObject release = new JSONObject(readText(connection));
                    String tag = release.optString("tag_name", "");
                    int latestBuild = releaseBuildNumber(tag);
                    int currentBuild = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                    String currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    String notes = release.optString("body", "Atualização disponível.");
                    String releaseName = release.optString("name", tag);
                    String publishedAt = release.optString("published_at", "");
                    String apkUrl = "";
                    String apkName = "Fichario-Pokemon.apk";
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.optJSONObject(i);
                            if (asset == null) continue;
                            String name = asset.optString("name", "");
                            if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "");
                                apkName = name;
                                break;
                            }
                        }
                    }
                    JSONObject result = new JSONObject();
                    result.put("ok", true);
                    result.put("currentBuild", currentBuild);
                    result.put("latestBuild", latestBuild);
                    result.put("currentVersion", currentVersion == null ? "" : currentVersion);
                    result.put("latestVersion", releaseName);
                    result.put("notes", notes);
                    result.put("publishedAt", publishedAt);
                    result.put("apkUrl", apkUrl);
                    result.put("apkName", apkName);
                    result.put("updateAvailable", latestBuild > currentBuild && apkUrl.length() > 0);
                    final String payload = result.toString();
                    runJavascript("window.receiveUpdateInfo && window.receiveUpdateInfo(" + JSONObject.quote(payload) + ");");
                } catch (Exception error) {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("ok", false);
                        result.put("error", error.getMessage() == null ? "Falha ao consultar atualizações." : error.getMessage());
                    } catch (Exception ignored) {}
                    final String payload = result.toString();
                    runJavascript("window.receiveUpdateInfo && window.receiveUpdateInfo(" + JSONObject.quote(payload) + ");");
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        });
    }

    private boolean canInstallUnknownApps() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void requestInstallPermission() {
        if (Build.VERSION.SDK_INT < 26) return;
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void downloadUpdateNative(String url, String fileName) {
        try {
            URL parsed = new URL(url);
            if (!"github.com".equalsIgnoreCase(parsed.getHost()) && !"objects.githubusercontent.com".equalsIgnoreCase(parsed.getHost())) {
                throw new IllegalArgumentException("Endereço de atualização não autorizado.");
            }
            String safeName = String.valueOf(fileName).replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!safeName.toLowerCase(Locale.US).endsWith(".apk")) safeName += ".apk";
            pendingInstallFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), safeName);
            if (pendingInstallFile.exists()) pendingInstallFile.delete();
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Atualizando Fichário Pokémon");
            request.setDescription("Baixando a nova versão do aplicativo");
            request.setMimeType(APK_MIME);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, safeName);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            updateDownloadId = manager.enqueue(request);
            runJavascript("window.receiveUpdateDownload && window.receiveUpdateDownload(null,'Download iniciado.');");
        } catch (Exception error) {
            String message = error.getMessage() == null ? "Não foi possível iniciar o download." : error.getMessage();
            runJavascript("window.receiveUpdateDownload && window.receiveUpdateDownload(false," + JSONObject.quote(message) + ");");
        }
    }

    private void installDownloadedApk(File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            Toast.makeText(this, "Arquivo da atualização não encontrado", Toast.LENGTH_LONG).show();
            return;
        }
        if (!canInstallUnknownApps()) {
            pendingInstallFile = apkFile;
            Toast.makeText(this, "Permita instalar apps desta fonte e volte ao Fichário.", Toast.LENGTH_LONG).show();
            requestInstallPermission();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível abrir o instalador", Toast.LENGTH_LONG).show();
        }
    }


    private String fetchPublicText(String urlValue) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlValue).openConnection();
            connection.setConnectTimeout(18000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126 Safari/537.36");
            connection.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 400) throw new IllegalStateException("HTTP " + status);
            return readText(connection);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String htmlToPlainText(String html) {
        if (html == null) return "";
        String value = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(div|p|li|h1|h2|h3|h4|section|tr)>", "\n")
                .replaceAll("(?is)<[^>]+>", " ");
        value = value.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
        return value.replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String normalizeSearchText(String value) {
        if (value == null) return "";
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized;
    }

    private String collectorCore(String number) {
        if (number == null) return "";
        String left = number.split("/")[0].replaceAll("[^A-Za-z0-9]", "");
        return left.replaceFirst("^0+(?!$)", "");
    }

    private String decodeSearchUrl(String value) {
        if (value == null) return "";
        String decoded = value.replace("&amp;", "&").replace("\\/", "/");
        try { decoded = URLDecoder.decode(decoded, "UTF-8"); } catch (Exception ignored) {}
        java.util.regex.Matcher redirect = java.util.regex.Pattern.compile("[?&](?:url|u|r)=([^&]+)").matcher(decoded);
        if (redirect.find()) {
            try { decoded = URLDecoder.decode(redirect.group(1), "UTF-8"); } catch (Exception ignored) {}
        }
        return decoded;
    }

    private java.util.List<String> mypCandidateUrls(String html) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<String>();
        if (html == null) return new java.util.ArrayList<String>();
        java.util.regex.Matcher direct = java.util.regex.Pattern.compile(
                "https?://(?:www\\.)?mypcards\\.com/pokemon/(?:preco|produto)/\\d+/[a-z0-9-]+",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html.replace("\\/", "/"));
        while (direct.find()) urls.add(direct.group());
        java.util.regex.Matcher href = java.util.regex.Pattern.compile("href=[\\\"']([^\\\"']+)[\\\"']", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        while (href.find()) {
            String decoded = decodeSearchUrl(href.group(1));
            java.util.regex.Matcher nested = java.util.regex.Pattern.compile(
                    "https?://(?:www\\.)?mypcards\\.com/pokemon/(?:preco|produto)/\\d+/[a-z0-9-]+",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(decoded);
            if (nested.find()) urls.add(nested.group());
        }
        return new java.util.ArrayList<String>(urls);
    }

    private Double parseBrlAfterLabel(String text, String label) {
        if (text == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(label) + "[\\s\\S]{0,100}?R\\$\\s*([0-9.]+(?:,[0-9]{1,2})?)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        try { return Double.parseDouble(matcher.group(1).replace(".", "").replace(',', '.')); }
        catch (Exception ignored) { return null; }
    }

    private JSONObject queryMypCardsPrice(String cardName, String cardNumber, String setName, String setId) throws Exception {
        String numberCore = collectorCore(cardNumber);
        String[] queries = new String[] {
                "site:mypcards.com/pokemon/preco \"" + cardName + "\" \"" + cardNumber + "\" \"" + setName + "\"",
                "site:mypcards.com/pokemon/preco \"" + cardName + "\" \"" + numberCore + "\" \"" + setId + "\"",
                "site:mypcards.com/pokemon/produto \"" + cardName + "\" \"" + cardNumber + "\" \"" + setName + "\""
        };
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<String>();
        StringBuilder diagnostics = new StringBuilder();
        for (String query : queries) {
            try {
                String searchUrl = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8");
                candidates.addAll(mypCandidateUrls(fetchPublicText(searchUrl)));
            } catch (Exception error) {
                diagnostics.append("Busca: ").append(error.getMessage()).append("; ");
            }
        }
        String normalizedName = normalizeSearchText(cardName);
        String normalizedSet = normalizeSearchText(setName);
        for (String originalUrl : candidates) {
            String historyUrl = originalUrl.replace("/produto/", "/preco/");
            try {
                String html = fetchPublicText(historyUrl);
                String text = htmlToPlainText(html);
                String normalized = normalizeSearchText(text);
                boolean nameOk = normalizedName.length() == 0 || normalized.contains(normalizedName);
                boolean numberOk = numberCore.length() == 0 || normalized.contains(" " + numberCore + " ")
                        || normalized.contains(numberCore + " ") || normalized.contains(" " + numberCore);
                boolean setOk = normalizedSet.length() == 0 || normalized.contains(normalizedSet)
                        || (setId != null && setId.length() > 0 && normalized.contains(normalizeSearchText(setId)));
                if (!nameOk || !numberOk) continue;
                Double median = parseBrlAfterLabel(text, "Mediana MYP");
                if (median != null && median > 0) {
                    JSONObject result = new JSONObject();
                    result.put("brl", median);
                    result.put("metric", "median");
                    result.put("url", historyUrl);
                    result.put("matchedCode", cardNumber);
                    result.put("diagnostic", setOk ? "Correspondência exata no MYP Cards." : "Nome e número conferidos; coleção aproximada.");
                    return result;
                }
                String productUrl = originalUrl.replace("/preco/", "/produto/");
                String productText = htmlToPlainText(fetchPublicText(productUrl));
                java.util.regex.Matcher money = java.util.regex.Pattern.compile("R\\$\\s*([0-9.]+(?:,[0-9]{1,2})?)").matcher(productText);
                double lowest = Double.MAX_VALUE;
                int count = 0;
                while (money.find() && count < 40) {
                    try {
                        double value = Double.parseDouble(money.group(1).replace(".", "").replace(',', '.'));
                        if (value > 0 && value < lowest) lowest = value;
                    } catch (Exception ignored) {}
                    count++;
                }
                if (lowest < Double.MAX_VALUE) {
                    JSONObject result = new JSONObject();
                    result.put("brl", lowest);
                    result.put("metric", "lowest");
                    result.put("url", productUrl);
                    result.put("matchedCode", cardNumber);
                    result.put("diagnostic", "Mediana indisponível; usada a menor oferta visível no MYP Cards.");
                    return result;
                }
            } catch (Exception error) {
                diagnostics.append(error.getMessage()).append("; ");
            }
        }
        throw new IllegalStateException("Carta não localizada no MYP Cards. " + diagnostics.toString());
    }

    private void requestMypCardsNative(final String requestId, final String cardName, final String cardNumber, final String setName, final String setId) {
        backgroundExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject result = queryMypCardsPrice(cardName, cardNumber, setName, setId);
                    final String payload = result.toString();
                    runJavascript("window.receiveMypCardsPrice&&window.receiveMypCardsPrice(" + JSONObject.quote(requestId) + ",true," + JSONObject.quote(payload) + ",null);");
                } catch (Exception error) {
                    String message = error.getMessage() == null ? "Falha ao consultar o MYP Cards." : error.getMessage();
                    runJavascript("window.receiveMypCardsPrice&&window.receiveMypCardsPrice(" + JSONObject.quote(requestId) + ",false,null," + JSONObject.quote(message) + ");");
                }
            }
        });
    }

    public final class AppBridge {
        @JavascriptInterface
        public double getTopInsetCss() {
            return topInsetCss;
        }

        @JavascriptInterface
        public double getBottomInsetCss() {
            return bottomInsetCss;
        }

        @JavascriptInterface
        public void requestMypCards(final String requestId, final String cardName, final String cardNumber, final String setName, final String setId) {
            requestMypCardsNative(requestId, cardName, cardNumber, setName, setId);
        }

        @JavascriptInterface
        public void startCardScanner(final String finish) {
            runOnUiThread(new Runnable() {
                @Override public void run() { launchScannerCamera(finish); }
            });
        }

        @JavascriptInterface
        public void exportBackup(String json) {
            pendingBackup = json;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, "fichario-pokemon-backup.json");
                    startActivityForResult(intent, CREATE_BACKUP);
                }
            });
        }

        @JavascriptInterface
        public void exportCsv(String csv, String fileName) {
            pendingCsvExport = csv;
            pendingCsvExportName = (fileName == null || fileName.trim().isEmpty()) ? "pokecard-exportacao.csv" : fileName;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/csv");
                    intent.putExtra(Intent.EXTRA_TITLE, pendingCsvExportName);
                    startActivityForResult(intent, CREATE_CSV_EXPORT);
                }
            });
        }

        @JavascriptInterface
        public void importBackup() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    startActivityForResult(intent, OPEN_BACKUP);
                }
            });
        }

        @JavascriptInterface
        public void checkForUpdate() {
            if (BuildConfig.PLAY_STORE) {
                runJavascript("window.receiveUpdateInfo&&window.receiveUpdateInfo(" +
                        JSONObject.quote("{\"ok\":false,\"error\":\"Atualizações são gerenciadas pela Google Play.\"}") + ");");
                return;
            }
            checkForUpdateNative();
        }

        @JavascriptInterface
        public void downloadAndInstallUpdate(final String url, final String fileName) {
            if (BuildConfig.PLAY_STORE) return;
            runOnUiThread(new Runnable() {
                @Override public void run() { downloadUpdateNative(url, fileName); }
            });
        }

        @JavascriptInterface
        public void toast(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void startLiveScanner(final String finish) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.requestLiveScanner(finish);
                }
            });
        }

        @JavascriptInterface
        public void stopLiveScanner() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.closeLiveScanner();
                }
            });
        }

        /* Enquanto o painel de confirmação está aberto a câmera continua
           ligada — o usuário pediu para não sair da câmera —, mas parar de
           entregar leituras. Sem isto a carta seguinte chegaria por cima da
           pergunta que ainda está na tela. */
        @JavascriptInterface
        public void pauseLiveScanner() {
            liveScannerPaused = true;
        }

        @JavascriptInterface
        public void resumeLiveScanner() {
            liveScannerPaused = false;
            liveScannerLastDelivery = System.currentTimeMillis();
        }
    }

    /* =====================================================================
       Scanner contínuo — câmera ao vivo dentro do aplicativo.

       O modo "uma por vez" continua usando o aplicativo de câmera do celular.
       Aqui a imagem aparece dentro do app e cada quadro é lido pelo mesmo
       reconhecedor de texto, sem precisar tirar foto. Uma carta reconhecida
       não é reenviada enquanto a anterior não for concluída ou até passar o
       tempo de espera, para não cadastrar a mesma carta várias vezes.
       ===================================================================== */

    private static final int LIVE_CAMERA_PERMISSION = 2001;
    /* Espaço mínimo entre dois envios, para o app conseguir mostrar a carta
       reconhecida antes de aceitar a próxima. */
    private static final long LIVE_SCAN_INTERVAL_MS = 1800L;
    /* Texto muito curto costuma ser reflexo ou borda; não vale tentar. */
    private static final int LIVE_MIN_TEXT_LENGTH = 12;

    private FrameLayout liveScannerOverlay;
    private ExecutorService liveScannerExecutor;
    private ProcessCameraProvider liveCameraProvider;
    private ScannerLifecycle liveScannerLifecycle;
    private TextRecognizer liveRecognizer;
    private TextView liveScannerHint;
    private String pendingLiveFinish = "comum";
    private volatile boolean liveScannerBusy;
    private volatile long liveScannerLastDelivery;
    /* Ligada enquanto o app mostra o painel "é esta carta?": a câmera segue
       ligada, só não entrega leitura nova até o usuário responder. */
    private volatile boolean liveScannerPaused;

    /** Ciclo de vida próprio: a tela principal estende Activity simples,
        que a CameraX não aceita como dona da câmera. */
    private static final class ScannerLifecycle implements LifecycleOwner {
        private final LifecycleRegistry registry = new LifecycleRegistry(this);

        ScannerLifecycle() {
            registry.setCurrentState(Lifecycle.State.INITIALIZED);
        }

        void start() {
            registry.setCurrentState(Lifecycle.State.RESUMED);
        }

        void stop() {
            registry.setCurrentState(Lifecycle.State.DESTROYED);
        }

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return registry;
        }
    }

    private void requestLiveScanner(String finish) {
        pendingLiveFinish = finish == null || finish.isEmpty() ? "comum" : finish;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, LIVE_CAMERA_PERMISSION);
            return;
        }
        openLiveScanner();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LIVE_CAMERA_PERMISSION) return;
        boolean liberada = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (liberada) {
            openLiveScanner();
        } else {
            runJavascript("window.receiveScannerError&&window.receiveScannerError("
                    + JSONObject.quote("Permissão de câmera negada. Use o modo Uma por vez.") + ");");
        }
    }

    private void openLiveScanner() {
        if (liveScannerOverlay != null) return;
        try {
            liveScannerBusy = false;
            liveScannerLastDelivery = 0L;
            liveScannerExecutor = Executors.newSingleThreadExecutor();
            liveRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            PreviewView previewView = new PreviewView(this);
            previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

            /* A câmera fica ATRÁS da tela do aplicativo, não por cima.
               Antes este overlay era preto e cobria tudo, então a única coisa
               possível era um botão nativo "Encerrar" — nada da interface do
               app aparecia. Colocando a imagem embaixo e deixando a WebView
               transparente, a moldura, a faixa de miniaturas e o painel de
               confirmação são desenhados em HTML por cima da imagem ao vivo,
               junto com o resto do aplicativo. */
            liveScannerOverlay = new FrameLayout(this);
            liveScannerOverlay.setBackgroundColor(Color.BLACK);
            liveScannerOverlay.addView(previewView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            // Índice 0 = abaixo da WebView, que já está no rootView.
            rootView.addView(liveScannerOverlay, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            /* Sem isto a WebView pinta o próprio fundo e a câmera não aparece.
               A cor original é restaurada ao fechar o scanner. */
            webView.setBackgroundColor(Color.TRANSPARENT);
            runJavascript("document.documentElement.classList.add('camera-ao-vivo');");

            liveScannerLifecycle = new ScannerLifecycle();
            final ListenableFuture<ProcessCameraProvider> futuro = ProcessCameraProvider.getInstance(this);
            futuro.addListener(new Runnable() {
                @Override
                public void run() {
                    try {
                        liveCameraProvider = futuro.get();
                        bindLiveCamera(previewView);
                    } catch (Exception error) {
                        falharLiveScanner(error);
                    }
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception error) {
            falharLiveScanner(error);
        }
    }

    private void bindLiveCamera(PreviewView previewView) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analise = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analise.setAnalyzer(liveScannerExecutor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy proxy) {
                analisarQuadro(proxy);
            }
        });

        liveCameraProvider.unbindAll();
        liveScannerLifecycle.start();
        liveCameraProvider.bindToLifecycle(liveScannerLifecycle,
                CameraSelector.DEFAULT_BACK_CAMERA, preview, analise);
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    private void analisarQuadro(final ImageProxy proxy) {
        long agora = System.currentTimeMillis();
        if (liveScannerPaused || liveScannerBusy
                || agora - liveScannerLastDelivery < LIVE_SCAN_INTERVAL_MS
                || proxy.getImage() == null || liveRecognizer == null) {
            proxy.close();
            return;
        }
        liveScannerBusy = true;
        InputImage imagem = InputImage.fromMediaImage(
                proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        liveRecognizer.process(imagem)
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text resultado) {
                        String texto = resultado == null ? "" : resultado.getText();
                        if (texto.trim().length() >= LIVE_MIN_TEXT_LENGTH) {
                            liveScannerLastDelivery = System.currentTimeMillis();
                            final String finish = pendingLiveFinish;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (liveScannerHint != null) liveScannerHint.setText("Carta lida — procurando…");
                                    deliverScannerText(texto, finish);
                                }
                            });
                        }
                        liveScannerBusy = false;
                        proxy.close();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        liveScannerBusy = false;
                        proxy.close();
                    }
                });
    }

    /** Chamado pelo app quando a carta foi cadastrada, para voltar a ler. */
    private void liberarLiveScanner(final String mensagem) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (liveScannerHint != null) liveScannerHint.setText(mensagem);
                liveScannerLastDelivery = System.currentTimeMillis();
            }
        });
    }

    private void falharLiveScanner(Exception error) {
        closeLiveScanner();
        String mensagem = error == null || error.getMessage() == null
                ? "Não foi possível abrir a câmera ao vivo."
                : error.getMessage();
        runJavascript("window.receiveScannerError&&window.receiveScannerError("
                + JSONObject.quote(mensagem) + ");");
    }

    private void closeLiveScanner() {
        try {
            if (liveCameraProvider != null) liveCameraProvider.unbindAll();
        } catch (Exception ignored) {
        }
        if (liveScannerLifecycle != null) {
            liveScannerLifecycle.stop();
            liveScannerLifecycle = null;
        }
        if (liveRecognizer != null) {
            try { liveRecognizer.close(); } catch (Exception ignored) {}
            liveRecognizer = null;
        }
        if (liveScannerExecutor != null) {
            liveScannerExecutor.shutdown();
            liveScannerExecutor = null;
        }
        if (liveScannerOverlay != null) {
            rootView.removeView(liveScannerOverlay);
            liveScannerOverlay = null;
        }
        // Devolve o fundo da tela: sem isto o aplicativo fica transparente e
        // o preto da janela aparece por trás de tudo depois de escanear.
        if (webView != null) {
            webView.setBackgroundColor(Color.rgb(255, 248, 220));
            runJavascript("document.documentElement.classList.remove('camera-ao-vivo');");
        }
        liveScannerHint = null;
        liveCameraProvider = null;
        liveScannerBusy = false;
        liveScannerPaused = false;
    }
}
