package com.subhub.app.people;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.subhub.app.R;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.studio.PackCoverArtwork;
import com.subhub.app.util.AsyncUiScope;

/** Active contract identity and bounded local cover artwork share one presentation. */
public final class ContractHeader {
    private ContractHeader() {}

    public static void add(LinearLayout parent, PersonSnapshot person, String label, AsyncUiScope data) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = PersonUi.column(context);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        PersonUi.text(copy, label, false);
        PersonUi.text(copy, person.contractName, true);
        TextView version = PersonUi.text(copy, "", false);
        version.setTag("contract_version:" + person.id.value());
        version.setVisibility(View.GONE);
        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.setVisibility(View.GONE);
        cover.setTag("contract_cover:" + person.id.value());
        int edge = PersonUi.dp(context, 88);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(edge, edge);
        size.setMarginStart(PersonUi.dp(context, 16));
        row.addView(cover, size);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (!person.id.isLocal()) return;
        Context app = context.getApplicationContext();
        String expectedName = person.contractName;
        int bound = Math.min(512, edge);
        data.load("contract-cover", () -> {
            SubHubPackManager packs = new SubHubPackManager(app);
            String id = packs.activePackId();
            com.subhub.app.pack.SubHubPack pack = packs.activePackOverview();
            if (pack == null || !pack.getName().equals(expectedName)) return new Cover(null, id, "");
            Bitmap bitmap = PackCoverArtwork.decode(packs.readLibraryCover(id), bound);
            Cover result = new Cover(bitmap, id, pack.getPackVersion());
            if (!java.util.Objects.equals(id, packs.activePackId())) result.close();
            return result;
        }, result -> {
            if (!result.version.isEmpty() && java.util.Objects.equals(result.packId,
                    new SubHubPackManager(app).activePackId())) {
                version.setText(context.getString(R.string.person_contract_version, result.version));
                version.setVisibility(View.VISIBLE);
            }
            if (result.bitmap != null && java.util.Objects.equals(result.packId,
                    new SubHubPackManager(app).activePackId())) {
                cover.setImageBitmap(result.bitmap);
                cover.setVisibility(View.VISIBLE);
                // ImageView retains delivered artwork; only never-displayed results are recycled.
                result.bitmap = null;
            } else result.close();
        }, failure -> cover.setVisibility(View.GONE));
    }

    private static final class Cover implements AutoCloseable {
        Bitmap bitmap;
        final String packId, version;
        Cover(Bitmap bitmap, String packId, String version) {
            this.bitmap = bitmap; this.packId = packId; this.version = version;
        }
        @Override public void close() { if (bitmap != null) { bitmap.recycle(); bitmap = null; } }
    }
}
