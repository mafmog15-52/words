package com.wordimage.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME    = "WordImagePrefs";
    private static final String KEY_DIR_URI   = "selected_dir_uri";
    private static final String KEY_DIR_NAME  = "selected_dir_name";

    private TextInputEditText editTextWord;
    private TextView          textCharCount;
    private TextView          textSelectedDir;
    private MaterialButton    btnSelectDir;
    private MaterialButton    btnGenerate;
    private CardView          cardStatus;
    private TextView          textStatus;
    private MaterialButton    btnOpenFile;
    private ProgressBar       progressBar;

    private SharedPreferences prefs;
    private String            selectedDirUri;   // persisted URI string
    private String            lastSavedFilePath; // for "Open file" button (internal temp)
    private Uri               lastSavedUri;      // SAF Uri of the saved file

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         handler  = new Handler(Looper.getMainLooper());

    // SAF directory picker
    private final ActivityResultLauncher<Uri> dirPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;

                // Persist permission across reboots
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                selectedDirUri = uri.toString();

                // Friendly display name
                DocumentFile dir = DocumentFile.fromTreeUri(this, uri);
                String name = (dir != null && dir.getName() != null) ? dir.getName() : uri.getLastPathSegment();

                prefs.edit()
                     .putString(KEY_DIR_URI, selectedDirUri)
                     .putString(KEY_DIR_NAME, name)
                     .apply();

                textSelectedDir.setText(name);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        bindViews();
        restoreSavedDirectory();
        setupListeners();
    }

    private void bindViews() {
        editTextWord    = findViewById(R.id.editTextWord);
        textCharCount   = findViewById(R.id.textCharCount);
        textSelectedDir = findViewById(R.id.textSelectedDir);
        btnSelectDir    = findViewById(R.id.btnSelectDir);
        btnGenerate     = findViewById(R.id.btnGenerate);
        cardStatus      = findViewById(R.id.cardStatus);
        textStatus      = findViewById(R.id.textStatus);
        btnOpenFile     = findViewById(R.id.btnOpenFile);
        progressBar     = findViewById(R.id.progressBar);
    }

    private void restoreSavedDirectory() {
        selectedDirUri = prefs.getString(KEY_DIR_URI, null);
        String name    = prefs.getString(KEY_DIR_NAME, null);

        if (selectedDirUri != null && name != null) {
            textSelectedDir.setText(name);
        }
    }

    private void setupListeners() {
        // Character counter
        editTextWord.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                int len = s.length();
                textCharCount.setText(len + " / 30");
                // Hide stale status on new input
                cardStatus.setVisibility(View.GONE);
            }
        });

        btnSelectDir.setOnClickListener(v -> dirPickerLauncher.launch(null));

        btnGenerate.setOnClickListener(v -> {
            String word = editTextWord.getText() != null
                    ? editTextWord.getText().toString().trim()
                    : "";

            if (word.isEmpty()) {
                showError(getString(R.string.error_empty_word));
                return;
            }

            if (!isRussianWord(word)) {
                showError(getString(R.string.error_non_russian));
                return;
            }

            if (selectedDirUri == null) {
                showError(getString(R.string.error_no_dir));
                return;
            }

            generateImage(word);
        });

        btnOpenFile.setOnClickListener(v -> {
            if (lastSavedUri != null) openFile(lastSavedUri);
        });
    }

    /** Only Cyrillic letters (а–я + ё, А–Я + Ё) and spaces are allowed. */
    private boolean isRussianWord(String word) {
        for (char c : word.toCharArray()) {
            if (c == ' ') continue; // space → s.png
            if (!Character.UnicodeBlock.of(c).equals(Character.UnicodeBlock.CYRILLIC)) {
                return false;
            }
            char lower = Character.toLowerCase(c);
            if (lower < 'а' || lower > 'я') {
                if (lower != 'ё') return false;
            }
        }
        return true;
    }

    private void generateImage(String word) {
        setUiBusy(true);
        cardStatus.setVisibility(View.GONE);
        btnOpenFile.setVisibility(View.GONE);
        lastSavedUri = null;

        executor.execute(() -> {
            try {
                // 1. Render to a temp file inside app cache
                File tempFile = new File(getCacheDir(), word + ".png");
                WordRenderer renderer = new WordRenderer(this);
                renderer.render(word, tempFile);

                // 2. Copy from temp to user-chosen SAF directory
                Uri dirUri = Uri.parse(selectedDirUri);
                DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                if (dir == null || !dir.canWrite()) {
                    throw new IOException("Нет прав на запись в выбранный каталог");
                }

                String fileName = (word + ".png").replace(' ', '_');
                // Delete existing file with the same name if present
                DocumentFile existing = dir.findFile(fileName);
                if (existing != null) existing.delete();

                DocumentFile newFile = dir.createFile("image/png", fileName);
                if (newFile == null) {
                    throw new IOException("Не удалось создать файл в выбранном каталоге");
                }

                try (InputStream in  = new FileInputStream(tempFile);
                     OutputStream out = getContentResolver().openOutputStream(newFile.getUri())) {
                    if (out == null) throw new IOException("Нет доступа к файлу");
                    byte[] buf = new byte[8192];
                    int    n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }

                tempFile.delete();
                lastSavedUri = newFile.getUri();

                handler.post(() -> {
                    setUiBusy(false);
                    showSuccess(getString(R.string.status_success, fileName));
                });

            } catch (WordRenderer.WordRenderException e) {
                handler.post(() -> {
                    setUiBusy(false);
                    showError(getString(R.string.status_error, e.getMessage()));
                });
            } catch (IOException e) {
                handler.post(() -> {
                    setUiBusy(false);
                    showError(getString(R.string.status_error, e.getMessage()));
                });
            }
        });
    }

    private void setUiBusy(boolean busy) {
        btnGenerate.setEnabled(!busy);
        btnSelectDir.setEnabled(!busy);
        editTextWord.setEnabled(!busy);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void showSuccess(String message) {
        textStatus.setText(message);
        textStatus.setTextColor(getColor(R.color.success));
        cardStatus.setVisibility(View.VISIBLE);
        btnOpenFile.setVisibility(View.VISIBLE);
    }

    private void showError(String message) {
        textStatus.setText(message);
        textStatus.setTextColor(getColor(R.color.error));
        cardStatus.setVisibility(View.VISIBLE);
        btnOpenFile.setVisibility(View.GONE);
    }

    private void openFile(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "image/png");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Нет приложения для открытия PNG", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
