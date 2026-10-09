package com.subhub.app.detection.text;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.subhub.app.detection.Detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ultra-only bundled Latin OCR feeding the same local text-risk classifier as Accessibility. */
public final class OcrTextSmutDetector implements AutoCloseable {
    public interface Callback {
        void onComplete(List<Detection> detections);
        void onFailure(Exception error);
        /** Input pixels are no longer in use; must be lightweight and independent of worker life. */
        default void onRecognitionFinished() { }
    }

    private final TextRecognizer recognizer;
    private final TextSmutDetectionFactory factory;
    private final AtomicBoolean warmupRequested = new AtomicBoolean();
    private final OcrRecognizerLease lifetime;

    public OcrTextSmutDetector(SmutTextClassifier classifier) {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        lifetime = new OcrRecognizerLease(recognizer::close);
        factory = new TextSmutDetectionFactory(classifier);
    }

    /** Starts bundled model initialization without delaying the first real screenshot. */
    public void warmUp(Executor callbackExecutor) {
        if (callbackExecutor == null || !warmupRequested.compareAndSet(false, true)) return;
        OcrRecognizerLease.Operation operation = lifetime.begin();
        if (operation == null) return;
        Bitmap warmup = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        warmup.eraseColor(android.graphics.Color.WHITE);
        try {
            // Recycling this tiny fixture requires no classifier work or service-owned executor.
            // Completion also runs for cancellation, including close during model warmup.
            recognizer.process(InputImage.fromBitmap(warmup, 0))
                    .addOnCompleteListener(Runnable::run, ignored -> {
                        try { warmup.recycle(); }
                        finally { operation.close(); }
                    });
        } catch (RuntimeException error) {
            warmup.recycle();
            operation.close();
            throw error;
        }
    }

    public void detect(
            Bitmap bitmap,
            TextSmutConfig config,
            int sourceWidth,
            int sourceHeight,
            Executor callbackExecutor,
            Callback callback) {
        if (bitmap == null || bitmap.isRecycled() || config == null || !config.isEnabled()) {
            callback.onComplete(Collections.emptyList());
            return;
        }
        int ocrWidth = bitmap.getWidth();
        int ocrHeight = bitmap.getHeight();
        OcrRecognizerLease.Operation operation = lifetime.begin();
        if (operation == null) {
            callback.onFailure(new IllegalStateException("OCR detector is closed"));
            return;
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnCompleteListener(Runnable::run, task -> {
                    try { callback.onRecognitionFinished(); }
                    finally { operation.close(); }
                    OcrCallbackDispatcher.dispatch(
                        callbackExecutor, () -> {
                            if (!task.isSuccessful()) {
                                Exception error = task.isCanceled()
                                        ? new CancellationException("OCR task cancelled") : task.getException();
                                callback.onFailure(error != null ? error
                                        : new IllegalStateException("OCR task completed without a result"));
                                return;
                            }
                            List<Detection> mapped;
                            try {
                                mapped = mapDetections(task.getResult(), config,
                                        ocrWidth, ocrHeight, sourceWidth, sourceHeight);
                            } catch (RuntimeException error) {
                                callback.onFailure(error);
                                return;
                            }
                            callback.onComplete(mapped);
                        }, callback::onFailure);
                });
        } catch (RuntimeException error) {
            operation.close();
            throw error;
        }
    }

    private List<Detection> mapDetections(
            Text result,
            TextSmutConfig config,
            int ocrWidth,
            int ocrHeight,
            int sourceWidth,
            int sourceHeight) {
        List<Detection> ocrDetections = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            List<OcrTextLayout.Line> lines = new ArrayList<>();
            for (Text.Line line : block.getLines()) {
                if (line.getBoundingBox() != null) {
                    lines.add(new OcrTextLayout.Line(line.getText(), line.getBoundingBox()));
                }
            }
            if (!lines.isEmpty()) {
                ocrDetections.addAll(OcrTextLayout.classify(
                        lines, config, factory, ocrWidth, ocrHeight));
            } else {
                Detection blockDetection = create(block.getText(), block.getBoundingBox(),
                        config, ocrWidth, ocrHeight);
                if (blockDetection != null) ocrDetections.add(blockDetection);
            }
        }
        List<Detection> fused = DetectionFusion.merge(
                Collections.emptyList(), ocrDetections);
        return TextDetectionCoordinateMapper.screenToCapture(
                fused, ocrWidth, ocrHeight, sourceWidth, sourceHeight);
    }

    private Detection create(
            String text,
            Rect bounds,
            TextSmutConfig config,
            int width,
            int height) {
        if (bounds == null) return null;
        SmutTextClassifier.Match match = factory.classify(text, config, true);
        return factory.create(text, bounds, match, width, height, "TEXT_SMUT_OCR_");
    }

    @Override public void close() {
        lifetime.close();
    }
}
