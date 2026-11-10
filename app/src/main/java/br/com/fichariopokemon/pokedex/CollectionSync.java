package br.com.fichariopokemon.pokedex;

import android.app.Activity;
import android.content.Intent;
import android.util.Base64;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bridge assíncrona de revisões imutáveis. A coleção local é a fonte de trabalho. */
final class CollectionSync {
    static final int SIGN_IN = 1045;
    private static final int PART_SIZE = 400000;
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    interface Callback { void send(String request, JSONObject result); }
    private final Activity activity;
    private final Callback callback;
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    CollectionSync(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    private void reply(String request, String key, Object value) {
        try { callback.send(request, new JSONObject().put(key, value)); }
        catch (Exception ignored) { }
    }

    private void fail(String request, Exception error) {
        reply(request, "error", error.getMessage() == null ? "Falha de conexão" : error.getMessage());
    }

    void account(String request) {
        FirebaseUser user = auth.getCurrentUser();
        try {
            JSONObject result = new JSONObject();
            if (user != null) result.put("uid", user.getUid()).put("email", user.getEmail());
            callback.send(request, result);
        } catch (Exception error) { fail(request, error); }
    }

    void signIn() {
        activity.runOnUiThread(() -> {
            int id = activity.getResources().getIdentifier("default_web_client_id", "string", activity.getPackageName());
            if (id == 0) {
                reply("login", "error", "Login Google ainda não configurado no Firebase.");
                return;
            }
            GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(activity.getString(id)).requestEmail().build();
            activity.startActivityForResult(GoogleSignIn.getClient(activity, options).getSignInIntent(), SIGN_IN);
        });
    }

    void signInResult(Intent data) {
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(com.google.android.gms.common.api.ApiException.class);
            auth.signInWithCredential(GoogleAuthProvider.getCredential(account.getIdToken(), null))
                    .addOnSuccessListener(result -> account("login"))
                    .addOnFailureListener(error -> fail("login", error));
        } catch (Exception error) { fail("login", error); }
    }

    void signOut() {
        auth.signOut();
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build();
        GoogleSignIn.getClient(activity, options).signOut();
        account("logout");
    }

    private DocumentReference owner() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) throw new IllegalStateException("Entre na sua conta Google.");
        return db.collection("colecoes").document(user.getUid());
    }

    void read(String request, String knownRevision) {
        try {
            DocumentReference owner = owner();
            owner.get(Source.SERVER).addOnSuccessListener(IO, head -> {
                String revision = head.getString("revisao");
                if (revision == null) { reply(request, "revision", ""); return; }
                if (revision.equals(knownRevision)) {
                    try {
                        callback.send(request, new JSONObject().put("revision", revision).put("unchanged", true));
                    } catch (Exception error) { fail(request, error); }
                    return;
                }
                Long count = head.getLong("partes");
                if (count == null || count < 1 || count > 100) {
                    fail(request, new IllegalStateException("Revisão da nuvem inválida.")); return;
                }
                List<Task<com.google.firebase.firestore.DocumentSnapshot>> tasks = new ArrayList<>();
                for (int i = 0; i < count; i++)
                    tasks.add(owner.collection("revisoes").document(revision).collection("partes")
                            .document(String.valueOf(i)).get(Source.SERVER));
                Tasks.whenAllSuccess(tasks).addOnSuccessListener(IO, parts -> {
                    try {
                        StringBuilder encoded = new StringBuilder();
                        for (Object part : parts) {
                            String value = ((com.google.firebase.firestore.DocumentSnapshot) part).getString("dados");
                            if (value == null) throw new IllegalStateException("Parte da revisão ausente.");
                            encoded.append(value);
                        }
                        byte[] compressed = Base64.decode(encoded.toString(), Base64.NO_WRAP);
                        GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = input.read(buffer)) != -1) {
                            output.write(buffer, 0, length);
                            if (output.size() > 30000000) throw new IllegalStateException("Coleção excede o limite.");
                        }
                        callback.send(request, new JSONObject().put("revision", revision)
                                .put("data", new String(output.toByteArray(), StandardCharsets.UTF_8)));
                    } catch (Exception error) { fail(request, error); }
                }).addOnFailureListener(error -> fail(request, error));
            }).addOnFailureListener(error -> fail(request, error));
        } catch (Exception error) { fail(request, error); }
    }

    void write(String request, String expectedRevision, String data) {
        IO.execute(() -> writeInBackground(request, expectedRevision, data));
    }

    private void writeInBackground(String request, String expectedRevision, String data) {
        try {
            DocumentReference owner = owner();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(output);
            gzip.write(data.getBytes(StandardCharsets.UTF_8));
            gzip.close();
            String encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            int count = (encoded.length() + PART_SIZE - 1) / PART_SIZE;
            if (count < 1 || count > 100) throw new IllegalStateException("Coleção excede o limite.");
            String revision = UUID.randomUUID().toString();
            List<Task<Void>> writes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Map<String, Object> part = new HashMap<>();
                part.put("dados", encoded.substring(i * PART_SIZE, Math.min(encoded.length(), (i + 1) * PART_SIZE)));
                writes.add(owner.collection("revisoes").document(revision).collection("partes")
                        .document(String.valueOf(i)).set(part));
            }
            Tasks.whenAll(writes).addOnSuccessListener(unused ->
                db.runTransaction(transaction -> {
                    String current = transaction.get(owner).getString("revisao");
                    if (!expectedRevision.equals(current == null ? "" : current))
                        throw new com.google.firebase.firestore.FirebaseFirestoreException("CONFLICT",
                                com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED);
                    Map<String, Object> head = new HashMap<>();
                    head.put("revisao", revision);
                    head.put("partes", count);
                    transaction.set(owner, head);
                    return null;
                }).addOnSuccessListener(result -> reply(request, "revision", revision))
                  .addOnFailureListener(error -> fail(request, error))
            ).addOnFailureListener(error -> fail(request, error));
        } catch (Exception error) { fail(request, error); }
    }
}
