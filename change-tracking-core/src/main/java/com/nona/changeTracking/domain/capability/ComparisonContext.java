package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.ChangeLocation;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;

/**
 * 单次比较的会话状态。
 * <p>
 * 承载两类状态，均随一次 {@link ValueNodeComparisonStrategy#compare} 调用创建、调用结束释放，
 * 不进入策略实例字段、静态缓存或线程局部变量：
 * <ul>
 *   <li><b>活动路径</b>：路径段栈与按需、可共享的定位派生由 {@link ActivePath} 承载；上下文只把
 *       路径状态的进入、恢复与按需生成委托给它，保证单次遍历内的定位只按其活动路径的前缀构造一次。</li>
 *   <li><b>节点对状态（单表三态）</b>：路径段栈之外只保留<b>一份</b>
 *       {@link NodePairStates 节点对状态表}，按新旧节点引用身份组合记录。同一份表同时回答两个
 *       问题——「这个节点对是否正在比较」（{@link NodePairStates#IN_PROGRESS}，用于循环终止）
 *       与「是否已完整比较并确认没有变化」（{@link NodePairStates#COMPLETED_UNCHANGED}，用于复用
 *       跳过重复工作）。进入时<b>一次查询</b>即按状态分发，不再为复用判定另付一次逐节点查询；
 *       退出时把本次结论写回同一状态。有变化、发生循环截断或异常退出的节点对为
 *       {@link NodePairStates#COMPLETED_CHANGED}，不可复用。</li>
 * </ul>
 * 本类不承担业务比较规则：匹配在 {@link CollectionMatchIndex}，分类与输出在
 * {@link ValueNodeComparisonStrategy}，路径与定位在 {@link ActivePath}。
 * <p>
 * <b>非空守卫口径</b>：本类是包内私有实现，节点对相关方法（进入查询、状态更新、退出）
 * <b>不写重复的非空守卫</b>——调用方（{@link ValueNodeComparisonStrategy#compare} 的递归遍历）
 * 在分派前已用 {@code instanceof ObjectNode/CollectionNode} 判定两侧节点，传 null 不可达。
 * 守卫只保留在边界入口（{@link #pushField(String)} 的字段名边界与 {@link #pop()} 的空栈边界），
 * 不为被调用点已保证的前置条件再付一次检查。
 */
final class ComparisonContext {

    /**
     * 不需要出现序后缀的标记值：{@code 0} 表示唯一项不加后缀，正数为既有出现序。
     */
    static final int NO_OCCURRENCE = 0;

    /**
     * 当前递归路径的活动路径：段栈、按需路径与按深度复用的定位前缀缓存。
     */
    private final ActivePath activePath;
    /**
     * 节点对状态表：单表三态，同时承担循环终止与安全无变更复用判定。
     */
    private final NodePairStates nodePairStates;

    /**
     * 本次比较累计的循环截断次数：遇到状态为 {@link NodePairStates#IN_PROGRESS}（正在比较）的
     * 节点对而终止递归的次数。
     */
    private int cycleTruncationCount;

    /**
     * 创建一次比较的独立会话状态。
     */
    ComparisonContext() {
        this.activePath = new ActivePath();
        this.nodePairStates = new NodePairStates();
        this.cycleTruncationCount = 0;
    }

    /**
     * 压入字段路径段。
     *
     * @param fieldName 字段名，不能为 null。
     * @throws NullPointerException 如果 fieldName 为 null。
     */
    void pushField(final String fieldName) {
        this.activePath.pushField(fieldName);
    }

    /**
     * 压入集合项路径段。
     * <p>
     * 标识保持原对象，不在此处字符串化；出现序 {@link #NO_OCCURRENCE} 表示不加后缀。
     *
     * @param identity   匹配组的原标识（允许 null，表示 null 项标识）。
     * @param occurrence 数值出现序；{@link #NO_OCCURRENCE} 表示不加后缀。
     */
    void pushItem(final Object identity, final int occurrence) {
        this.activePath.pushItem(identity, occurrence);
    }

    /**
     * 退出当前路径段：恢复进入前的栈深度并清理该槽位的原标识与文本引用。
     *
     * @throws IllegalStateException 如果当前没有已压入的路径段。
     */
    void pop() {
        this.activePath.pop();
    }

    /**
     * 按当前栈<b>按需</b>生成完整路径字符串。
     * <p>
     * 集合项标识文本经 {@link String#valueOf(Object)} 准备并缓存在当前活动项路径段内，
     * null 标识呈现为 {@code null}；出现序非 {@link #NO_OCCURRENCE} 时追加 {@code #n}。
     *
     * @return 完整路径；栈为空时返回空字符串。
     */
    String currentPath() {
        return this.activePath.currentPath();
    }

    /**
     * 按当前栈<b>按需</b>生成当前比较位置的定位对象。
     * <p>
     * 定位由同一份路径段栈一次性形成一致的整体：字段段产生字段定位，集合项段产生集合项定位，
     * 完整路径、相对路径、字段名、最近集合字段名与「直接包含者是否为集合」不分别猜测。
     * 集合项标识文本与 {@link #currentPath()} 使用同一份按需渲染结果。
     *
     * @return 当前比较位置的定位；栈为空时返回根定位。
     */
    ChangeLocation currentLocation() {
        return this.activePath.currentLocation();
    }

    /**
     * 进入节点对：<b>一次查询</b>节点对状态，同时完成循环判断与复用判断。
     * <p>
     * 按查询到的状态分发：
     * <ul>
     *   <li>{@link NodePairStates#IN_PROGRESS}（正在比较）→ 循环截断：按原规则返回 false，并使累计
     *       截断计数加一；</li>
     *   <li>{@link NodePairStates#COMPLETED_UNCHANGED}（已完成且无变更）→ 复用跳过：返回 false，
     *       不改动状态；</li>
     *   <li>{@link NodePairStates#COMPLETED_CHANGED}（已完成但有变更）或未记录 → 置为
     *       {@link NodePairStates#IN_PROGRESS}（记录进入时的截断计数），返回 true。</li>
     * </ul>
     * 调用方只在返回 true 时递归子节点并在结束时调用退出方法；返回 false 时直接返回，不生成变更、
     * 不生成路径，也不再重复登记。
     *
     * @param oldNode 旧侧节点（调用方已保证非空，本方法不重复守卫）。
     * @param newNode 新侧节点（调用方已保证非空，本方法不重复守卫）。
     * @return 已置为「正在比较」返回 true；循环截断或复用命中返回 false。
     */
    boolean enterNodePair(final ValueNode oldNode, final ValueNode newNode) {
        final byte previous = this.nodePairStates.enter(oldNode, newNode, this.cycleTruncationCount);
        if (previous == NodePairStates.IN_PROGRESS) {
            this.cycleTruncationCount++;
            return false;
        }
        return previous != NodePairStates.COMPLETED_UNCHANGED;
    }

    /**
     * 退出节点对并保守地置为「已完成有变更」（不可复用）。
     * <p>
     * 等价于 {@code exitNodePair(oldNode, newNode, false)}：无法确认无变更的退出（含异常退出路径）
     * 一律视为不可复用，下次遇到同一节点对时重新完整比较。无论正常或异常退出都须调用，保证状态
     * 不泄漏到其他路径。
     *
     * @param oldNode 旧侧节点（调用方已保证非空，本方法不重复守卫）。
     * @param newNode 新侧节点（调用方已保证非空，本方法不重复守卫）。
     */
    void exitNodePair(final ValueNode oldNode, final ValueNode newNode) {
        this.exitNodePair(oldNode, newNode, false);
    }

    /**
     * 退出节点对并按本次结论更新状态。
     * <p>
     * 本次无变更<b>且</b>期间未发生循环截断 → {@link NodePairStates#COMPLETED_UNCHANGED}（可复用）；
     * 否则 → {@link NodePairStates#COMPLETED_CHANGED}（不可复用）。是否发生循环截断由本方法按
     * 进入时记录的截断计数与当前计数比较判定，调用方只需给出本次是否无变更。
     *
     * @param oldNode   旧侧节点（调用方已保证非空，本方法不重复守卫）。
     * @param newNode   新侧节点（调用方已保证非空，本方法不重复守卫）。
     * @param unchanged 本次子比较是否无变更。
     */
    void exitNodePair(final ValueNode oldNode, final ValueNode newNode, final boolean unchanged) {
        this.nodePairStates.exit(oldNode, newNode, unchanged, this.cycleTruncationCount);
    }

    /**
     * 返回本次比较累计的循环截断次数。
     * <p>
     * 数值口径与既有语义保持一致：仅在遇到状态为「正在比较」的节点对而终止递归时加一；
     * 复用命中（已完成且无变更）与「已完成有变更」的重新比较均不增加该计数。
     *
     * @return 累计的循环截断次数，未发生截断时为 0。
     */
    int cycleTruncationCount() {
        return this.cycleTruncationCount;
    }

    /**
     * 节点对状态表（单表三态）：按 (old,new) 引用身份组合开放寻址存储，
     * 用<b>一份</b>表同时回答「正在比较」与「已完成且无变更」两个问题。
     * <p>
     * 查询与登记都<b>不构造临时节点对对象、不另建第二份集合</b>：普通树、共享图与循环图共用同一
     * 比较热路径，会话记录成本必须与节点数同阶且常数足够小。表在本轮 {@code compare}
     * 内驻留，随 {@link ComparisonContext} 回收。
     * <p>
     * 槽位三态，空槽表示未记录：
     * <ul>
     *   <li>{@link #IN_PROGRESS}：该节点对正在当前递归路径上比较（循环终止语义）；</li>
     *   <li>{@link #COMPLETED_UNCHANGED}：已完整比较、无变更且期间未发生循环截断，可复用；</li>
     *   <li>{@link #COMPLETED_CHANGED}：有变更、依赖循环截断或异常退出，不可复用，下次重新比较。</li>
     * </ul>
     * 只支持登记与状态更新，不删除条目；每个槽位另存该节点对进入比较时的截断计数，用于退出时判定
     * 「本次空结果是否依赖循环截断」。
     */
    private static final class NodePairStates {

        /**
         * 查询结果：本次查询前该节点对未记录（空槽）。
         */
        static final byte NOT_RECORDED = 0;

        /**
         * 状态：该节点对正在当前递归路径上比较。
         */
        static final byte IN_PROGRESS = 1;

        /**
         * 状态：该节点对已完整比较、无变更且未发生循环截断，可复用。
         */
        static final byte COMPLETED_UNCHANGED = 2;

        /**
         * 状态：该节点对已比较但有变更、依赖截断或异常退出，不可复用。
         */
        static final byte COMPLETED_CHANGED = 3;

        /**
         * 初始槽位数（2 的幂）：节点对不超过 {@code INITIAL_CAPACITY / 2} 的小对象图不触发扩容，
         * 不为小负载固定分配大数组。
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * 扩容倍数（2 的幂）：按需增长的跳跃幅度。取 4 让常见中小对象图（节点对约 32 以内）
         * 至多付一次扩容，同时不改变装载因子上限与摊销复杂度。
         */
        private static final int GROWTH_FACTOR = 4;

        /**
         * 旧侧节点引用数组；null 表示空槽（节点引用永不为 null）。
         */
        private ValueNode[] oldNodes;

        /**
         * 新侧节点引用数组，与 {@link #oldNodes} 逐槽对应。
         */
        private ValueNode[] newNodes;

        /**
         * 槽位状态数组，取值见本类状态常量。
         */
        private byte[] states;

        /**
         * 各槽位记录进入比较时的截断计数，用于退出时判定截断窗口。
         */
        private int[] truncationCountsAtEntry;

        /**
         * 已登记的节点对数。
         */
        private int size;

        /**
         * 下一次扩容前可容纳的节点对数。
         */
        private int threshold;

        /**
         * 创建一张空表。
         */
        NodePairStates() {
            this.oldNodes = new ValueNode[INITIAL_CAPACITY];
            this.newNodes = new ValueNode[INITIAL_CAPACITY];
            this.states = new byte[INITIAL_CAPACITY];
            this.truncationCountsAtEntry = new int[INITIAL_CAPACITY];
            this.size = 0;
            this.threshold = INITIAL_CAPACITY / 2;
        }

        /**
         * 一次查询并登记：返回查询前该节点对的状态，按状态决定是否置为「正在比较」。
         * <p>
         * 命中 {@link #IN_PROGRESS} 时不改动状态；命中 {@link #COMPLETED_UNCHANGED} 时不改动状态；
         * 命中 {@link #COMPLETED_CHANGED} 或未记录（空槽）时置为 {@link #IN_PROGRESS} 并记录本次
         * 进入时的截断计数。查询不构造临时节点对对象。
         * <p>
         * 扩容只在确认需要插入新条目时发生：命中已记录条目的两种情形（复用命中与重新比较）都只读改
         * 原槽位，不触发数组分配与重散列。
         *
         * @param oldNode                      旧侧节点（调用方已保证非空）。
         * @param newNode                      新侧节点（调用方已保证非空）。
         * @param cycleTruncationCountAtEntry  本次进入时的累计截断计数。
         * @return 查询前的状态：{@link #NOT_RECORDED} / {@link #IN_PROGRESS} /
         *         {@link #COMPLETED_UNCHANGED} / {@link #COMPLETED_CHANGED}。
         */
        byte enter(final ValueNode oldNode, final ValueNode newNode, final int cycleTruncationCountAtEntry) {
            int index = hash(oldNode, newNode) & (this.oldNodes.length - 1);
            while (this.oldNodes[index] != null) {
                if (this.oldNodes[index] == oldNode && this.newNodes[index] == newNode) {
                    final byte previous = this.states[index];
                    if (previous == IN_PROGRESS || previous == COMPLETED_UNCHANGED) {
                        return previous;
                    }
                    this.states[index] = IN_PROGRESS;
                    this.truncationCountsAtEntry[index] = cycleTruncationCountAtEntry;
                    return COMPLETED_CHANGED;
                }
                index = (index + 1) & (this.oldNodes.length - 1);
            }
            if (this.size >= this.threshold) {
                resize();
                index = hash(oldNode, newNode) & (this.oldNodes.length - 1);
                while (this.oldNodes[index] != null) {
                    index = (index + 1) & (this.oldNodes.length - 1);
                }
            }
            this.oldNodes[index] = oldNode;
            this.newNodes[index] = newNode;
            this.states[index] = IN_PROGRESS;
            this.truncationCountsAtEntry[index] = cycleTruncationCountAtEntry;
            this.size++;
            return NOT_RECORDED;
        }

        /**
         * 退出本次比较并按结论更新同一状态。
         * <p>
         * 本次无变更且当前累计截断计数与进入时记录相同 → {@link #COMPLETED_UNCHANGED}；
         * 否则 → {@link #COMPLETED_CHANGED}。更新不构造临时节点对对象。
         *
         * @param oldNode                     旧侧节点（调用方已保证非空）。
         * @param newNode                     新侧节点（调用方已保证非空）。
         * @param unchanged                   本次子比较是否无变更。
         * @param cycleTruncationCountAtExit  本次退出时的累计截断计数。
         */
        void exit(final ValueNode oldNode, final ValueNode newNode, final boolean unchanged,
                  final int cycleTruncationCountAtExit) {
            int index = hash(oldNode, newNode) & (this.oldNodes.length - 1);
            while (this.oldNodes[index] != null) {
                if (this.oldNodes[index] == oldNode && this.newNodes[index] == newNode) {
                    if (this.states[index] == IN_PROGRESS) {
                        this.states[index] = unchanged
                                && cycleTruncationCountAtExit == this.truncationCountsAtEntry[index]
                                ? COMPLETED_UNCHANGED : COMPLETED_CHANGED;
                    }
                    return;
                }
                index = (index + 1) & (this.oldNodes.length - 1);
            }
        }

        /**
         * 计算节点对的身份组合散列：组合两侧的 {@link System#identityHashCode(Object)}，
         * 使 (old,new) 的顺序参与散列（(a,b) 与 (b,a) 通常落到不同槽位）。
         *
         * @param oldNode 旧侧节点，调用方已保证非空。
         * @param newNode 新侧节点，调用方已保证非空。
         * @return 节点对的身份散列值。
         */
        private static int hash(final ValueNode oldNode, final ValueNode newNode) {
            return 31 * System.identityHashCode(oldNode) + System.identityHashCode(newNode);
        }

        /**
         * 容量按 {@link #GROWTH_FACTOR} 倍扩容并按身份散列重新放置全部条目。
         * <p>
         * 扩容保持装载因子不超过 1/2，保证探测总能遇到空槽而终止；重散列不构造临时节点对对象。
         */
        private void resize() {
            final ValueNode[] previousOldNodes = this.oldNodes;
            final ValueNode[] previousNewNodes = this.newNodes;
            final byte[] previousStates = this.states;
            final int[] previousTruncationCounts = this.truncationCountsAtEntry;
            final int newCapacity = previousOldNodes.length * GROWTH_FACTOR;
            this.oldNodes = new ValueNode[newCapacity];
            this.newNodes = new ValueNode[newCapacity];
            this.states = new byte[newCapacity];
            this.truncationCountsAtEntry = new int[newCapacity];
            this.threshold = newCapacity / 2;
            for (int source = 0; source < previousOldNodes.length; source++) {
                if (previousOldNodes[source] == null) {
                    continue;
                }
                int index = hash(previousOldNodes[source], previousNewNodes[source]) & (newCapacity - 1);
                while (this.oldNodes[index] != null) {
                    index = (index + 1) & (newCapacity - 1);
                }
                this.oldNodes[index] = previousOldNodes[source];
                this.newNodes[index] = previousNewNodes[source];
                this.states[index] = previousStates[source];
                this.truncationCountsAtEntry[index] = previousTruncationCounts[source];
            }
        }
    }
}