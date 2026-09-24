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
import android.view.HapticFeedbackConstants;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.TextView;
import androidx.annotation.NonNull;
import android.util.Size;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
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
    private static final int CREATE_CSV_EXPORT = 1005;
    private static final int CREATE_FILE_EXPORT = 1006;
    private static final String UPDATE_API_URL = "https://api.github.com/repos/fernandossb/POKECARD/releases/latest";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private long updateDownloadId = -1L;
    private File pendingInstallFile;
    private FrameLayout rootView;
    private WebView webView;
    private String pendingBackup;
    private String pendingCsvExport;
    private String pendingCsvExportName;
    // Exportação em PDF ou Excel: o arquivo chega da página em pedaços.
    private ByteArrayOutputStream arquivoEmMontagem;
    private byte[] arquivoPendente;
    private ValueCallback<Uri[]> pendingImageChooser;
    private Uri pendingCameraImageUri;
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
        configureWebView(webView);
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
    private void configureWebView(WebView target) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDefaultTextEncodingName("utf-8");
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
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

    private Bitmap enhancedScannerCrop(Bitmap source, float leftRatio, float topRatio, float widthRatio, float heightRatio) {
        int left = Math.max(0, Math.min(source.getWidth() - 1, Math.round(source.getWidth() * leftRatio)));
        int top = Math.max(0, Math.min(source.getHeight() - 1, Math.round(source.getHeight() * topRatio)));
        int width = Math.max(1, Math.min(source.getWidth() - left, Math.round(source.getWidth() * widthRatio)));
        int height = Math.max(1, Math.min(source.getHeight() - top, Math.round(source.getHeight() * heightRatio)));
        Bitmap crop = Bitmap.createBitmap(source, left, top, width, height);

        /* Ampliação com teto de memória.
         *
         * A conta antiga mirava 3000 pixels de largura, o que fazia sentido
         * enquanto o quadro tinha 640: dava 4× e uma imagem pequena. Com o
         * quadro em 1080, a faixa de baixo viraria 3000×2400 — quase 29 MB só
         * nela, e o mesmo de novo na cópia com contraste. Dois recortes assim
         * derrubariam o aplicativo por falta de memória.
         *
         * Agora existe um orçamento de pixels: amplia o quanto der até 2,5×,
         * respeitando o limite. O ganho de nitidez veio da captura, não do
         * esticão — quem já foi capturado grande não precisa esticar tanto. */
        final int TETO_DE_PIXELS = 4_000_000;   // ~16 MB por bitmap ARGB
        float escala = Math.max(1f, Math.min(2.5f, 1800f / Math.max(1, crop.getWidth())));
        long previsto = (long) (crop.getWidth() * escala) * (long) (crop.getHeight() * escala);
        if (previsto > TETO_DE_PIXELS) {
            escala = (float) Math.sqrt((double) TETO_DE_PIXELS / (double) (crop.getWidth() * crop.getHeight()));
            escala = Math.max(1f, escala);
        }
        Bitmap enlarged = escala <= 1.01f ? crop : Bitmap.createScaledBitmap(
                crop, Math.round(crop.getWidth() * escala), Math.round(crop.getHeight() * escala), true);
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
            } else if (requestCode == CREATE_FILE_EXPORT && arquivoPendente != null) {
                OutputStream output = null;
                try {
                    output = getContentResolver().openOutputStream(uri);
                    if (output == null) throw new IllegalStateException("Arquivo indisponível");
                    output.write(arquivoPendente);
                } finally {
                    if (output != null) try { output.close(); } catch (Exception ignored) {}
                }
                arquivoPendente = null;
                Toast.makeText(this, "Arquivo salvo", Toast.LENGTH_SHORT).show();
                runJavascript("window.receberArquivoExportado&&window.receberArquivoExportado('salvo');");
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

    // O arquivo de exportação que a página terminou de mandar, ou null.
    private synchronized byte[] arquivoMontado() {
        if (arquivoEmMontagem == null || arquivoEmMontagem.size() == 0) return null;
        return arquivoEmMontagem.toByteArray();
    }

    private static String nomeDeArquivoSeguro(String nome) {
        String limpo = nome == null ? "" : nome.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return limpo.isEmpty() ? "pokecard-exportacao" : limpo;
    }

    private static String tipoDoArquivo(String mime) {
        return mime == null || mime.trim().isEmpty() ? "application/octet-stream" : mime.trim();
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

        /**
         * Backup sem perguntar nada, direto na pasta Download.
         *
         * O `exportBackup` abre o seletor de arquivos do sistema — ótimo para
         * um backup pedido, impossível para um automático: apareceria uma
         * janela do nada. Aqui a gravação é silenciosa, e vai para a pasta
         * Download pública de propósito: a pasta privada do aplicativo some
         * junto com ele na desinstalação, que é justamente um dos casos de que
         * o backup deveria proteger.
         */
        @JavascriptInterface
        public void salvarBackupAutomatico(final String json, final String nomeArquivo) {
            final String nome = (nomeArquivo == null || nomeArquivo.trim().isEmpty())
                    ? "pokecard-backup-automatico.json" : nomeArquivo.trim();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String resultado;
                    try {
                        OutputStream saida = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            android.content.ContentValues dados = new android.content.ContentValues();
                            dados.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
                            dados.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                            dados.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                            Uri destino = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dados);
                            if (destino != null) saida = getContentResolver().openOutputStream(destino, "wt");
                        } else {
                            File pasta = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                            if (pasta != null && (pasta.exists() || pasta.mkdirs())) {
                                saida = new java.io.FileOutputStream(new File(pasta, nome));
                            }
                        }
                        if (saida == null) throw new Exception("sem acesso à pasta Download");
                        saida.write(json.getBytes(StandardCharsets.UTF_8));
                        saida.flush();
                        saida.close();
                        resultado = "ok";
                    } catch (Exception erro) {
                        resultado = erro.getMessage() == null ? "falhou" : erro.getMessage();
                    }
                    final String aviso = resultado;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            runJavascript("window.receberBackupAutomatico&&window.receberBackupAutomatico("
                                    + JSONObject.quote(aviso) + "," + JSONObject.quote(nome) + ");");
                        }
                    });
                }
            }).start();
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

        /* Exportação das cartas em PDF ou Excel (tela "Exportar" da Coleção).
           O arquivo é binário e pode passar de alguns megabytes — uma foto por
           carta —, então chega em pedaços de base64 que aqui voltam a ser
           bytes. Depois vai para o "Salvar como" do sistema ou para o
           compartilhamento (WhatsApp, e-mail, Drive). */
        @JavascriptInterface
        public void arquivoComecar() {
            synchronized (MainActivity.this) {
                arquivoEmMontagem = new ByteArrayOutputStream();
            }
        }

        @JavascriptInterface
        public void arquivoPedaco(String base64) {
            if (base64 == null || base64.isEmpty()) return;
            byte[] pedaco = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            synchronized (MainActivity.this) {
                if (arquivoEmMontagem == null) arquivoEmMontagem = new ByteArrayOutputStream();
                arquivoEmMontagem.write(pedaco, 0, pedaco.length);
            }
        }

        @JavascriptInterface
        public void arquivoSalvar(final String nome, final String mime) {
            final byte[] bytes = arquivoMontado();
            if (bytes == null) return;
            arquivoPendente = bytes;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(tipoDoArquivo(mime));
                    intent.putExtra(Intent.EXTRA_TITLE, nomeDeArquivoSeguro(nome));
                    startActivityForResult(intent, CREATE_FILE_EXPORT);
                }
            });
        }

        @JavascriptInterface
        public void arquivoCompartilhar(final String nome, final String mime) {
            final byte[] bytes = arquivoMontado();
            if (bytes == null) return;
            backgroundExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // A pasta de cache já está liberada no FileProvider
                        // (cache-path "."), como as fotos da câmera.
                        File pasta = new File(getCacheDir(), "exportacoes");
                        if (!pasta.exists() && !pasta.mkdirs()) throw new IllegalStateException("sem pasta");
                        File arquivo = new File(pasta, nomeDeArquivoSeguro(nome));
                        java.io.FileOutputStream saida = new java.io.FileOutputStream(arquivo);
                        try { saida.write(bytes); } finally { saida.close(); }
                        final Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", arquivo);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Intent envio = new Intent(Intent.ACTION_SEND);
                                envio.setType(tipoDoArquivo(mime));
                                envio.putExtra(Intent.EXTRA_STREAM, uri);
                                envio.putExtra(Intent.EXTRA_SUBJECT, nomeDeArquivoSeguro(nome));
                                envio.setClipData(android.content.ClipData.newRawUri(nomeDeArquivoSeguro(nome), uri));
                                envio.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(envio, "Compartilhar " + nomeDeArquivoSeguro(nome)));
                            }
                        });
                    } catch (Exception erro) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Não foi possível compartilhar o arquivo", Toast.LENGTH_LONG).show();
                            }
                        });
                    }
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

        /* Toque de confirmação: a carta entrou, sem precisar olhar para a tela
           — no scanner a mão está segurando a carta. Usa o retorno tátil do
           sistema, que respeita a opção "vibrar ao tocar" do aparelho e não
           precisa de permissão nova. "curto": carta lida ou adicionada.
           "duplo": coleção completa, troféu, cartas do scanner gravadas. */
        @JavascriptInterface
        public void vibrar(final String padrao) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (webView == null) return;
                    final int efeito = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? HapticFeedbackConstants.CONFIRM
                            : HapticFeedbackConstants.VIRTUAL_KEY;
                    webView.performHapticFeedback(efeito);
                    if ("duplo".equals(padrao)) {
                        webView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (webView != null) webView.performHapticFeedback(efeito);
                            }
                        }, 140);
                    }
                }
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
       Scanner — câmera ao vivo dentro do aplicativo (modo único).

       A imagem aparece dentro do app e cada quadro é lido pelo reconhecedor de
       texto, sem precisar tirar foto. Uma carta reconhecida não é reenviada
       enquanto a anterior não for concluída ou até passar o tempo de espera,
       para não cadastrar a mesma carta várias vezes.
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
        /* Deixar a tela pronta para mostrar a câmera vem ANTES de conferir se
           ela já está aberta. Assim `startLiveScanner` pode ser chamado a
           qualquer momento com o sentido de "garanta que a câmera aparece":
           se algo tiver devolvido o fundo opaco no meio do caminho, esta
           chamada conserta, em vez de sair calada e deixar o aplicativo
           desenhado por cima da imagem. */
        if (webView != null) {
            webView.setBackgroundColor(Color.TRANSPARENT);
            runJavascript("document.documentElement.classList.add('camera-ao-vivo');");
        }
        liveScannerPaused = false;
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

        /* Resolução da análise: 1920×1080, não os 640×480 do padrão.
         *
         * Era esta a razão de o número do rodapé nunca sair. A 640×480, com a
         * carta ocupando metade da altura do quadro, "049/193" mede cerca de
         * SEIS pixels de altura — ampliar não recupera o que não foi captado.
         * O nome e os ataques, que são letras grandes, saíam bem; só o número
         * é que ficava fora de alcance, e o número é o que identifica a carta.
         *
         * A 1080 de largura o mesmo número passa dos vinte pixels, que é o
         * mínimo confortável para o reconhecimento de texto. */
        ResolutionSelector seletor = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        new Size(1080, 1920),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();

        ImageAnalysis analise = new ImageAnalysis.Builder()
                .setResolutionSelector(seletor)
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

    /**
     * Converte o quadro da câmera numa imagem em tons de cinza.
     *
     * Usa apenas o plano de luminância (Y) do formato YUV. É de onde vem toda
     * a informação que o reconhecimento de texto aproveita — a cor não ajuda a
     * ler um número — e evita a conversão completa de cor, que é cara e
     * introduz manchas nas bordas das letras.
     */
    private Bitmap bitmapCinzaDoQuadro(ImageProxy proxy) {
        ImageProxy.PlaneProxy plano = proxy.getPlanes()[0];
        ByteBuffer buffer = plano.getBuffer();
        int largura = proxy.getWidth();
        int altura = proxy.getHeight();
        int rowStride = plano.getRowStride();
        int pixelStride = plano.getPixelStride();

        int[] pixels = new int[largura * altura];
        byte[] linha = new byte[rowStride];
        int destino = 0;
        for (int y = 0; y < altura; y++) {
            int inicio = y * rowStride;
            if (inicio >= buffer.limit()) break;
            buffer.position(inicio);
            int lidos = Math.min(rowStride, buffer.remaining());
            buffer.get(linha, 0, lidos);
            for (int x = 0; x < largura; x++) {
                int posicao = x * pixelStride;
                int v = posicao < lidos ? (linha[posicao] & 0xFF) : 0;
                pixels[destino++] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(pixels, largura, altura, Bitmap.Config.ARGB_8888);
        int giro = proxy.getImageInfo().getRotationDegrees();
        if (giro == 0) return bitmap;
        Matrix matriz = new Matrix();
        matriz.postRotate(giro);
        Bitmap girado = Bitmap.createBitmap(bitmap, 0, 0, largura, altura, matriz, true);
        if (girado != bitmap) bitmap.recycle();
        return girado;
    }

    /**
     * Lê o quadro duas vezes: inteiro e só a metade de baixo, ampliada.
     *
     * A numeração do rodapé ("015/094") é o menor texto da carta e o único que
     * a identifica sozinha — o nome do Pokémon se repete em dezenas de
     * impressões. Lido junto com o resto, esse número quase nunca sai; ampliado
     * três vezes e com o contraste puxado, sai quase sempre.
     */
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

        final Bitmap quadro;
        try {
            quadro = bitmapCinzaDoQuadro(proxy);
        } catch (Exception erro) {
            liveScannerBusy = false;
            proxy.close();
            return;
        } finally {
            // O quadro já virou bitmap: soltar cedo evita travar a câmera.
            proxy.close();
        }

        final TextRecognizer reconhecedor = liveRecognizer;
        if (reconhecedor == null) { quadro.recycle(); liveScannerBusy = false; return; }

        reconhecedor.process(InputImage.fromBitmap(quadro, 0))
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text resultado) {
                        final String textoCheio = resultado == null ? "" : resultado.getText();
                        Bitmap rodape = null;
                        Bitmap faixaNumero = null;
                        try {
                            // Metade de baixo, ampliada e com contraste: é onde
                            // fica a numeração e o código da coleção.
                            rodape = enhancedScannerCrop(quadro, 0f, .55f, 1f, .45f);
                            /* E uma segunda passada só na tira do número.
                             *
                             * O recorte grande traz junto o texto de ataque e a
                             * história da carta, que são letras bem maiores. O
                             * reconhecimento se firma nelas e passa por cima da
                             * numeração, que é a menor coisa impressa na carta.
                             * Numa tira estreita não há concorrência: só sobra
                             * o rodapé, e ele vem ampliado o dobro. */
                            faixaNumero = enhancedScannerCrop(quadro, 0f, .80f, 1f, .20f);
                        } catch (Exception ignorado) {
                        }
                        if (rodape == null) {
                            concluirLeitura(textoCheio, "", quadro, null, null);
                            return;
                        }
                        final Bitmap recorte = rodape;
                        final Bitmap tira = faixaNumero;
                        reconhecedor.process(InputImage.fromBitmap(recorte, 0))
                                .addOnSuccessListener(new OnSuccessListener<Text>() {
                                    @Override
                                    public void onSuccess(Text baixo) {
                                        final String textoBaixo = baixo == null ? "" : baixo.getText();
                                        if (tira == null) {
                                            concluirLeitura(textoCheio, textoBaixo, quadro, recorte, null);
                                            return;
                                        }
                                        reconhecedor.process(InputImage.fromBitmap(tira, 0))
                                                .addOnSuccessListener(new OnSuccessListener<Text>() {
                                                    @Override
                                                    public void onSuccess(Text numero) {
                                                        concluirLeitura(textoCheio, textoBaixo,
                                                                numero == null ? "" : numero.getText(),
                                                                quadro, recorte, tira);
                                                    }
                                                })
                                                .addOnFailureListener(new OnFailureListener() {
                                                    @Override
                                                    public void onFailure(@NonNull Exception e) {
                                                        concluirLeitura(textoCheio, textoBaixo, quadro, recorte, tira);
                                                    }
                                                });
                                    }
                                })
                                .addOnFailureListener(new OnFailureListener() {
                                    @Override
                                    public void onFailure(@NonNull Exception e) {
                                        concluirLeitura(textoCheio, "", quadro, recorte, tira);
                                    }
                                });
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        quadro.recycle();
                        liveScannerBusy = false;
                    }
                });
    }

    private void concluirLeitura(String textoCheio, String textoRodape, Bitmap quadro, Bitmap recorte, Bitmap tira) {
        concluirLeitura(textoCheio, textoRodape, "", quadro, recorte, tira);
    }

    private void concluirLeitura(String textoCheio, String textoRodape, String textoNumero,
                                 Bitmap quadro, Bitmap recorte, Bitmap tira) {
        if (quadro != null) quadro.recycle();
        if (recorte != null) recorte.recycle();
        if (tira != null) tira.recycle();
        liveScannerBusy = false;

        StringBuilder juntar = new StringBuilder(textoCheio == null ? "" : textoCheio);
        if (textoRodape != null && !textoRodape.trim().isEmpty()) {
            juntar.append("\n[FAIXA INFERIOR AMPLIADA]\n").append(textoRodape);
        }
        if (textoNumero != null && !textoNumero.trim().isEmpty()) {
            juntar.append("\n[NUMERO AMPLIADO]\n").append(textoNumero);
        }
        String junto = juntar.toString();
        if (junto.trim().length() < LIVE_MIN_TEXT_LENGTH) return;

        liveScannerLastDelivery = System.currentTimeMillis();
        final String finish = pendingLiveFinish;
        final String entrega = junto;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (liveScannerHint != null) liveScannerHint.setText("Carta lida — procurando…");
                deliverScannerText(entrega, finish);
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
