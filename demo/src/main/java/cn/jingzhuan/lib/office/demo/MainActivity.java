package cn.jingzhuan.lib.office.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.jingzhuan.lib.office.OfficePreviewView;

public final class MainActivity extends Activity {
    private static final int PICK_DOCUMENT = 20;
    private OfficePreviewView preview;
    private TextView title, status;
    private ImageButton previous, next;
    private OfficePreviewView.Info loadedInfo;
    private Uri current;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xfffafbfc);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL); toolbar.setPadding(dp(12), 0, dp(8), 0);
        title = new TextView(this); title.setText("JZ Office"); title.setTextSize(18); title.setTextColor(0xff25282d); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1)); title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(button(R.drawable.ic_office_open, "Open document", v -> pick()));
        toolbar.addView(button(R.drawable.ic_office_fit, "Reset zoom", v -> preview.resetZoom()));
        previous = button(R.drawable.ic_office_previous, "Previous slide", v -> preview.jumpToPage(preview.getCurrentPage() - 1));
        next = button(R.drawable.ic_office_next, "Next slide", v -> preview.jumpToPage(preview.getCurrentPage() + 1));
        toolbar.addView(previous); toolbar.addView(next);
        root.addView(toolbar);
        status = new TextView(this); status.setTextSize(12); status.setTextColor(0xff58616b); status.setPadding(dp(16), dp(4), dp(16), dp(8));
        status.setText("DOCX / PPTX"); root.addView(status);
        preview = new OfficePreviewView(this); root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        preview.setOnPageChangeListener((page, count) -> updatePageState());
        setContentView(root);
        if (saved != null && saved.getString("uri") != null) open(Uri.parse(saved.getString("uri")));
        else if (getIntent().getData() != null) open(getIntent().getData());
    }

    private ImageButton button(int icon, String label, View.OnClickListener action) {
        ImageButton button = new ImageButton(this); button.setImageResource(icon); button.setContentDescription(label);
        button.setImageTintList(getColorStateList(R.color.office_toolbar_icon));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        android.util.TypedValue value = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true); button.setBackgroundResource(value.resourceId);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); button.setOnClickListener(action);
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
        return button;
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        });
        startActivityForResult(intent, PICK_DOCUMENT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_DOCUMENT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
        }
        open(uri);
    }

    private void open(Uri uri) {
        loadedInfo = null; updatePageState();
        current = uri; status.setText("Loading..."); status.setOnClickListener(null);
        title.setText("Document");
        new Thread(() -> {
            String name = "Document";
            try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            } catch (RuntimeException ignored) { }
            final String displayName = name;
            runOnUiThread(() -> { if (!isDestroyed() && uri.equals(current)) title.setText(displayName); });
        }, "document-name").start();
        preview.open(uri, new OfficePreviewView.Listener() {
            @Override public void onLoaded(OfficePreviewView.Info info) {
                loadedInfo = info; updatePageState();
                if (!info.warnings.isEmpty()) status.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this).setTitle("Preview notices").setMessage(android.text.TextUtils.join("\n\n", info.warnings)).setPositiveButton(android.R.string.ok, null).show());
            }
            @Override public void onError(Exception error) { status.setText(error.getMessage()); }
        });
    }

    private void updatePageState() {
        boolean slides = loadedInfo != null && loadedInfo.format.equals("PPTX");
        previous.setVisibility(slides ? View.VISIBLE : View.GONE);
        next.setVisibility(slides ? View.VISIBLE : View.GONE);
        if (loadedInfo == null) return;
        int page = preview.getCurrentPage();
        previous.setEnabled(page > 1);
        next.setEnabled(page > 0 && page < preview.getPageCount());
        status.setText(loadedInfo.format + (slides ? "  |  " + page + " / " + preview.getPageCount() : "  |  Continuous")
            + (loadedInfo.warnings.isEmpty() ? "" : "  |  " + loadedInfo.warnings.size() + " notices"));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getData() != null) open(intent.getData());
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); if (current != null) out.putString("uri", current.toString());
    }
    @Override protected void onDestroy() {
        preview.clear();
        super.onDestroy();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
