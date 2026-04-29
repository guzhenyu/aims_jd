package com.jingyicare.aims_jd.utils;

import ca.uhn.hl7v2.util.Terser;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.utils.Consts;

@Slf4j
public class Hl7Utils {
    public static String safeGet(Terser t, String path) {
        try { String v = t.get(path); return v == null ? "" : v; }
        catch (Exception e) { return ""; }
    }

    public static void safeSet(Terser t, String path, String val) {
        try { if (val != null) t.set(path, val); } catch (Exception ignore) {}
    }
}
