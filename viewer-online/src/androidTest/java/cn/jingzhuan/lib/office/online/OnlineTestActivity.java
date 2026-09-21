package cn.jingzhuan.lib.office.online;

import android.app.Activity;
import android.os.Bundle;
import cn.jingzhuan.lib.office.OfficePreviewView;

public final class OnlineTestActivity extends Activity {
    OfficePreviewView preview;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preview = new OfficePreviewView(this);
        setContentView(preview);
    }

    @Override public void onDestroy() {
        preview.clear();
        super.onDestroy();
    }
}
