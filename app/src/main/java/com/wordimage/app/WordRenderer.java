package com.wordimage.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a Russian word as a composite image from individual letter PNGs.
 * Algorithm mirrors write.py by RussianWordRenderer.
 */
public class WordRenderer {

    // Inter-letter gap (matches Python: spacing=5, +60 constant from original)
    private static final int SPACING = 65;
    private static final int PADDING = 10;

    private final Context context;

    public WordRenderer(Context context) {
        this.context = context;
    }

    /**
     * Returns the asset filename for a given Russian character.
     * Files are named after the Unicode escape of the character, e.g. #U0430.png for 'а'.
     * Special case: 'ё' (U+0451) has its own file #U0451.png; falls back to #U0435.png (е).
     */
    private String getLetterAssetName(char ch) {
        // Normalise to lower case
        ch = Character.toLowerCase(ch);

        // Check if asset exists for this char
        String name = String.format("#U%04x.png", (int) ch);

        // Verify existence in assets
        try {
            InputStream is = context.getAssets().open("letters/" + name);
            is.close();
            return name;
        } catch (IOException e) {
            // For ё fall back to е if not found
            if (ch == 'ё') {
                return String.format("#U%04x.png", (int) 'е');
            }
            return null; // not found
        }
    }

    /**
     * Builds the composite word image and saves it to outputFile.
     *
     * @param word       Russian word (mixed or lower case)
     * @param outputFile Destination file path (e.g. /sdcard/Download/слово.png)
     * @throws WordRenderException if any letter is missing or I/O fails
     */
    public void render(String word, File outputFile) throws WordRenderException, IOException {
        word = word.toLowerCase();

        List<Bitmap> bitmaps = new ArrayList<>();

        try {
            for (int i = 0; i < word.length(); i++) {
                char ch = word.charAt(i);
                String assetName = getLetterAssetName(ch);
                if (assetName == null) {
                    throw new WordRenderException("Нет картинки для буквы «" + ch + "»");
                }

                try (InputStream is = context.getAssets().open("letters/" + assetName)) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    if (bmp == null) {
                        throw new WordRenderException("Не удалось загрузить файл для «" + ch + "»");
                    }
                    bitmaps.add(bmp);
                }
            }

            if (bitmaps.isEmpty()) {
                throw new WordRenderException("Слово не содержит букв");
            }

            // Calculate canvas size
            int maxHeight = 0;
            int totalWidth = 0;
            for (Bitmap bmp : bitmaps) {
                if (bmp.getHeight() > maxHeight) maxHeight = bmp.getHeight();
                totalWidth += bmp.getWidth();
            }
            totalWidth += SPACING * (bitmaps.size() - 1);

            int canvasWidth  = totalWidth + 2 * PADDING;
            int canvasHeight = maxHeight + 2 * PADDING;

            Bitmap result = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.WHITE);

            int xOffset = PADDING;
            for (Bitmap bmp : bitmaps) {
                // Bottom-align letters (same as Python: y = maxHeight - padding - img.height)
                int yOffset = maxHeight - PADDING - bmp.getHeight();
                canvas.drawBitmap(bmp, xOffset, yOffset, null);
                xOffset += bmp.getWidth() + SPACING;
            }

            // Save PNG
            outputFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                result.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }

            result.recycle();

        } finally {
            for (Bitmap bmp : bitmaps) {
                if (!bmp.isRecycled()) bmp.recycle();
            }
        }
    }

    public static class WordRenderException extends Exception {
        public WordRenderException(String message) {
            super(message);
        }
    }
}
