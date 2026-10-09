package com.nona.changeTracking.change;

import java.util.Objects;

/**
 * 变更定位值对象：集中拥有一个变化位置的完整路径、相对路径、字段名、集合归属与直接包含者类型。
 * <p>
 * 定位在结果建立时作为<b>一致的整体</b>形成：完整路径相对被追踪根对象且在所有入口下固定；
 * 相对路径相对真实包含节点；字段名、最近集合字段名与「直接包含者是否为集合」由同一套派生规则
 * 得出，不作为可互相冲突的独立输入事实。因此本类的构造入口只有下面三个语义工厂——
 * 根位置、字段位置与集合项位置——不暴露全参构造器。
 * <p>
 * 派生规则（工厂的语义契约，示例见下表）：
 * <ul>
 *   <li>{@link #root()}：被追踪对象根处的定位，完整路径与相对路径均为空串，无字段名与集合归属，
 *       直接包含者不是集合。</li>
 *   <li>{@link #field(ChangeLocation, String)}：某包含位置下的<b>字段</b>定位。完整路径为包含位置的
 *       完整路径与字段名以点号连接；相对路径就是字段名；字段名即该字段；最近集合字段名继承包含位置的
 *       集合归属；直接包含者不是集合。</li>
 *   <li>{@link #collectionItem(ChangeLocation, Object, int)}：某<b>包含位置</b>下的集合项定位。包含位置
 *       可以是集合字段定位，也可以是集合项定位（集合项自身是集合时）；相对路径为 {@code [标识]}
 *       （重复标识按出现序追加 {@code #n}）；完整路径为包含位置的完整路径与相对路径连接；字段名为 null；
 *       最近集合字段名取包含位置自身的字段名（集合项位置与根位置为 null）；直接包含者是集合。</li>
 * </ul>
 * <table border="1">
 *   <caption>定位示例</caption>
 *   <tr><th>节点</th><th>完整路径</th><th>相对路径</th><th>字段名</th><th>集合字段名</th><th>直接父级为集合</th></tr>
 *   <tr><td>{@code items[100]} 新增</td><td>{@code items[100]}</td><td>{@code [100]}</td><td>null</td><td>{@code items}</td><td>true</td></tr>
 *   <tr><td>{@code items[200].name} 修改</td><td>{@code items[200].name}</td><td>{@code name}</td><td>{@code name}</td><td>{@code items}</td><td>false</td></tr>
 *   <tr><td>{@code items[200].subItems[101].name} 修改</td><td>{@code items[200].subItems[101].name}</td><td>{@code name}</td><td>{@code name}</td><td>{@code subItems}</td><td>false</td></tr>
 *   <tr><td>根集合的 {@code [100]} 项新增</td><td>{@code [100]}</td><td>{@code [100]}</td><td>null</td><td>null</td><td>true</td></tr>
 * </table>
 * 本类不反向持有业务对象、完整变更节点或比较会话状态，是纯值语义对象。
 */
public final class ChangeLocation {

    /**
     * 不需要出现序后缀的标记值：唯一标识的集合项使用该值。
     */
    public static final int NO_OCCURRENCE = 0;

    /**
     * 直接集合项的字段名占位：集合项本身不对应字段名，其相对路径以方括号开头。
     */
    private static final String ITEM_PATH_PREFIX = "[";

    /**
     * 相对被追踪根对象的完整路径；根处为空串。
     */
    private final String fullPath;

    /**
     * 相对真实包含节点的局部路径；根处为空串。
     */
    private final String relativePath;

    /**
     * 当前定位对应的字段名；直接集合项为 null。
     */
    private final String fieldName;

    /**
     * 当前节点的最近集合项对应的集合字段名；不在集合内时为 null。
     */
    private final String collectionFieldName;

    /**
     * 当前节点的直接包含者是否为集合。
     */
    private final boolean parentCollection;

    /**
     * 创建定位：只有本类的语义工厂可以调用。
     *
     * @param fullPath            相对被追踪根对象的完整路径
     * @param relativePath        相对真实包含节点的局部路径
     * @param fieldName           字段名，直接集合项为 null
     * @param collectionFieldName 最近集合字段名，不在集合内时为 null
     * @param parentCollection    直接包含者是否为集合
     */
    private ChangeLocation(final String fullPath, final String relativePath, final String fieldName,
                           final String collectionFieldName, final boolean parentCollection) {
        this.fullPath = fullPath;
        this.relativePath = relativePath;
        this.fieldName = fieldName;
        this.collectionFieldName = collectionFieldName;
        this.parentCollection = parentCollection;
    }

    /**
     * 创建被追踪对象根处的定位：空完整路径、空相对路径、无字段名与集合归属，直接包含者不是集合。
     * <p>
     * 该位置同时是真实根值变化（空路径原子变化）的定位，也是所有字段与集合项定位的派生基准。
     *
     * @return 根处定位
     */
    public static ChangeLocation root() {
        return new ChangeLocation("", "", null, null, false);
    }

    /**
     * 在某包含位置下创建字段定位。
     *
     * @param parent    包含位置的定位，不能为 null
     * @param fieldName 字段名，不能为 null
     * @return 字段定位
     */
    public static ChangeLocation field(final ChangeLocation parent, final String fieldName) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(fieldName, "fieldName");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank.");
        }
        final String parentPath = parent.fullPath;
        final String fullPath = parentPath.isEmpty() ? fieldName : parentPath + '.' + fieldName;
        return new ChangeLocation(fullPath, fieldName, fieldName, parent.collectionFieldName, false);
    }

    /**
     * 在某集合字段位置下创建唯一标识的集合项定位（不加出现序后缀）。
     *
     * @param parent   包含位置的定位（集合字段位置或集合项位置），不能为 null
     * @param identity 集合项的匹配标识，允许为 null
     * @return 集合项定位
     */
    public static ChangeLocation collectionItem(final ChangeLocation parent, final Object identity) {
        return collectionItem(parent, identity, NO_OCCURRENCE);
    }

    /**
     * 在某包含位置下创建集合项定位，出现序非 {@link #NO_OCCURRENCE} 时渲染 {@code #n} 后缀。
     *
     * @param parent     包含位置的定位（集合字段位置或集合项位置），不能为 null
     * @param identity   集合项的匹配标识，允许为 null
     * @param occurrence 出现序；{@link #NO_OCCURRENCE} 表示不加后缀
     * @return 集合项定位
     */
    public static ChangeLocation collectionItem(final ChangeLocation parent, final Object identity, final int occurrence) {
        Objects.requireNonNull(parent, "parent");
        if (occurrence < NO_OCCURRENCE) {
            throw new IllegalArgumentException("occurrence must not be negative: " + occurrence);
        }
        final StringBuilder relativePath = new StringBuilder();
        relativePath.append(ITEM_PATH_PREFIX).append(identity);
        if (occurrence != NO_OCCURRENCE) {
            relativePath.append('#').append(occurrence);
        }
        relativePath.append(']');
        final String relative = relativePath.toString();
        final String parentPath = parent.fullPath;
        final String fullPath = parentPath.isEmpty() ? relative : parentPath + relative;
        return new ChangeLocation(fullPath, relative, null, parent.fieldName, true);
    }

    /**
     * 返回相对被追踪根对象的完整路径。
     *
     * @return 完整路径，根处为空串
     */
    public String fullPath() {
        return this.fullPath;
    }

    /**
     * 返回相对真实包含节点的局部路径。
     *
     * @return 相对路径，根处为空串
     */
    public String relativePath() {
        return this.relativePath;
    }

    /**
     * 返回当前定位对应的字段名。
     *
     * @return 字段名，直接集合项为 null
     */
    public String fieldName() {
        return this.fieldName;
    }

    /**
     * 返回当前节点的最近集合字段名。
     *
     * @return 集合字段名，不在集合内时为 null
     */
    public String collectionFieldName() {
        return this.collectionFieldName;
    }

    /**
     * 判断当前节点的直接包含者是否为集合。
     *
     * @return 直接包含者是集合时返回 true
     */
    public boolean isParentCollection() {
        return this.parentCollection;
    }

    /**
     * 按全部定位事实比较值语义。
     *
     * @param other 待比较对象
     * @return 全部定位事实相同返回 true
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChangeLocation that)) {
            return false;
        }
        return this.parentCollection == that.parentCollection
                && this.fullPath.equals(that.fullPath)
                && this.relativePath.equals(that.relativePath)
                && Objects.equals(this.fieldName, that.fieldName)
                && Objects.equals(this.collectionFieldName, that.collectionFieldName);
    }

    /**
     * 与 {@link #equals(Object)} 对应的散列值。
     *
     * @return 定位事实的散列值
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.fullPath, this.relativePath, this.fieldName, this.collectionFieldName,
                this.parentCollection);
    }

    /**
     * 以完整路径表达定位。
     *
     * @return 完整路径文本
     */
    @Override
    public String toString() {
        return this.fullPath;
    }
}
