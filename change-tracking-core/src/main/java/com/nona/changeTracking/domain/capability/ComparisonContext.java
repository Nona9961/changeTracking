package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.snapshot.ValueNode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 单次比较的会话状态（ADR-003、US05）。
 * <p>
 * 承载两类状态，均随一次 {@link ValueNodeComparisonStrategy#compare} 调用创建、调用结束释放，
 * 不进入策略实例字段、静态缓存或线程局部变量：
 * <ul>
 *   <li><b>路径段栈</b>：字段段保存字段名，集合项段保存匹配组的<b>原标识</b>与数值出现序；
 *       上下文负责路径状态的进入、恢复及<b>按需</b>生成，仅在需要输出变更时拼接完整路径。
 *       路径栈按最大深度扩容并复用槽位，压入不为每个被检查字段新建路径段对象或可选值包装；
 *       标识不因入栈而字符串化，仅在当前活动项需要输出时准备文本并供其后续输出复用，
 *       退出时清理原标识与文本引用（US05）。</li>
 *   <li><b>活动节点对</b>：当前递归路径上的节点对（按新旧节点引用身份组合），用于循环引用终止；
 *       T03 将在同一上下文内增加安全无变更结论与循环截断计数（ADR-003）。</li>
 * </ul>
 * 本类不承担业务比较规则：匹配在 {@link CollectionMatchIndex}，分类与输出在
 * {@link ValueNodeComparisonStrategy}。
 */
final class ComparisonContext {

    /**
     * 不需要出现序后缀的标记值（US05）：{@code 0} 表示唯一项不加后缀，正数为既有出现序。
     */
    static final int NO_OCCURRENCE = 0;

    /**
     * 路径段栈的初始容量。
     */
    private static final int INITIAL_PATH_CAPACITY = 8;

    /**
     * 当前递归路径的路径段栈；槽位按需扩容并复用，元素为 {@link PathSegment}。
     */
    private PathSegment[] pathStack;

    /**
     * 路径段栈的当前深度（已使用的槽位数）。
     */
    private int depth;

    /**
     * 当前递归路径上的活动节点对，用于循环引用终止（按节点引用身份比较）。
     */
    private final Set<NodePair> activeNodePairs;

    /**
     * 创建一次比较的独立会话状态。
     */
    ComparisonContext() {
        this.pathStack = new PathSegment[INITIAL_PATH_CAPACITY];
        this.activeNodePairs = new HashSet<>();
        this.depth = 0;
    }

    /**
     * 压入字段路径段。
     *
     * @param fieldName 字段名，不能为 null。
     * @throws NullPointerException 如果 fieldName 为 null。
     */
    void pushField(final String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        segmentAt(this.depth).asField(fieldName);
        this.depth++;
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
        segmentAt(this.depth).asItem(identity, occurrence);
        this.depth++;
    }

    /**
     * 退出当前路径段：恢复进入前的栈深度并清理该槽位的原标识与文本引用。
     *
     * @throws IllegalStateException 如果当前没有已压入的路径段。
     */
    void pop() {
        if (this.depth == 0) {
            throw new IllegalStateException("No path segment to pop");
        }
        this.depth--;
        this.pathStack[this.depth].clear();
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
        if (this.depth == 0) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        for (int index = 0; index < this.depth; index++) {
            this.pathStack[index].appendTo(builder, index == 0);
        }
        return builder.toString();
    }

    /**
     * 登记当前递归路径上的活动节点对（循环引用终止）。
     *
     * @param oldNode 旧侧节点，不能为 null。
     * @param newNode 新侧节点，不能为 null。
     * @return 首次登记返回 true；同一节点对已在当前路径上返回 false（循环）。
     * @throws NullPointerException 如果任一节点为 null。
     */
    boolean enterNodePair(final ValueNode oldNode, final ValueNode newNode) {
        Objects.requireNonNull(oldNode, "oldNode");
        Objects.requireNonNull(newNode, "newNode");
        return this.activeNodePairs.add(new NodePair(oldNode, newNode));
    }

    /**
     * 退出活动节点对；无论正常或异常退出都须调用，保证状态不泄漏到其他路径。
     *
     * @param oldNode 旧侧节点，不能为 null。
     * @param newNode 新侧节点，不能为 null。
     * @throws NullPointerException 如果任一节点为 null。
     */
    void exitNodePair(final ValueNode oldNode, final ValueNode newNode) {
        Objects.requireNonNull(oldNode, "oldNode");
        Objects.requireNonNull(newNode, "newNode");
        this.activeNodePairs.remove(new NodePair(oldNode, newNode));
    }

    /**
     * 取指定深度处的路径段槽位，必要时扩容并创建槽位对象以便复用。
     *
     * @param index 目标槽位下标。
     * @return 可复用的路径段槽位。
     */
    private PathSegment segmentAt(final int index) {
        if (index == this.pathStack.length) {
            this.pathStack = Arrays.copyOf(this.pathStack, this.pathStack.length * 2);
        }
        PathSegment segment = this.pathStack[index];
        if (segment == null) {
            segment = new PathSegment();
            this.pathStack[index] = segment;
        }
        return segment;
    }

    /**
     * 一个路径段槽位：或为字段段（保存字段名），或为集合项段（保存原标识与数值出现序及按需文本）。
     * <p>
     * 槽位复用：重复压入不新建对象；退出时清理原标识与文本引用。
     */
    private static final class PathSegment {

        /**
         * 字段名；非 null 表示本段为字段段。
         */
        private String fieldName;

        /**
         * 集合项的原标识；允许 null。
         */
        private Object identity;

        /**
         * 数值出现序；{@link #NO_OCCURRENCE} 表示不加后缀。
         */
        private int occurrence;

        /**
         * 按需准备的标识文本；null 表示尚未准备。
         */
        private String identityText;

        /**
         * 将本槽位重置为字段段。
         *
         * @param name 字段名，不能为 null。
         */
        void asField(final String name) {
            this.fieldName = name;
            this.identity = null;
            this.occurrence = NO_OCCURRENCE;
            this.identityText = null;
        }

        /**
         * 将本槽位重置为集合项段。
         *
         * @param identity   原标识；允许 null。
         * @param occurrence 数值出现序；{@link #NO_OCCURRENCE} 表示不加后缀。
         */
        void asItem(final Object identity, final int occurrence) {
            this.fieldName = null;
            this.identity = identity;
            this.occurrence = occurrence;
            this.identityText = null;
        }

        /**
         * 将本段追加到路径构建器。
         *
         * @param builder 路径构建器。
         * @param first   本段是否为路径首段（字段段首段不加点号）。
         */
        void appendTo(final StringBuilder builder, final boolean first) {
            if (this.fieldName != null) {
                if (!first) {
                    builder.append('.');
                }
                builder.append(this.fieldName);
                return;
            }
            builder.append('[').append(identifierText());
            if (this.occurrence != NO_OCCURRENCE) {
                builder.append('#').append(this.occurrence);
            }
            builder.append(']');
        }

        /**
         * 清理本槽位的原标识与文本引用，供后续复用。
         */
        void clear() {
            this.fieldName = null;
            this.identity = null;
            this.occurrence = NO_OCCURRENCE;
            this.identityText = null;
        }

        /**
         * 按需准备并复用标识文本：null 标识为 {@code null}，非 null 标识为
         * {@link String#valueOf(Object)}。
         *
         * @return 标识文本。
         */
        String identifierText() {
            if (this.identityText == null) {
                this.identityText = this.identity == null ? "null" : String.valueOf(this.identity);
            }
            return this.identityText;
        }
    }

    /**
     * 当前递归路径上的一个活动节点对：按新旧节点引用身份组合记录，不使用节点内容相等性。
     */
    private static final class NodePair {

        /**
         * 旧侧节点。
         */
        private final ValueNode oldNode;

        /**
         * 新侧节点。
         */
        private final ValueNode newNode;

        /**
         * 创建活动节点对。
         *
         * @param oldNode 旧侧节点。
         * @param newNode 新侧节点。
         */
        NodePair(final ValueNode oldNode, final ValueNode newNode) {
            this.oldNode = oldNode;
            this.newNode = newNode;
        }

        /**
         * 按节点引用身份比较。
         *
         * @param other 待比较对象。
         * @return 身份相同返回 true。
         */
        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof NodePair that)) {
                return false;
            }
            return this.oldNode == that.oldNode && this.newNode == that.newNode;
        }

        /**
         * 与 {@link #equals(Object)} 对应：两侧节点身份哈希的组合。
         *
         * @return 节点身份哈希。
         */
        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(this.oldNode) + System.identityHashCode(this.newNode);
        }
    }
}
