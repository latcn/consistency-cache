package com.consist.cache.core.util;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class MapUtil {

    private static final int maxExpectedSize = (1 << 28)*3;

    /**
     * For hashMap
     * @param expectedSize
     * @return
     */
    public static int capacity(int expectedSize) {
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize cannot be negative but was:" + expectedSize);
        }
        // 2^30
        return expectedSize < maxExpectedSize ? (int)Math.ceil((double)expectedSize / 0.75) : maxExpectedSize;
    }

    public static <T> void copy(Set<T> oldSet, Set<T> newSet, boolean isRemove) {
        /*T[] keys = (T[]) new Object[oldSet.size()];
        oldSet.toArray(keys);
        List<T> list = Arrays.asList(keys);
        newSet.addAll(list);
        if (isRemove) {
            oldSet.removeAll(list);
        }*/
        copy(oldSet, newSet, oldSet.size(), isRemove);
    }

    public static <T> void copy(Set<T> oldSet, Set<T> newSet, int maxExpectedSize, boolean isRemove) {
        int expectedSize = maxExpectedSize>oldSet.size()?oldSet.size():maxExpectedSize;
        for(T ele: oldSet) {
            if (expectedSize<=0) {
                return;
            }
            if (isRemove) {
                oldSet.remove(ele);
            }
            newSet.add(ele);
            expectedSize--;
        }
    }

    /**
     * 随机选取元素
     * @param oldSet
     * @param newSet
     * @param maxExpectedSize
     * @param isRemove
     * @param <T>
     */
    public static <T> void randomSelection(Set<T> oldSet, Set<T> newSet, int maxExpectedSize, boolean isRemove) {
        if (maxExpectedSize>oldSet.size()) {
            copy(oldSet, newSet, maxExpectedSize, isRemove);
        } else {
            Function<Integer, Boolean> validLogic;
            if (maxExpectedSize > oldSet.size()/2) {
                Set<Integer> randomExclude = randomGenerate(oldSet.size()-maxExpectedSize, oldSet.size());
                validLogic = (s)-> !randomExclude.contains(s);
            } else {
                Set<Integer> randomSelection = randomGenerate(maxExpectedSize, oldSet.size());
                validLogic = (s)-> randomSelection.contains(s);
            }
            T[] list = (T[]) new Object[oldSet.size()];
            oldSet.toArray(list);
            for (int i=0; i<list.length; i++) {
                if (validLogic.apply(i)) {
                    newSet.add(list[i]);
                    if (isRemove) {
                        oldSet.remove(list[i]);
                    }
                }
            }
        }
    }

    public static Set<Integer> randomGenerate(int size, int maxValue) {
        Set<Integer> indexSet = new HashSet<>();
        Random random = new Random();
        while(indexSet.size()<size) {
            indexSet.add(random.nextInt(maxValue));
        }
        return indexSet;
    }


    public static void main(String[] args) {
        Set<String> oldKeys = ConcurrentHashMap.newKeySet();
        oldKeys.add("1");
        oldKeys.add("2");
        oldKeys.add("3");
        oldKeys.add("4");
        oldKeys.add("5");
        Set<String> newKeys = ConcurrentHashMap.newKeySet();
        randomSelection(oldKeys, newKeys, 4, true);
        System.out.println("-------");
    }

    public static void testCopy() {
        Set<String> oldKeys = ConcurrentHashMap.newKeySet();
        oldKeys.add("1");
        oldKeys.add("2");
        oldKeys.add("3");
        Set<String> newKeys = ConcurrentHashMap.newKeySet();
        Thread t1 = new Thread(()->{
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            copy(oldKeys, newKeys, 2, true);
        });

        Thread t2 =  new Thread(()->{
            oldKeys.add("4");
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            oldKeys.add("5");
        });
        t1.start(); t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println("------");
    }
}
