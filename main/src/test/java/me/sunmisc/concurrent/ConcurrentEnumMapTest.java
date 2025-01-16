package me.sunmisc.concurrent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sunmisc.utils.concurrent.maps.ConcurrentEnumMap;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;

public final class ConcurrentEnumMapTest {
    private ConcurrentMap<Letter, Integer> map;

    @BeforeEach
    public void setup() {
        this.map = new ConcurrentEnumMap<>(Letter.class);
    }

    @Test
    public void modifyMapWithLetters() {
        final Map<Letter, Integer> hashMap = Collections.synchronizedMap(
                new EnumMap<>(Letter.class)
        );
        try (final ExecutorService executor = Executors.newWorkStealingPool()) {
            final int ps = Math.min(Runtime.getRuntime().availableProcessors(), 8);
            for (int w = 0; w < ps; ++w) {
                executor.execute(() -> {
                    for (int i = 0; i < 128; i++) {
                        final Letter letter = Letter.rand();
                        this.map.put(letter, i);
                        hashMap.put(letter, i);
                    }
                });
                executor.execute(() -> {
                    hashMap.forEach((letter, value) -> {
                        this.map.remove(letter, value);
                        hashMap.remove(letter, value);
                    });
                });
            }
        }
        Assertions.assertEquals(
                hashMap,
                this.map,
                "Maps should match"
        );
    }

    public enum Letter {
        A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z;

        private static final Letter[] values = values();

        public static Letter rand() {
            final Random random = ThreadLocalRandom.current();
            return values[random.nextInt(values.length)];
        }
    }
}
