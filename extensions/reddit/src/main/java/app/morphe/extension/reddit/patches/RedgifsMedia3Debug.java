/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Diagnostic-only bridge used to correlate RedGIFs DataSpecs with the exact
 * ProgressiveMediaPeriod that later publishes sample queues / TrackGroups.
 */
@SuppressWarnings("unused")
public final class RedgifsMedia3Debug {
    private static final String TAG = "MorpheRedgifs";

    private static final Map<Object, String> PERIOD_URIS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, String> LAST_PERIOD_STATE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RedgifsMedia3Debug() {
    }

    public static void onDataSpec(Object mediaPeriod, Object dataSpec) {
        if (mediaPeriod == null || dataSpec == null) return;
        try {
            Object uriValue = field(dataSpec.getClass(), "a").get(dataSpec);
            if (!(uriValue instanceof Uri)) return;
            Uri uri = (Uri) uriValue;
            if (!isRedgifsMedia(uri)) return;

            String value = uri.toString();
            String previous = PERIOD_URIS.put(mediaPeriod, value);
            if (!value.equals(previous)) {
                Log.d(TAG, "PERIOD_BIND period=" + identity(mediaPeriod) + " uri=" + value);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Log.d(TAG, "PERIOD_BIND error=" + exception.getClass().getSimpleName());
        }
    }

    public static void onPeriodState(Object mediaPeriod) {
        if (mediaPeriod == null) return;
        String uri = PERIOD_URIS.get(mediaPeriod);
        if (uri == null) return;

        String queues;
        String groups;
        try {
            queues = sampleQueueMimes(mediaPeriod);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            queues = "<error:" + exception.getClass().getSimpleName() + ">";
        }
        try {
            groups = trackGroupMimes(mediaPeriod);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            groups = "<error:" + exception.getClass().getSimpleName() + ">";
        }

        String state = "queues=" + queues + " groups=" + groups;
        String previous = LAST_PERIOD_STATE.put(mediaPeriod, state);
        if (!state.equals(previous)) {
            Log.d(TAG, "PERIOD_STATE period=" + identity(mediaPeriod) +
                    " uri=" + uri + " " + state);
        }
    }

    private static String sampleQueueMimes(Object mediaPeriod)
            throws ReflectiveOperationException {
        Object queues = field(mediaPeriod.getClass(), "l0").get(mediaPeriod);
        if (queues == null || !queues.getClass().isArray()) return "null";

        int count = Array.getLength(queues);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i != 0) out.append(',');
            Object queue = Array.get(queues, i);
            if (queue == null) {
                out.append("null");
                continue;
            }
            Method formatMethod = method(queue.getClass(), "w");
            Object format = formatMethod.invoke(queue);
            out.append(mime(format));
        }
        return out.append(']').toString();
    }

    private static String trackGroupMimes(Object mediaPeriod)
            throws ReflectiveOperationException {
        Object state = field(mediaPeriod.getClass(), "r0").get(mediaPeriod);
        if (state == null) return "null";
        Object groups = field(state.getClass(), "a").get(state);
        if (groups == null) return "null";

        Field countField = field(groups.getClass(), "a");
        int count = countField.getInt(groups);
        Method getGroup = method(groups.getClass(), "a", int.class);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i != 0) out.append(',');
            Object group = getGroup.invoke(groups, i);
            if (group == null) {
                out.append("null");
                continue;
            }
            Object formats = field(group.getClass(), "d").get(group);
            out.append('[');
            if (formats != null && formats.getClass().isArray()) {
                int formatCount = Array.getLength(formats);
                for (int j = 0; j < formatCount; j++) {
                    if (j != 0) out.append('|');
                    out.append(mime(Array.get(formats, j)));
                }
            } else {
                out.append("null");
            }
            out.append(']');
        }
        return out.append(']').toString();
    }

    private static String mime(Object format) throws ReflectiveOperationException {
        if (format == null) return "null";
        Object value = field(format.getClass(), "n").get(format);
        return value == null ? "null" : String.valueOf(value);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method result = owner.getDeclaredMethod(name, parameterTypes);
        result.setAccessible(true);
        return result;
    }

    private static boolean isRedgifsMedia(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        String normalizedHost = host.toLowerCase(java.util.Locale.US);
        if (!("redgifs.com".equals(normalizedHost) || normalizedHost.endsWith(".redgifs.com"))) {
            return false;
        }
        if ("api.redgifs.com".equals(normalizedHost) || "www.redgifs.com".equals(normalizedHost)) {
            return false;
        }
        String path = uri.getPath();
        return path != null && path.toLowerCase(java.util.Locale.US).endsWith(".mp4");
    }

    private static String identity(Object value) {
        return value.getClass().getName() + "@" +
                Integer.toHexString(System.identityHashCode(value));
    }
}
