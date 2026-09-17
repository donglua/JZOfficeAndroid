package cn.jingzhuan.lib.office;

import java.io.IOException;

final class NativeCore {
    static { System.loadLibrary("jz_office"); }
    static native String parse(String cachedPath) throws IOException;
}
