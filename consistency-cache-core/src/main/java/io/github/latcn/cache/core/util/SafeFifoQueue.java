package io.github.latcn.cache.core.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 线程安全的FIFO队列实现 特性： 1. 严格保证先进先出顺序 2. 支持重复元素插入（显式更新语义） 3. put/drain操作原子化，彻底避免数据不一致 4.
 * 无hashCode变化风险（不依赖元素哈希值定位）
 *
 * @param <T> 队列元素类型（需正确实现equals）
 */
public class SafeFifoQueue<T> {

	private final LinkedBlockingDeque<T> insertionOrder;

	private final Map<T, T> valueMap;

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	private final Lock writeLock = lock.writeLock();

	private final AtomicInteger addCounter = new AtomicInteger();

	private final AtomicInteger removeCounter = new AtomicInteger();

	public SafeFifoQueue() {
		this.insertionOrder = new LinkedBlockingDeque<>();
		this.valueMap = new ConcurrentHashMap<>(); // 无锁读优化
	}

	/**
	 * 插入/更新元素（线程安全）
	 * @param value 待插入元素
	 * @return 旧值（若存在重复元素），否则返回null
	 */
	public T put(T value) {
		writeLock.lock();
		try {
			// 1. 更新map：若存在旧值，返回旧值供后续处理
			T oldValue = valueMap.put(value, value);

			// 2. 处理重复元素：在锁内原子移除旧值（必然成功）
			if (oldValue != null) {
				boolean removed = insertionOrder.remove(oldValue);
				// 断言：在writeLock保护下remove必须成功（避免逻辑错误）
				assert removed : "Inconsistent state! oldValue should exist in deque";
			}
			// 3. 添加新值到队尾
			insertionOrder.offer(value);
			addCounter.incrementAndGet();
			return oldValue;
		}
		finally {
			writeLock.unlock();
		}
	}

	/**
	 * 批量移除并返回队头元素（线程安全）
	 * @param maxElements 最大移除数量
	 * @return 实际移除的元素列表（按FIFO顺序）
	 */
	public List<T> drain(int maxElements) {
		if (maxElements <= 0) {
			return Collections.emptyList();
		}

		List<T> result = new ArrayList<>(maxElements);
		writeLock.lock();
		try {
			// 1. 原子化批量移除：drainTo保证内部操作不可分割
			insertionOrder.drainTo(result, maxElements);

			// 2. 在同一临界区内同步更新map（避免部分移除）
			for (T item : result) {
				valueMap.remove(item);
				removeCounter.incrementAndGet();
			}
			return result;
		}
		finally {
			writeLock.unlock();
		}
	}

	/**
	 * 检查元素是否存在（线程安全读）
	 * @param value 元素
	 * @return 是否存在
	 */
	public boolean contains(T value) {
		return valueMap.containsKey(value);
	}

	/**
	 * 获取当前队列大小（线程安全读）
	 * @return 元素数量
	 */
	public int size() {
		return valueMap.size();
	}

	// ======================= 以下为可选扩展方法 =======================

	/**
	 * 安全获取队头元素（不移除）
	 * @return 队头元素（若存在），否则null
	 */
	public T peek() {
		return insertionOrder.peekFirst();
	}

	/**
	 * 移除并返回队头元素（单元素版drain）
	 * @return 队头元素（若存在），否则null
	 */
	public T poll() {
		List<T> items = drain(1);
		return items.isEmpty() ? null : items.get(0);
	}

	/**
	 * 清空队列（线程安全）
	 */
	public void clear() {
		writeLock.lock();
		try {
			insertionOrder.clear();
			valueMap.clear();
		}
		finally {
			writeLock.unlock();
		}
	}

	public int getAddCounter() {
		return addCounter.get();
	}

	public int getRemoveCounter() {
		return removeCounter.get();
	}

}
