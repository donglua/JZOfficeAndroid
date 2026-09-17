package cn.jingzhuan.lib.office.demo;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import cn.jingzhuan.lib.office.OfficePreviewView;

final class SheetTabs extends HorizontalScrollView {
    private final LinearLayout tabs;

    SheetTabs(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setBackgroundColor(0xfff4f5f7);
        tabs = new LinearLayout(context);
        addView(tabs);
        setVisibility(GONE);
    }

    void bind(List<String> names, OfficePreviewView preview) {
        tabs.removeAllViews();
        setVisibility(names.isEmpty() ? GONE : VISIBLE);
        for (int i = 0; i < names.size(); i++) {
            final int number = i + 1;
            TextView tab = new TextView(getContext());
            tab.setText(names.get(i)); tab.setTextSize(14); tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true); tab.setEllipsize(TextUtils.TruncateAt.END);
            tab.setMaxWidth(dp(180)); tab.setMinWidth(dp(72)); tab.setPadding(dp(16), 0, dp(16), 0);
            tab.setContentDescription(names.get(i)); tab.setFocusable(true);
            tab.setOnClickListener(v -> preview.selectSheet(number));
            tabs.addView(tab, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        select(preview.getCurrentPage());
    }

    void select(int number) {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView tab = (TextView) tabs.getChildAt(i);
            boolean active = i + 1 == number;
            tab.setSelected(active); tab.setTextColor(active ? 0xff176b43 : 0xff4a5159);
            tab.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tab.setBackgroundColor(active ? 0xffe0efe6 : 0xfff4f5f7);
            if (active) tab.post(() -> smoothScrollTo(Math.max(0, tab.getLeft() - dp(16)), 0));
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
