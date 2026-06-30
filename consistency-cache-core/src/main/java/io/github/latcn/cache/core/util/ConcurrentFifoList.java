package io.github.latcn.cache.core.util;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/**
 * 线程安全的 FIFO 顺序列表，支持固定容量或无限容量。
 * <p>
 * 基于 {@link LinkedHashMap} 加锁实现，并重写 {@link LinkedHashMap#removeEldestEntry} 钩子， 由 Map
 * 自身在插入后自动淘汰最旧元素，无需外部显式移除。 键和值存储同一个对象（T），因此可以通过值快速获取原始键实例（即 {@link #getKey(Object)}）。
 * </p>
 * <ul>
 * <li>不允许存储 {@code null}</li>
 * <li>若元素已存在，{@link #put(Object)} 会覆盖旧值并返回旧对象（不改变容量与顺序）</li>
 * <li>容量可指定为正整数（固定容量）或 ≤0（无限容量，不淘汰）</li>
 * <li>支持两种溢出策略：
 * <ul>
 * <li>{@link OverflowStrategy#REJECT}：容量满时拒绝新元素并抛出异常</li>
 * <li>{@link OverflowStrategy#REMOVE_OLDEST}：容量满时自动移除最旧元素后插入新元素</li>
 * </ul>
 * </li>
 * <li>提供批量取出（{@link #drain(int)}）和单元素移除（{@link #remove(Object)}）</li>
 * <li>内部使用 {@link ReentrantLock} 保护写操作，使用 {@link AtomicInteger} 维护大小，支持无锁读取
 * {@link #size()}</li>
 * </ul>
 *
 * @param <T> 元素类型
 * @author your-name
 */
@Slf4j
public class ConcurrentFifoList<T> {

	/**
	 * 溢出策略枚举
	 */
	public enum OverflowStrategy {

		/**
		 * 当容量满时，拒绝新元素并抛出 {@link IllegalStateException}
		 */
		REJECT,
		/**
		 * 当容量满时，移除最早插入的元素（FIFO 队首），再插入新元素
		 */
		REMOVE_OLDEST

	}

	private final LinkedHashMap<T, T> map;

	private final int capacity; // 实际容量，Integer.MAX_VALUE 表示无限

	private final OverflowStrategy strategy;

	private final AtomicInteger sizeCount; // 独立计数，无锁读取 size()

	private final ReentrantLock lock;

	private final boolean unbounded; // 是否无限容量

	/**
	 * 构造指定容量和溢出策略的 FIFO 列表。
	 * <p>
	 * 若 {@code capacity <= 0}，则视为无限容量（不淘汰元素）。
	 * </p>
	 * @param capacity 容量（≤0 表示无限）
	 * @param strategy 溢出策略（仅在固定容量模式下有效）
	 * @throws IllegalArgumentException 如果 capacity 为负数
	 */
	public ConcurrentFifoList(int capacity, OverflowStrategy strategy) {
		if (capacity < 0) {
			throw new IllegalArgumentException("Capacity cannot be negative");
		}
		this.capacity = capacity == 0 ? Integer.MAX_VALUE : capacity;
		this.unbounded = (capacity == 0);
		this.strategy = strategy;
		this.sizeCount = new AtomicInteger(0);
		this.lock = new ReentrantLock();

		// 计算合理的初始容量：固定容量小于16则用容量，否则用16；无限容量用16
		int initCap;
		if (this.capacity == Integer.MAX_VALUE) {
			initCap = 16;
		}
		else {
			initCap = Math.max(1, Math.min(16, this.capacity));
		}
		// accessOrder = false 表示按插入顺序（FIFO）
		this.map = new LinkedHashMap<T, T>(initCap, 0.75f, false) {
			private static final long serialVersionUID = 1L;

			/**
			 * 重写 removeEldestEntry 以实现自动淘汰。 仅在固定容量、元素数超过容量、且策略为 REMOVE_OLDEST 时返回 true， 由
			 * LinkedHashMap 内部自行移除最旧节点（无需额外哈希查找）。 注意：此时 map.size() 已经包含了新插入的元素，因此用 size()
			 * > capacity 判断。
			 */
			@Override
			protected boolean removeEldestEntry(Map.Entry<T, T> eldest) {
				if (!unbounded && size() > capacity && strategy == OverflowStrategy.REMOVE_OLDEST) {
					// 淘汰时同步减少外部计数（锁由外部 put 方法持有，线程安全）
					sizeCount.decrementAndGet();
					return true;
				}
				return false;
			}
		};
	}

	/**
	 * 构造无限容量的 FIFO 列表，溢出策略默认使用 {@link OverflowStrategy#REJECT}（实际不会触发）。
	 */
	public ConcurrentFifoList() {
		this(0, OverflowStrategy.REJECT);
	}

	/**
	 * 插入元素。
	 * <ul>
	 * <li>不允许 {@code null}</li>
	 * <li>若元素已存在，则用新值替换旧值并返回旧对象（容量与顺序不变）</li>
	 * <li>若为固定容量且已满，根据策略处理：
	 * <ul>
	 * <li>{@code REJECT}：抛异常</li>
	 * <li>{@code REMOVE_OLDEST}：自动淘汰最旧元素后插入</li>
	 * </ul>
	 * </li>
	 * <li>若为无限容量，则无容量限制，直接插入</li>
	 * </ul>
	 * @param value 要插入的元素
	 * @return 如果元素已存在，返回旧的元素对象（即被替换的旧值）；否则返回 {@code null}
	 * @throws NullPointerException 如果 value 为 null
	 * @throws IllegalStateException 如果容量满且策略为 REJECT
	 */
	public T put(T value) {
		if (value == null) {
			throw new NullPointerException("Value cannot be null");
		}
		lock.lock();
		try {
			// 1. 检查是否已存在
			T old = map.get(value);
			if (old != null) {
				// 存在：替换旧值并返回旧对象（不改变 size，不触发容量检查）
				map.put(value, value);
				return old;
			}

			// 2. 不存在：执行插入逻辑
			// 对于 REJECT 策略且已满，提前抛出异常，避免插入后再移除（也避免回调）
			if (!unbounded && sizeCount.get() == capacity && strategy == OverflowStrategy.REJECT) {
				throw new IllegalStateException(
						"Capacity exceeded (REJECT): limit=" + capacity + ", current=" + capacity);
			}
			// 3. 插入新元素（键和值相同）
			map.put(value, value);
			// 4. 更新计数（如果发生了淘汰，sizeCount 在回调中已经减了 1，
			sizeCount.incrementAndGet();
			return null;
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * 获取指定值对应的原始键对象（即存在于容器中的实例）。
	 * <p>
	 * 由于键值相同，此方法实际上返回的是容器中存储的键对象。 如果值存在但对应的键实例与传入的 value 不是同一个对象（但 equals 相等），
	 * 则返回容器中的原始键实例。
	 * </p>
	 * @param value 要查找的值（键）
	 * @return 容器中存储的原始键对象；如果不存在则返回 {@code null}
	 */
	public T getKey(T value) {
		if (value == null) {
			return null;
		}
		lock.lock();
		try {
			return map.get(value);
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * 移除列表中指定的元素（若存在）。
	 * @param value 要移除的元素，不允许为 null
	 * @return {@code true} 如果元素存在并被移除；{@code false} 如果元素不存在
	 * @throws NullPointerException 如果 value 为 null
	 */
	public boolean remove(T value) {
		if (value == null) {
			throw new NullPointerException("Value cannot be null");
		}
		lock.lock();
		try {
			T removed = map.remove(value);
			if (removed != null) {
				sizeCount.decrementAndGet();
				return true;
			}
			return false;
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * 批量取出并移除最多 {@code maxSize} 个最早插入的元素。 返回的元素按 FIFO 顺序排列（从旧到新）。
	 * @param maxSize 最大取出数量，必须 > 0
	 * @return 包含取出元素的列表（不可变），若列表为空则返回空集合
	 */
	public List<T> drain(int maxSize) {
		if (maxSize <= 0) {
			return Collections.emptyList();
		}
		lock.lock();
		try {
			int actualSize = sizeCount.get();
			int drainCount = Math.min(maxSize, actualSize);
			if (drainCount == 0) {
				return Collections.emptyList();
			}

			List<T> result = new ArrayList<>(drainCount);
			Iterator<Map.Entry<T, T>> it = map.entrySet().iterator();
			int removed = 0;
			while (it.hasNext() && removed < drainCount) {
				Map.Entry<T, T> entry = it.next();
				T key = entry.getKey();
				it.remove();
				result.add(key);
				removed++;
			}
			sizeCount.addAndGet(-removed);
			return result;
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * 返回当前元素数量（无锁读取，高效）。
	 * <p>
	 * 注意：由于读写分离，此方法返回的是近似值，可能在写操作过程中短暂滞后， 但最终一致。适用于统计或非严格场景。
	 * </p>
	 * @return 元素个数
	 */
	public int size() {
		return sizeCount.get();
	}

	/**
	 * 判断元素是否存在于列表中。
	 * @param value 要查找的元素，允许为 null（返回 false）
	 * @return {@code true} 如果存在
	 */
	public boolean contains(T value) {
		if (value == null) {
			return false;
		}
		lock.lock();
		try {
			return map.containsKey(value);
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * 清空列表。
	 */
	public void clear() {
		lock.lock();
		try {
			map.clear();
			sizeCount.set(0);
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * 返回当前所有元素的快照列表（按 FIFO 顺序），仅供调试或只读遍历。 注意：该列表是复制品，修改不影响原容器。
	 * @return 包含所有元素的列表
	 */
	public List<T> values() {
		lock.lock();
		try {
			return new ArrayList<>(map.keySet());
		}
		finally {
			lock.unlock();
		}
	}

}