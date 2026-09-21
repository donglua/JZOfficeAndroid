package cn.jingzhuan.lib.office;

import static cn.jingzhuan.lib.office.PptxRenderingChecks.check;
import static cn.jingzhuan.lib.office.PptxRenderingChecks.near;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

final class PptxGeometryChecks {
    private PptxGeometryChecks() { }

    static String run(Context context, String nativeJson) throws Exception {
        JSONObject root = new JSONObject(nativeJson);
        check(root.getInt("schemaVersion") == 9, "Native PPTX uses schema9");
        for (int version : new int[] {8, 10}) {
            reject(new JSONObject(nativeJson).put("schemaVersion", version),
                "Schema " + version, "Unsupported core model version");
        }
        JSONObject missing = new JSONObject(nativeJson);
        missing.remove("schemaVersion");
        reject(missing, "Missing schema version");

        JSONObject gradient = new JSONObject()
            .put("colors", new JSONArray("[4294901760,4278190335]"))
            .put("positions", new JSONArray("[0,1]"))
            .put("points", new JSONArray("[0,30,100,30]"))
            .put("transform", JSONObject.NULL);
        element(root).put("fillGradient", gradient).put("imageBounds", JSONObject.NULL);
        OfficeDocument.Element ordinary = decodedElement(root);
        check(Arrays.equals(ordinary.fillGradient.points, new float[] {0, 30, 100, 30}),
            "Gradient endpoints decode in local points");
        check(ordinary.fillGradient.transform == null && ordinary.imageBounds == null,
            "Ordinary gradient and uncropped image retain null geometry overrides");
        String baseline = root.toString();
        invalidArray(baseline, false, "transform", "[1,0,0,1,0,0]", 100001);
        invalidArray(baseline, false, "imageBounds", "[0,0,40,40]", 100000016);
        invalidArray(baseline, true, "points", "[0,30,100,30]", 200001);
        invalidArray(baseline, true, "transform", "[1,0,0,1,0,0]", 0);
        for (boolean inGradient : new boolean[] {false, true}) {
            String field = inGradient ? "points" : "transform";
            missing = new JSONObject(baseline);
            target(missing, inGradient).remove(field);
            reject(missing, "Missing required " + (inGradient ? "gradient " : "element ") + field);
            missing = new JSONObject(baseline);
            target(missing, inGradient).put(field, JSONObject.NULL);
            reject(missing, "Null required " + (inGradient ? "gradient " : "element ") + field);
        }
        for (String bounds : new String[] {"[1,0,0,1]", "[0,1,1,0]"}) {
            JSONObject invalid = new JSONObject(baseline);
            element(invalid).put("imageBounds", new JSONArray(bounds));
            reject(invalid, "Reversed image destination " + bounds);
        }
        acceptedLimits(baseline);
        nativeCrop(context);
        return "PASS schema9 geometry arrays, finite float limits, required endpoints and ordered image bounds\n"
            + "PASS native PPTX asymmetric crop destination and uncropped image bounds\n";
    }

    private static void invalidArray(String baseline, boolean inGradient, String field,
                                     String valid, double outsideLimit) throws Exception {
        int length = new JSONArray(valid).length();
        for (int size : new int[] {0, length - 1, length + 1}) {
            JSONArray values = new JSONArray();
            for (int i = 0; i < size; i++) values.put(0);
            JSONObject invalid = new JSONObject(baseline);
            target(invalid, inGradient).put(field, values);
            reject(invalid, field + " length " + size);
        }
        for (Object wrongType : new Object[] {"not-an-array", new JSONObject(), 1}) {
            JSONObject invalid = new JSONObject(baseline);
            target(invalid, inGradient).put(field, wrongType);
            reject(invalid, field + " invalid array type");
        }
        Object[] invalidNumbers = {"NaN", "Infinity", "-Infinity", 3.5e38, -3.5e38,
            1e100, "invalid", JSONObject.NULL};
        for (int index = 0; index < length; index++) {
            for (Object value : invalidNumbers) {
                JSONObject invalid = new JSONObject(baseline);
                target(invalid, inGradient).put(field, new JSONArray(valid).put(index, value));
                reject(invalid, field + " invalid coefficient " + index + ": " + value);
            }
            if (outsideLimit > 0) for (double value : new double[] {outsideLimit, -outsideLimit}) {
                JSONObject invalid = new JSONObject(baseline);
                target(invalid, inGradient).put(field, new JSONArray(valid).put(index, value));
                reject(invalid, field + " out-of-range coefficient " + index);
            }
        }
    }

    private static void acceptedLimits(String baseline) throws Exception {
        JSONObject root = new JSONObject(baseline);
        element(root).put("transform", new JSONArray("[-100000,0,0,100000,100000,-100000]"));
        element(root).put("imageBounds", new JSONArray("[-100000000,-100000000,100000000,100000000]"));
        target(root, true).put("points", new JSONArray("[-200000,-200000,200000,200000]"));
        target(root, true).put("transform", new JSONArray()
            .put(100000000).put(0).put(0).put(-100000000).put((double) Float.MAX_VALUE).put((double) -Float.MAX_VALUE));
        OfficeDocument.Element decoded = decodedElement(root);
        check(Arrays.equals(decoded.transform, new float[] {-100000, 0, 0, 100000, 100000, -100000}),
            "Element transform includes its exact limits");
        check(Arrays.equals(decoded.imageBounds, new float[] {-100000000, -100000000, 100000000, 100000000}),
            "Image destination includes its exact limits");
        check(Arrays.equals(decoded.fillGradient.points, new float[] {-200000, -200000, 200000, 200000}),
            "Gradient endpoints include their exact limits");
        check(Arrays.equals(decoded.fillGradient.transform,
            new float[] {100000000, 0, 0, -100000000, Float.MAX_VALUE, -Float.MAX_VALUE}),
            "Shader inverse retains finite float range without an absolute bound");
        element(root).put("imageBounds", new JSONArray("[4,5,4,5]"));
        check(Arrays.equals(decodedElement(root).imageBounds, new float[] {4, 5, 4, 5}),
            "Zero-area ordered image destination is accepted");
    }

    private static void nativeCrop(Context context) throws Exception {
        File fixture = LayoutFixtures.pptx(context.getCacheDir());
        try (OfficePackage source = OfficePackage.open(context, Uri.fromFile(fixture))) {
            int images = 0;
            for (OfficeDocument.Element image : source.document.pages.get(0).elements) {
                if (image.type != OfficeDocument.Type.IMAGE) continue;
                if (images++ == 0) check(image.imageBounds == null, "Native uncropped image has no destination override");
                else {
                    float[] expected = {-72, 0, 72, 144};
                    check(image.imageBounds != null && image.imageBounds.length == 4, "Native crop has four bounds");
                    for (int i = 0; i < 4; i++) near(image.imageBounds[i], expected[i], 0.001f, "Native crop bound " + i);
                }
            }
            check(images == 2, "Native crop fixture exercises both image modes");
        } finally { check(fixture.delete(), "Native crop fixture removed"); }
    }

    private static JSONObject element(JSONObject root) throws Exception {
        return root.getJSONArray("pages").getJSONObject(0).getJSONArray("elements").getJSONObject(0);
    }

    private static JSONObject target(JSONObject root, boolean inGradient) throws Exception {
        JSONObject element = element(root);
        return inGradient ? element.getJSONObject("fillGradient") : element;
    }

    private static OfficeDocument.Element decodedElement(JSONObject root) throws Exception {
        return DocumentDecoder.decode(root.toString()).pages.get(0).elements.get(0);
    }

    private static void reject(JSONObject root, String label) throws Exception {
        reject(root, label, "Invalid core display model");
    }

    private static void reject(JSONObject root, String label, String message) throws Exception {
        try { DocumentDecoder.decode(root.toString()); }
        catch (IOException expected) {
            check(message.equals(expected.getMessage()), label + " reports " + message);
            return;
        }
        throw new AssertionError(label + " was accepted");
    }
}
