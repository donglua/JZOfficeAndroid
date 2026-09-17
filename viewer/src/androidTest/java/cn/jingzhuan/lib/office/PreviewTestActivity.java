package cn.jingzhuan.lib.office;

import android.app.Activity;
import android.os.Bundle;

public final class PreviewTestActivity extends Activity {
    OfficePreviewView preview;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preview = new OfficePreviewView(this);
        setContentView(preview);
    }
    @Override protected void onDestroy() {
        preview.clear();
        super.onDestroy();
    }
}
