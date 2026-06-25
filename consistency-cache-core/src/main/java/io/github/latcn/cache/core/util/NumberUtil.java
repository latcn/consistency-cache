package io.github.latcn.cache.core.util;

public class NumberUtil {

    public static final long GOLDEN_RATIO = 0x9e3779b97f4a7c15L;

    public static int nextPowerOfTwo(int n) {
        if (n <= 1) {
            // 处理边界：n≤0 时最小 2 的幂是 1（2^0）
            return 1;
        }
        // 避免 n 本身是 2 的幂时结果翻倍
        n--;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        return n + 1;
    }

    /**
     * 斐波那契哈希（Fibonacci Hashing）与雪崩效应混合的优化哈希函数。
     * <p>
     * 该方法将输入的 key 哈希值（base）与行种子（seed）混合，映射到 [0, width-1] 范围内的桶索引。
     * 设计上包含四个核心优化点，以最大程度降低哈希碰撞概率并保持极高性能：
     *
     * <ol>
     *   <li><b>黄金比例乘法（Fibonacci Hashing）</b>：
     *   将 (base ^ seed) 乘以 64 位黄金分割常数 (0x9e3779b97f4a7c15L)，利用无符号整数自然溢出，
     *   将顺序输入（如连续递增的 ID）映射到模数空间上的“均匀随机”位置，打散线性排列，降低碰撞。
     *   </li>
     *   <li><b>雪崩效应（Avalanche Effect）</b>：
     *   通过 h ^= h >>> 32 将高 32 位混合信息折叠到低 32 位，确保输入特征的微小变化都能以高概率影响最终的低位，
     *   避免高位信息被丢弃，增强抗碰撞能力。
     *   </li>
     *   <li><b>种子隔离（Seed Isolation）</b>：
     *   为每一行（深度）分配独立的随机种子（构造时生成），使同一 key 在不同行中的哈希路径完全不同，
     *   保证多行独立性，从而提升“取多行最小值”修正误差策略的有效性。
     *   </li>
     *   <li><b>2 的幂掩码（Fast Modulo via AND）</b>：
     *   利用 h & (width-1) 替代 h % width，位运算仅需 1 个 CPU 周期，远快于除法指令（约 20~30 周期），
     *   极大提升计算效率。前提：width 必须为 2 的幂。
     *   </li>
     * </ol>
     *
     * <p><b>前置条件：</b>{@code width} 必须是 2 的幂（如 1024, 1<<20 等），否则 {@code & (width-1)} 无法正确取模。
     * 若 width 非 2 的幂，需退化为 {@code (int)(h % width)}，但会失去性能优势。
     *
     * @param base  key.hashCode() 的原始整数值
     * @param seed  该行独立的随机种子（每行不同）
     * @return      0 到 width-1 之间的桶索引
     */
    public static int hash(int base, int seed, int width) {
        long h = (base ^ seed) * GOLDEN_RATIO;
        h ^= h >>> 32;
        return (int) (h & (width - 1));
    }
}
