package com.subhub.app.onboarding;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.subhub.app.R;

/** Synthetic, text-free diagrams use the same controls and plum surfaces as the native app. */
public final class OnboardingGraphic extends View {
    private final OnboardingPage.Scene scene;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int surface, raised, outline, accent, ink, muted;
    private final RectF rect = new RectF();

    public OnboardingGraphic(Context context, OnboardingPage.Scene scene) {
        super(context);
        this.scene = scene;
        surface = context.getColor(R.color.surface_card);
        raised = context.getColor(R.color.surface_card_raised);
        outline = context.getColor(R.color.outline_subtle);
        accent = context.getColor(R.color.accent_text);
        ink = context.getColor(R.color.text_primary);
        muted = context.getColor(R.color.control_checked_surface);
        setId(R.id.onboarding_graphic);
        setTag("onboarding_graphic:" + scene.name());
        int description = scene == OnboardingPage.Scene.CONNECT_DOM || scene == OnboardingPage.Scene.CONNECT_SUB
                ? R.string.onboarding_graphic_connect : scene == OnboardingPage.Scene.HARDCORE
                ? R.string.onboarding_graphic_hardcore : scene == OnboardingPage.Scene.CONTRACTS
                || scene == OnboardingPage.Scene.LOCAL_FEATURES ? R.string.onboarding_graphic_contract
                : scene == OnboardingPage.Scene.SWITCH_ROLES ? R.string.onboarding_graphic_roles
                : scene == OnboardingPage.Scene.SECURITY ? R.string.onboarding_graphic_security
                : scene == OnboardingPage.Scene.PERMISSIONS ? R.string.onboarding_graphic_permissions
                : scene == OnboardingPage.Scene.WALLET_RULES ? R.string.dom_onboarding_wallet_title
                : R.string.onboarding_graphic_tasks;
        setContentDescription(context.getString(description));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = Math.round(Math.min(width * .60f, 230 * getResources().getDisplayMetrics().density));
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 360f, getHeight() / 216f);
        int saved = canvas.save();
        canvas.translate((getWidth() - 360 * scale) / 2f, (getHeight() - 216 * scale) / 2f);
        canvas.scale(scale, scale);
        rounded(canvas, 1, 1, 358, 214, 24, surface, false);
        rounded(canvas, 1, 1, 358, 214, 24, outline, true);
        switch (scene) {
            case CONNECT_DOM: case CONNECT_SUB: connection(canvas, false); break;
            case SWITCH_ROLES: connection(canvas, true); break;
            case SUB_TASKS: submission(canvas); break;
            case CREATE_TASK: creation(canvas); break;
            case REVIEW_TASK: review(canvas); break;
            case CONTRACTS: case LOCAL_FEATURES: contracts(canvas); break;
            case HARDCORE: hardcore(canvas); break;
            case CENSOR_SETTINGS: contracts(canvas); break;
            case WALLET_RULES: wallet(canvas); break;
            case SECURITY: security(canvas); break;
            case PERMISSIONS: permissions(canvas); break;
        }
        canvas.restoreToCount(saved);
    }

    private void connection(Canvas canvas, boolean both) {
        phone(canvas, 26, 20, 106, 176, true);
        phone(canvas, 228, 20, 106, 176, false);
        line(canvas, 141, 109, 219, 109, accent, 2);
        if (both) {
            arrow(canvas, 144, 95, 213, 95, accent);
            arrow(canvas, 216, 124, 147, 124, accent);
            circle(canvas, 180, 109, 12, surface, false);
        } else {
            circle(canvas, 180, 109, 22, muted, false);
            circle(canvas, 180, 109, 22, accent, true);
            lock(canvas, 180, 108, .57f);
        }
        circle(canvas, 152, 164, 3, accent, false);
        circle(canvas, 180, 164, 3, outline, false);
        circle(canvas, 208, 164, 3, accent, false);
    }

    private void phone(Canvas canvas, float x, float y, float width, float height, boolean dom) {
        rounded(canvas, x, y, width, height, 18, raised, false);
        rounded(canvas, x, y, width, height, 18, accent, true);
        rounded(canvas, x + width / 2 - 14, y + 7, 28, 3, 2, outline, false);
        icon(canvas, dom ? R.drawable.ic_nav_people : R.drawable.ic_nav_censor,
                x + width / 2 - 16, y + 24, 32, accent);
        for (int i = 0; i < 3; i++) {
            rounded(canvas, x + 12, y + 68 + i * 25, width - 24, 18, 6, surface, false);
            circle(canvas, x + 23, y + 77 + i * 25, 4, i == 0 ? accent : outline, false);
            line(canvas, x + 35, y + 77 + i * 25, x + width - 22, y + 77 + i * 25, outline, 3);
        }
        rounded(canvas, x + width / 2 - 18, y + height - 13, 36, 3, 2, outline, false);
    }

    private void submission(Canvas canvas) {
        taskCard(canvas, 27, 20, 242, 154, false);
        rounded(canvas, 209, 113, 125, 80, 14, muted, false);
        rounded(canvas, 209, 113, 125, 80, 14, accent, true);
        icon(canvas, R.drawable.ic_nav_tasks, 224, 126, 24, accent);
        line(canvas, 260, 134, 316, 134, ink, 4);
        line(canvas, 225, 157, 316, 157, outline, 4);
        arrow(canvas, 234, 177, 313, 177, accent);
    }

    private void creation(Canvas canvas) {
        taskCard(canvas, 38, 20, 260, 176, true);
        circle(canvas, 290, 161, 29, muted, false);
        circle(canvas, 290, 161, 29, accent, true);
        line(canvas, 278, 161, 302, 161, accent, 3);
        line(canvas, 290, 149, 290, 173, accent, 3);
    }

    private void taskCard(Canvas canvas, float x, float y, float w, float h, boolean authoring) {
        rounded(canvas, x, y, w, h, 18, raised, false);
        rounded(canvas, x, y, w, h, 18, outline, true);
        icon(canvas, R.drawable.ic_nav_tasks, x + 17, y + 16, 27, accent);
        line(canvas, x + 57, y + 26, x + w - 25, y + 26, ink, 5);
        line(canvas, x + 57, y + 39, x + w - 67, y + 39, outline, 3);
        for (int i = 0; i < 3; i++) {
            circle(canvas, x + 26, y + 72 + i * 24, 6, i == 0 && !authoring ? accent : outline, true);
            if (i == 0 && !authoring) check(canvas, x + 26, y + 72, .55f);
            line(canvas, x + 44, y + 72 + i * 24, x + w - 28 - i * 12,
                    y + 72 + i * 24, outline, 4);
        }
    }

    private void review(Canvas canvas) {
        rounded(canvas, 27, 50, 191, 142, 16, raised, false);
        rounded(canvas, 27, 50, 191, 142, 16, outline, true);
        rounded(canvas, 41, 65, 162, 83, 10, muted, false);
        Path mountain = new Path(); mountain.moveTo(51, 132); mountain.lineTo(91, 90);
        mountain.lineTo(124, 120); mountain.lineTo(154, 102); mountain.lineTo(193, 136);
        stroke(canvas, mountain, accent, 2);
        circle(canvas, 172, 85, 7, accent, false);
        line(canvas, 44, 165, 191, 165, outline, 4);
        line(canvas, 44, 177, 156, 177, outline, 3);
        rounded(canvas, 230, 26, 103, 133, 16, muted, false);
        rounded(canvas, 230, 26, 103, 133, 16, accent, true);
        circle(canvas, 281, 67, 23, raised, false);
        check(canvas, 281, 67, 1.5f);
        line(canvas, 248, 110, 314, 110, accent, 5);
        line(canvas, 256, 127, 306, 127, outline, 3);
        arrow(canvas, 201, 174, 280, 174, accent);
    }

    private void contracts(Canvas canvas) {
        int[] icons = {R.drawable.ic_nav_censor, R.drawable.ic_nav_limits, R.drawable.ic_nav_money};
        for (int i = 0; i < 3; i++) {
            float x = 19 + i * 109;
            rounded(canvas, x, 26, 103, 164, 17, raised, false);
            rounded(canvas, x, 26, 103, 164, 17, outline, true);
            icon(canvas, icons[i], x + 31, 44, 40, accent);
            line(canvas, x + 19, 105, x + 83, 105, outline, 4);
            line(canvas, x + 19, 119, x + 68 - i * 7, 119, outline, 3);
            rounded(canvas, x + 21, 145, 61, 25, 13, muted, false);
            circle(canvas, x + 65 - i * 13, 157.5f, 8, accent, false);
        }
    }

    private void wallet(Canvas canvas) {
        rounded(canvas, 29, 26, 207, 164, 18, raised, false);
        rounded(canvas, 29, 26, 207, 164, 18, outline, true);
        icon(canvas, R.drawable.ic_nav_money, 47, 43, 35, accent);
        line(canvas, 97, 54, 215, 54, ink, 5);
        line(canvas, 97, 69, 181, 69, outline, 3);
        for (int i = 0; i < 3; i++) {
            circle(canvas, 56, 104 + i * 26, 5, accent, true);
            line(canvas, 73, 104 + i * 26, 182, 104 + i * 26, outline, 4);
            line(canvas, 198, 104 + i * 26, 215, 104 + i * 26, accent, 4);
        }
        rounded(canvas, 220, 89, 113, 104, 14, muted, false);
        rounded(canvas, 220, 89, 113, 104, 14, accent, true);
        icon(canvas, R.drawable.ic_nav_money, 259, 101, 34, accent);
        for (int i = 0; i < 2; i++) {
            rounded(canvas, 235, 148 + i * 23, 83, 8, 4, surface, false);
            rounded(canvas, 235, 148 + i * 23, 51 + i * 17, 8, 4, accent, false);
        }
    }

    private void hardcore(Canvas canvas) {
        line(canvas, 89, 133, 133, 110, outline, 2);
        line(canvas, 227, 110, 271, 133, outline, 2);
        Path shield = new Path();
        shield.moveTo(180, 21); shield.cubicTo(151, 37, 128, 41, 112, 42);
        shield.lineTo(116, 115); shield.cubicTo(120, 151, 148, 180, 180, 195);
        shield.cubicTo(212, 180, 240, 151, 244, 115); shield.lineTo(248, 42);
        shield.cubicTo(232, 41, 209, 37, 180, 21); shield.close();
        fill(canvas, shield, muted); stroke(canvas, shield, accent, 2.5f);
        lock(canvas, 180, 102, 1.4f);
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? 61 : 299;
            circle(canvas, x, 148, 27, raised, false);
            circle(canvas, x, 148, 27, outline, true);
            icon(canvas, i == 0 ? R.drawable.ic_nav_censor : R.drawable.ic_nav_contract,
                    x - 15, 133, 30, accent);
        }
    }

    private void security(Canvas canvas) {
        rounded(canvas, 89, 17, 182, 184, 22, raised, false);
        rounded(canvas, 89, 17, 182, 184, 22, outline, true);
        circle(canvas, 180, 77, 36, muted, false);
        lock(canvas, 180, 76, 1.05f);
        for (int i = 0; i < 4; i++) circle(canvas, 144 + i * 24, 137, 5, accent, false);
        rounded(canvas, 119, 163, 122, 17, 8, muted, false);
        circle(canvas, 70, 133, 16, muted, false);
        circle(canvas, 289, 67, 11, muted, false);
    }

    private void permissions(Canvas canvas) {
        int[] icons = {R.drawable.ic_nav_censor, R.drawable.ic_nav_people, R.drawable.ic_nav_tasks};
        for (int i = 0; i < 3; i++) {
            float y = 20 + i * 61;
            rounded(canvas, 33 + i * 6, y, 282 - i * 12, 50, 13, raised, false);
            rounded(canvas, 33 + i * 6, y, 282 - i * 12, 50, 13, outline, true);
            icon(canvas, icons[i], 47 + i * 6, y + 12, 26, accent);
            line(canvas, 88 + i * 6, y + 21, 232 - i * 7, y + 21, ink, 4);
            line(canvas, 88 + i * 6, y + 33, 201 - i * 7, y + 33, outline, 3);
            circle(canvas, 288 - i * 6, y + 25, 9, accent, true);
        }
    }

    private void lock(Canvas canvas, float x, float y, float scale) {
        int saved = canvas.save(); canvas.translate(x, y); canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.5f); paint.setColor(accent);
        rect.set(-10, -21, 10, 5); canvas.drawArc(rect, 180, 180, false, paint);
        rounded(canvas, -16, -7, 32, 27, 5, raised, false);
        rounded(canvas, -16, -7, 32, 27, 5, accent, true);
        circle(canvas, 0, 5, 3, accent, false); line(canvas, 0, 7, 0, 12, accent, 2);
        canvas.restoreToCount(saved);
    }

    private void check(Canvas canvas, float x, float y, float scale) {
        Path path = new Path(); path.moveTo(x - 6 * scale, y); path.lineTo(x - scale, y + 5 * scale);
        path.lineTo(x + 8 * scale, y - 6 * scale); stroke(canvas, path, accent, 2.5f);
    }

    private void arrow(Canvas canvas, float x1, float y1, float x2, float y2, int color) {
        line(canvas, x1, y1, x2, y2, color, 2);
        float direction = x2 > x1 ? 1 : -1;
        line(canvas, x2 - direction * 7, y2 - 5, x2, y2, color, 2);
        line(canvas, x2 - direction * 7, y2 + 5, x2, y2, color, 2);
    }

    private void icon(Canvas canvas, int resource, float x, float y, int size, int color) {
        Drawable drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(getContext(), resource);
        if (drawable == null) return;
        drawable = drawable.mutate(); drawable.setTint(color);
        drawable.setBounds(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size));
        drawable.draw(canvas);
    }

    private void rounded(Canvas canvas, float x, float y, float w, float h, float radius, int color, boolean stroke) {
        paint.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL); paint.setStrokeWidth(1.5f); paint.setColor(color);
        rect.set(x, y, x + w, y + h); canvas.drawRoundRect(rect, radius, radius, paint);
    }
    private void circle(Canvas canvas, float x, float y, float radius, int color, boolean stroke) {
        paint.setStyle(stroke ? Paint.Style.STROKE : Paint.Style.FILL); paint.setStrokeWidth(1.5f); paint.setColor(color);
        canvas.drawCircle(x, y, radius, paint);
    }
    private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(width);
        canvas.drawLine(x1, y1, x2, y2, paint);
    }
    private void stroke(Canvas canvas, Path path, int color, float width) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(width); canvas.drawPath(path, paint);
    }
    private void fill(Canvas canvas, Path path, int color) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); canvas.drawPath(path, paint);
    }
}
