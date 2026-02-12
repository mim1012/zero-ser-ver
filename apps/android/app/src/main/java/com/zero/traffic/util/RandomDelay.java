package com.zero.traffic.util;

import java.util.Random;

/**
 * 랜덤 딜레이 유틸
 */
public class RandomDelay {
    private static final Random rng = new Random();

    /** min~max 사이 랜덤 밀리초 */
    public static int between(int min, int max) {
        if (max <= min) return min;
        return min + rng.nextInt(max - min + 1);
    }

    /** int[] {min, max} 배열에서 랜덤 */
    public static int fromRange(int[] range) {
        return between(range[0], range[1]);
    }

    /** ms 만큼 Thread.sleep (Interrupted 시 interrupt 플래그 복구) */
    public static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** min~max 사이 랜덤 sleep */
    public static void sleepBetween(int min, int max) {
        sleep(between(min, max));
    }

    /** int[] {min, max} 배열로 랜덤 sleep */
    public static void sleepRange(int[] range) {
        sleep(fromRange(range));
    }
}
