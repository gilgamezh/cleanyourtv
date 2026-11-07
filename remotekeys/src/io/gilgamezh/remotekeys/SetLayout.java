package io.gilgamezh.remotekeys;

import java.lang.reflect.Method;

/**
 * Run as shell over adb:
 *   CLASSPATH=$(pm path io.gilgamezh.remotekeys | cut -d: -f2) app_process /system/bin io.gilgamezh.remotekeys.SetLayout <device-name-substring> [clear]
 * Selects (or clears) the overlay layout for every input device whose name contains the substring.
 */
public class SetLayout {
    static final String LAYOUT = "io.gilgamezh.remotekeys/io.gilgamezh.remotekeys.Layouts/sony_remote";

    public static void main(String[] args) throws Exception {
        String match = args.length > 0 ? args[0] : "";
        boolean clear = args.length > 1 && args[1].equals("clear");
        Class<?> imc = Class.forName("android.hardware.input.InputManager");
        Object im = imc.getMethod("getInstance").invoke(null);
        int[] ids = (int[]) imc.getMethod("getInputDeviceIds").invoke(im);
        Class<?> idc = Class.forName("android.view.InputDevice");
        Class<?> identc = Class.forName("android.hardware.input.InputDeviceIdentifier");
        Method getDev = imc.getMethod("getInputDevice", int.class);
        Method setCur = imc.getMethod("setCurrentKeyboardLayoutForInputDevice", identc, String.class);
        Method getCur = imc.getMethod("getCurrentKeyboardLayoutForInputDevice", identc);
        Method removeL = imc.getMethod("removeKeyboardLayoutForInputDevice", identc, String.class);
        for (int id : ids) {
            Object dev = getDev.invoke(im, id);
            if (dev == null) continue;
            String name = (String) idc.getMethod("getName").invoke(dev);
            boolean virt = (Boolean) idc.getMethod("isVirtual").invoke(dev);
            Object ident = idc.getMethod("getIdentifier").invoke(dev);
            String cur = (String) getCur.invoke(im, ident);
            boolean hit = !virt && !match.isEmpty() && name.contains(match);
            if (hit && clear) {
                removeL.invoke(im, ident, LAYOUT);
                cur = (String) getCur.invoke(im, ident);
            } else if (hit) {
                setCur.invoke(im, ident, LAYOUT);
                cur = (String) getCur.invoke(im, ident);
            }
            System.out.println((hit ? "* " : "  ") + id + " \"" + name + "\" layout=" + cur);
        }
    }
}
