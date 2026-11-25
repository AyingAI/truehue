package com.truehue.camera;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LensCatalog {
    private LensCatalog() {}

    static List<CameraInput.Lens> discover(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        Map<String, CameraCharacteristics> backs = new LinkedHashMap<>();
        String primary = null;
        for (String id : ids) {
            CameraCharacteristics info = manager.getCameraCharacteristics(id);
            if (!Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK)
                    .equals(info.get(CameraCharacteristics.LENS_FACING))) continue;
            if (!CameraInput.contains(info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue;
            backs.put(id, info);
            if (primary == null || (backs.get(primary).getPhysicalCameraIds().isEmpty()
                    && !info.getPhysicalCameraIds().isEmpty())) primary = id;
        }
        List<CameraInput.Lens> lenses = new ArrayList<>();
        if (primary == null) return lenses;
        CameraCharacteristics logical = backs.get(primary);
        float baseline = equivalent(logical);
        for (String id : logical.getPhysicalCameraIds()) {
            try {
                CameraCharacteristics info = manager.getCameraCharacteristics(id);
                boolean directlyOpenable = Arrays.asList(ids).contains(id);
                add(lenses, id, directlyOpenable ? id : primary, directlyOpenable ? null : id, info, baseline);
            } catch (CameraAccessException | IllegalArgumentException | SecurityException ignored) {
                // OEM may omit an individual physical camera from third-party applications.
            }
        }
        for (Map.Entry<String, CameraCharacteristics> item : backs.entrySet()) {
            String id = item.getKey();
            if (logical.getPhysicalCameraIds().contains(id)) continue;
            if (!item.getValue().getPhysicalCameraIds().isEmpty() && !id.equals(primary)) continue;
            if (id.equals(primary) && lenses.stream().anyMatch(l -> l.label.equals("主摄"))) continue;
            add(lenses, id, id, null, item.getValue(), baseline);
        }
        lenses.sort(Comparator.comparingDouble(l -> l.equivalentMm));
        return lenses;
    }

    private static void add(List<CameraInput.Lens> lenses, String id, String cameraId, String physicalId,
                            CameraCharacteristics info, float baseline) {
        float equivalent = equivalent(info);
        String label = equivalent > 0 && baseline > 0 && equivalent < baseline * .88 ? "超广角"
                : equivalent > 0 && baseline > 0 && equivalent > baseline * 1.22 ? "长焦" : "主摄";
        lenses.add(new CameraInput.Lens(id, label, Math.round(equivalent), cameraId, physicalId, info));
    }

    static CameraInput.Lens choose(List<CameraInput.Lens> lenses, String requested) {
        for (CameraInput.Lens item : lenses) if (item.id.equals(requested)) return item;
        for (CameraInput.Lens item : lenses) if (item.label.equals("主摄")) return item;
        return lenses.isEmpty() ? null : lenses.get(0);
    }

    private static float equivalent(CameraCharacteristics info) {
        float[] focal = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        return equivalent(info, focal == null || focal.length == 0 ? null : focal[0]);
    }

    static float equivalent(CameraCharacteristics info, Float focal) {
        SizeF size = info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focal == null || size == null || size.getWidth() <= 0 || size.getHeight() <= 0) return 0;
        return (float) (focal * Math.hypot(36, 24) / Math.hypot(size.getWidth(), size.getHeight()));
    }
}
