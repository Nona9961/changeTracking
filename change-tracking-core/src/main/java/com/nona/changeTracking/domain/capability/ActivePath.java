package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.ChangeLocation;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一次比较的「活动路径」：路径段栈与按需、可共享的定位派生。
 * <p>
 * 承载两类状态，均随一次 {@link ValueNodeComparisonStrategy#compare} 调用创建、调用结束释放，
 * 不进入静态缓存、线程局部变量或跨调用缓存：
 * <ul>
 *   <li><b>路径段栈</b>：字段段保存字段名，集合项段保存匹配组的<b>原标识</b>与数值出现序；
 *       槽位按最大深度扩容并复用，压入不为每个被检查字段新建路径段对象或可选值包装。
 *       标识不因入栈而字符串化，仅在当前活动项需要输出时准备文本并供其后续输出复用，
 *       退出时清理原标识与文本引用。</li>
 *   <li><b>定位前缀缓存</b>：与段栈逐深度并行的 {@link ChangeLocation} 前缀缓存，另记「已构建前缀深度」。
 *       定位只经 {@link ChangeLocation} 的语义工厂按需构造：一次推进只为新增段各构造一个定位，
 *       已构建前缀（同一活动路径上的祖先段）被复用；同一深度重复取定位命中缓存，不从根重建。
 *       缓存只复用本次遍历活动路径上的不可变值对象，不跨调用保留业务标识文本。</li>
 * </ul>
 * 本类不承担业务比较规则：匹配在 {@link CollectionMatchIndex}，分类与输出在
 * {@link ValueNodeComparisonStrategy}，节点对状态（循环终止与无变更复用）在 {@link ComparisonContext}。
 */
final class ActivePath {

    /**
     * 路径段栈与定位缓存的初始容量。
     */
    private static final int INITIAL_CAPACITY = 8;

    /**
     * 路径段栈：槽位按需扩容并复用，元素为 {@link PathSegment}，与 {@link #locations} 逐深度对应。
     */
    private PathSegment[] segments;

    /**
     * 定位前缀缓存：下标 {@code i} 保存深度 {@code i + 1} 处的定位，与 {@link #segments} 逐深度对应。
     */
    private ChangeLocation[] locations;

    /**
     * 路径段栈的当前深度（已使用的槽位数），同时是当前路径的长度。
     */
    private int depth;

    /**
     * 已经构建定位的深度：{@code [0, builtDepth)} 的段已有可复用定位，{@code locations} 在这些下标处有效。
     */
    private int builtDepth;

    /**
     * 惰性缓存的根定位（深度 0）：空栈取定位时重复返回同一实例。
     */
    private ChangeLocation rootLocation;

    /**
     * 创建一次比较的活动路径。
     */
    ActivePath() {
        this.segments = new PathSegment[INITIAL_CAPACITY];
        this.locations = new ChangeLocation[INITIAL_CAPACITY];
        this.depth = 0;
        this.builtDepth = 0;
        this.rootLocation = null;
    }

    /**
     * 压入字段路径段。
     * <p>
     * 只写入段槽位：不触碰已构建的祖先前缀定位，也不为此段构造定位。
     *
     * @param fieldName 字段名，不能为 null
     * @throws NullPointerException 如果 fieldName 为 null
     */
    void pushField(final String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        segmentAt(this.depth).asField(fieldName);
        this.depth++;
    }

    /**
     * 压入集合项路径段。
     * <p>
     * 标识保持原对象，不在此处字符串化；出现序 {@link ComparisonContext#NO_OCCURRENCE} 表示不加后缀。
     *
     * @param identity   匹配组的原标识，允许 null（表示 null 项标识）
     * @param occurrence 数值出现序；{@link ComparisonContext#NO_OCCURRENCE} 表示不加后缀
     */
    void pushItem(final Object identity, final int occurrence) {
        segmentAt(this.depth).asItem(identity, occurrence);
        this.depth++;
    }

    /**
     * 退出当前路径段：恢复进入前的栈深度并清理该槽位的原标识与文本引用。
     * <p>
     * 已构建的祖先前缀定位保留在缓存中不重建。同时收缩「已构建前缀深度」到当前深度，使同一深度
     * 重新压入不同段时不会命中旧段的陈旧定位，而回退到已有前缀时仍复用其定位实例。
     *
     * @throws IllegalStateException 如果当前没有已压入的路径段
     */
    void pop() {
        if (this.depth == 0) {
            throw new IllegalStateException("No path segment to pop");
        }
        this.depth--;
        this.segments[this.depth].clear();
        if (this.builtDepth > this.depth) {
            this.builtDepth = this.depth;
        }
    }

    /**
     * 按当前栈<b>按需</b>生成完整路径字符串。
     * <p>
     * 集合项标识文本经 {@link String#valueOf(Object)} 准备并缓存在当前活动项路径段内，
     * null 标识呈现为 {@code null}；出现序非 {@link ComparisonContext#NO_OCCURRENCE} 时追加 {@code #n}。
     * 每次调用单遍拼接，不构造中间路径列表。
     *
     * @return 完整路径；栈为空时返回空字符串
     */
    String currentPath() {
        if (this.depth == 0) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        for (int index = 0; index < this.depth; index++) {
            this.segments[index].appendTo(builder, index == 0);
        }
        return builder.toString();
    }

    /**
     * 按当前栈<b>按需</b>生成当前比较位置的定位对象。
     * <p>
     * 定位由同一份路径段栈一次性形成一致的整体：字段段产生字段定位，集合项段产生集合项定位，
     * 完整路径、相对路径、字段名、最近集合字段名与「直接包含者是否为集合」不分别猜测。
     * 已构建的祖先前缀定位被复用，同一深度重复取定位返回同一实例；集合项标识文本与
     * {@link #currentPath()} 使用同一份按需渲染结果。
     *
     * @return 当前比较位置的定位；栈为空时返回根定位
     */
    ChangeLocation currentLocation() {
        if (this.depth == 0) {
            return rootLocation();
        }
        for (int index = this.builtDepth; index < this.depth; index++) {
            final ChangeLocation parent = index == 0 ? rootLocation() : this.locations[index - 1];
            this.locations[index] = this.segments[index].toLocation(parent);
        }
        this.builtDepth = this.depth;
        return this.locations[this.depth - 1];
    }

    /**
     * 返回惰性缓存的根定位，重复取用返回同一实例。
     *
     * @return 深度 0 处的根定位
     */
    private ChangeLocation rootLocation() {
        if (this.rootLocation == null) {
            this.rootLocation = ChangeLocation.root();
        }
        return this.rootLocation;
    }

    /**
     * 取指定深度处的路径段槽位，必要时扩容并创建槽位对象以便复用。
     * <p>
     * 段槽位数组与定位缓存数组同步扩容，保持两者逐深度对应。
     *
     * @param index 目标槽位下标
     * @return 可复用的路径段槽位
     */
    private PathSegment segmentAt(final int index) {
        if (index == this.segments.length) {
            this.segments = Arrays.copyOf(this.segments, this.segments.length * 2);
            this.locations = Arrays.copyOf(this.locations, this.locations.length * 2);
        }
        PathSegment segment = this.segments[index];
        if (segment == null) {
            segment = new PathSegment();
            this.segments[index] = segment;
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
         * 数值出现序；{@link ComparisonContext#NO_OCCURRENCE} 表示不加后缀。
         */
        private int occurrence;

        /**
         * 按需准备的标识文本；null 表示尚未准备。
         */
        private String identityText;

        /**
         * 按本段的派生规则形成定位：字段段产生字段定位，集合项段产生集合项定位。
         * <p>
         * 集合项段使用本槽位按需准备的标识文本（与 {@link #currentPath()} 共享同一份缓存），
         * 使同一活动项在多次形成定位时只格式化一次标识；已渲染的文本是字符串，不会再次触发
         * 原标识的 {@link Object#toString()}。
         *
         * @param parent 包含位置的定位
         * @return 本段的定位
         */
        ChangeLocation toLocation(final ChangeLocation parent) {
            if (this.fieldName != null) {
                return ChangeLocation.field(parent, this.fieldName);
            }
            return ChangeLocation.collectionItem(parent, identifierText(), this.occurrence);
        }

        /**
         * 将本槽位重置为字段段。
         *
         * @param name 字段名，不能为 null
         */
        void asField(final String name) {
            this.fieldName = name;
            this.identity = null;
            this.occurrence = ComparisonContext.NO_OCCURRENCE;
            this.identityText = null;
        }

        /**
         * 将本槽位重置为集合项段。
         *
         * @param identity   原标识；允许 null
         * @param occurrence 数值出现序；{@link ComparisonContext#NO_OCCURRENCE} 表示不加后缀
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
         * @param builder 路径构建器
         * @param first   本段是否为路径首段（字段段首段不加点号）
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
            if (this.occurrence != ComparisonContext.NO_OCCURRENCE) {
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
            this.occurrence = ComparisonContext.NO_OCCURRENCE;
            this.identityText = null;
        }

        /**
         * 按需准备并复用标识文本：null 标识为 {@code null}，非 null 标识为
         * {@link String#valueOf(Object)}。
         *
         * @return 标识文本
         */
        String identifierText() {
            if (this.identityText == null) {
                this.identityText = this.identity == null ? "null" : String.valueOf(this.identity);
            }
            return this.identityText;
        }
    }
}
