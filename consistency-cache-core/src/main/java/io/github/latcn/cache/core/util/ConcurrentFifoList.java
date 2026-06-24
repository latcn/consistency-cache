package io.github.latcn.cache.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/**
 * 线程安全的FIFO顺序List（固定容量，超容时淘汰最早插入元素）
 *
 */
@Slf4j
public class ConcurrentFifoList<T> {

	private final int capacity;

	private final ConcurrentHashMap<T, T> map;

	private final ConcurrentLinkedQueue<T> insertionOrder;

	private final ReentrantLock writeLock = new ReentrantLock();

	private final AtomicInteger addCounter = new AtomicInteger(0);

	private final AtomicInteger removeCounter = new AtomicInteger(0);

	/**
	 * 构造函数
	 * @param capacity 最大容量（必须 > 0）
	 */
	public ConcurrentFifoList(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("Capacity must be > 0");
		}
		this.capacity = capacity;
		this.map = new ConcurrentHashMap<>();
		this.insertionOrder = new ConcurrentLinkedQueue<>();
	}

	/**
	 * 新添加覆盖原来的顺序
	 * @param value
	 */
	public T put(T value) {
		T oldValue = null;
		if (map.size() > capacity) {
			throw new RuntimeException("already more than " + capacity);
		}
		writeLock.lock();
		try {
			oldValue = map.put(value, value);
			if (oldValue != null) {
				insertionOrder.remove(value);
			}
			insertionOrder.offer(value);
			addCounter.incrementAndGet();
		}
		catch (Exception e) {
			log.error("put", e);
		}
		finally {
			writeLock.unlock();
		}
		return oldValue;
	}

	public int size() {
		return map.size();
	}

	public List<T> drainAll(int maxSize) {
		List<T> drained = new ArrayList<>();
		T item;
		writeLock.lock();
		try {
			while ((item = insertionOrder.poll()) != null && drained.size() < maxSize) {
				drained.add(item);
				map.remove(item);
				removeCounter.incrementAndGet();
			}
		}
		catch (Exception e) {
			log.error("drainAll", e);
		}
		finally {
			writeLock.unlock();
		}
		return drained.size() == 0 ? null : drained;
	}

	public ConcurrentHashMap<T, T> getMap() {
		return map;
	}

	public ConcurrentLinkedQueue<T> getInsertionOrder() {
		return insertionOrder;
	}

	public int getAddCounter() {
		return addCounter.get();
	}

	public int getRemoveCounter() {
		return removeCounter.get();
	}

}
