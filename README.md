# Change Tracking Framework

一个轻量级的 Java 属性级变更追踪框架（**变更检测器**），支持嵌套对象和集合的深度比较。通过 SPI 机制支持灵活扩展。

> **命名说明**：本框架本质是**变更检测器**——注册对象 → 检测属性变更（UPDATE），
> 不管理 INSERT/UPDATE/DELETE 全生命周期，因此核心类命名为 `ChangeTracker`
> 而非经典工作单元（Unit of Work）。`excludeNew` / `excludeRemoved` 是**排除机制**
> （标记不追踪），不是生命周期语义。

## 特性

- **属性级变更追踪**：精确追踪对象属性的变化，包括嵌套对象和集合
- **业务标识符匹配**：基于业务标识符（而非索引）进行集合项匹配，支持集合重排序
- **数组支持**：数组 = 值语义（顺序敏感，`{1,2,3}` ≠ `{3,2,1}`）；复杂对象数组按集合递归匹配
- **SPI 扩展机制**：通过 `ServiceLoader` 支持自定义追踪能力
- **双视图变更表示**：`getAllChanges()` 树形视图 和 `getLeafChanges()` 扁平视图
- **循环引用处理**：自动检测和处理对象间的循环引用
- **不可变契约**：快照节点与配置构造后不可变（只读 API，编译级封死写操作）
- **零配置反射**：无需 JVM 参数即可正常使用反射功能

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>com.nona</groupId>
    <artifactId>change-tracking-api</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 基本用法

```java
// 1. 创建 ChangeTracker
ChangeTracker tracker = ChangeTrackerFactory.builder()
    .withDefaults()
    .build();

// 2. 纳入追踪（建立初始快照基线）
User user = userRepository.findById(1L);
tracker.track(user);

// 3. 修改对象属性
user.setName("新名称");
user.setEmail("new@example.com");

// 4. 计算变更（幂等视图：重复调用返回相同变更集，基线不推进）
ChangeSet changeSet = tracker.calculateChanges();

// 5. 获取变更列表
List<Change> leafChanges = changeSet.getLeafChanges();
for (Change change : leafChanges) {
    if (change instanceof ValueChange vc) {
        // 基本值变更：oldValue/newValue 是业务值，可安全强转业务类型
        System.out.printf("字段 %s: %s -> %s%n",
            vc.path(), vc.oldValue(), vc.newValue());
    } else if (change instanceof ObjectFieldChange ofc) {
        // 对象/集合字段整体替换：无业务值，携带 ValueNode 表示
        // NullNode=清空、ObjectNode/CollectionNode/ArrayNode=整体赋值
        System.out.printf("字段 %s 整体替换: %s -> %s%n",
            ofc.path(), ofc.oldNode(), ofc.newNode());
    }
}
```

### 配置业务标识符

```java
// 通过 Provider 配置业务标识符提取器
DefaultTrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
provider.withIdentifier(Order.class, Order::getId)
        .withIdentifier(LineItem.class, LineItem::getSku)
        .withValueType(Money.class)           // 自定义值类型（必须不可变）
        .withValuePackage("com.example.vo");  // 整个包的值类型
```

## 核心概念

### 注册方法语义

| 方法 | 说明 | 快照创建 | 变更生成 |
|------|------|:--------:|:--------:|
| `track(entity)` | 纳入追踪：为对象建立初始快照基线 | ✅ | ✅ |
| `excludeNew(entity)` | 排除机制：标记新对象，不追踪 | ❌ | ❌ |
| `excludeRemoved(entity)` | 排除机制：标记删除对象，停止追踪 | ❌ | ❌ |

> `calculateChanges()` 是**幂等视图**：无副作用，重复调用返回相同变更集；
> 基线仅在 `track()` 时建立，如需推进基线，由调用方重新 `track()` 登记。

### 变更类型

| 类型 | 说明 |
|------|------|
| `ValueChange` | 基本值字段变更（叶子节点）；`oldValue()`/`newValue()` 为**业务值**，可安全强转 |
| `ObjectFieldChange` | 对象/集合字段**整体替换**（叶子节点）；携带 `ValueNode oldNode/newNode`，无业务值（快照不持业务对象引用） |
| `ContainerChange` | 容器（对象/集合）变更（仅 `getAllChanges()` 包含） |
| `ItemAddedChange` | 集合项新增 |
| `ItemRemovedChange` | 集合项删除 |

`ValueChange` 与 `ObjectFieldChange` 的分界（dispatch 表）：

| 字段两侧节点 | 变更类型 | 载荷 |
|------|------|------|
| 基本值之间（`PrimitiveNode↔PrimitiveNode`、`PrimitiveNode↔NullNode`、`NullNode↔NullNode`） | `ValueChange` | 业务值（`oldValue`/`newValue`） |
| 数组之间（`ArrayNode↔ArrayNode`，内容不等含顺序变） | `ValueChange` | 数组实例（可强转 `byte[]` 等） |
| 同类型容器（`ObjectNode↔ObjectNode`、`CollectionNode↔CollectionNode`） | 递归子节点（无叶子变更；子变更按上述规则） | — |
| 容器/数组参与的跨类型变化（对象→null、null→对象、对象→基本值、集合↔数组 等） | `ObjectFieldChange` | `ValueNode`（`NullNode`=清空、`ObjectNode`=赋值） |

### 路径访问

每个 `Change` 提供多种路径和元数据访问方式：
- `path()`：
  - 在扁平视图（`getLeafChanges()` / `getAllChanges()` 返回的列表）中为**完整路径**（与 `fullPath()` 相同）
  - 在树形视图（`ContainerChange.children()`）中为**相对路径**（相对于当前容器）
- `fullPath()`：从根到当前节点的完整路径（始终可用），如 `"items[1].name"`
- `fieldName()`：纯字段名（不含索引），如 `"items[1]"` 返回 `"items"`，`"[1]"` 返回 `null`
- `collectionFieldName()`：所属集合字段名，主表字段返回 `null`
- `isParentCollection()`：父节点是否为集合

```java
// 扁平视图：path() == fullPath()
for (Change change : changeSet.getLeafChanges()) {
    String path = change.path();          // "address.street" / "items[1].name"
    String fullPath = change.fullPath();  // 与 path 相同
}

// 树形视图：children() 的 path() 为相对路径
for (Change change : changeSet.getAllChanges()) {
    if (change instanceof ContainerChange cc) {
        for (Change child : cc.children()) {
            String relative = child.path();     // "street" / "[1]" / "name"
            String absolute = child.fullPath(); // "address.street" / "items[1]" / "items[1].name"
        }
    }
}
```

### 变更视图

- `getAllChanges()`：返回完整的树形结构，包含容器节点（树的前序遍历展平——每个容器和每个叶子恰好出现一次；同一变更同时出现在容器 children 与扁平列表中属双视图设计语义）
- `getLeafChanges()`：仅返回叶子节点（字段变更、项新增/删除），适合转换为数据库操作

### 快照节点类型

| 类型 | 说明 |
|------|------|
| `PrimitiveNode` | 基本类型/包装类/String/枚举/UUID/LocalDate 等（**值类型必须不可变**） |
| `ObjectNode` | 复杂对象；只读 API：`field(name)` / `forEachField(...)` / `identifier()`（不暴露集合引用） |
| `CollectionNode` | Collection 和 Map（作为 Entry 集合）；只读 API：`size()` / `item(index)` / `forEachItem(...)` |
| `ArrayNode` | 数组（**值语义，顺序敏感**，equals 为内容比较） |
| `NullNode` | null 值的专门表示 |

## 模块结构

```
change-tracking/
├── change-tracking-api/           # 公共 API 入口
│   └── ChangeTrackerFactory       # 工厂类，Builder 模式
│
└── change-tracking-core/          # 核心实现
    ├── spi/                       # SPI 扩展点
    │   ├── TrackingCapabilityProvider
    │   ├── SnapshotStrategy<S>
    │   └── CreationContext
    │
    ├── domain/
    │   ├── capability/            # 追踪能力
    │   │   ├── TrackingCapability<S>
    │   │   ├── ComparisonStrategy<S>
    │   │   ├── ValueNodeComparisonStrategy
    │   │   └── TrackingConfiguration
    │   │
    │   └── model/
    │       ├── tracking/          # 变更检测器
    │       │   └── ChangeTracker
    │       ├── snapshot/          # 快照模型（Snapshot 可扩展；默认 ValueNode sealed）
    │       │   ├── Snapshot<T>
    │       │   ├── ValueNodeSnapshot
    │       │   └── ValueNode (PrimitiveNode, ObjectNode, CollectionNode, ArrayNode, NullNode)
    │       └── changeset/         # 变更集模型 (sealed)
    │           ├── ChangeSet, ObjectChange
    │           ├── ChangeNode (FieldChangeNode, ObjectFieldChangeNode, ContainerChangeNode, ItemAddedNode, ItemRemovedNode)
    │           └── Change (ValueChange, ObjectFieldChange, ContainerChange, ItemAddedChange, ItemRemovedChange)
    │
    └── internal/                  # 内部实现
        ├── capability/
        │   ├── DefaultTrackingCapability
        │   └── DefaultTrackingCapabilityProvider
        ├── snapshot/
        │   └── ValueNodeSnapshotStrategy
        └── util/
            └── ReflectionUtils
```

## 架构设计

### 核心流程

```
1. ChangeTrackerFactory.builder().withDefaults().build()
   ↓ (ServiceLoader 发现 SPI)
2. ChangeTracker.track(entity)
   ↓ (创建初始快照基线)
3. 业务逻辑修改对象属性
   ↓
4. ChangeTracker.calculateChanges()
   ↓ (创建新快照，比较差异；幂等视图，不更新基线)
5. ChangeSet.getLeafChanges() / getAllChanges()
```

### 集合项匹配机制

框架使用**业务标识符**进行集合项匹配，而非基于索引：

1. 通过 `withIdentifier(Class, Function)` 配置标识符提取器
2. 查找为**类链 × 每层接口链统一递归**：对类型及每个父类，先查精确 key，再递归该类的接口链（接口 + 父接口，visited 防环）——父类实现的接口、接口继承链均能命中
3. 默认使用 `System.identityHashCode()` 作为回退

这允许：
- 检测集合项新增/删除/修改
- 集合重排序不产生虚假变更
- 适合 DDD 聚合根场景

### 数组语义

数组 = **值语义（顺序敏感）**：`{1,2,3}` 与 `{3,2,1}` 是**不同**的值，内容变化（含顺序变化）报告为变更；需要顺序无关语义的场景应使用 `List`（集合语义，identifier 匹配）。

- **值类型元素数组**（基本类型 / String / 枚举等，即元素不可变）→ `ArrayNode`（快照时防御拷贝：一维浅拷贝、多维递归深拷贝——数组可变，不拷贝会导致 track 后业务修改污染旧快照）
- **复杂对象元素数组**（如 `Order[]` / `Order[][]`）→ `CollectionNode` 递归展开，复用集合的 identifier 匹配逻辑（提取器按元素实际类查找，已注册的提取器自动生效）

### 不可变契约

- **快照节点**（`ObjectNode` / `CollectionNode`）：final class + 只读方法 API——不暴露任何集合引用，外部写操作在编译级不可能；`equals`/`hashCode`/`toString` 为内容语义，循环引用图上不栈溢出
- **`TrackingConfiguration`**：构造时 `Map.copyOf` / `Set.copyOf` 防御拷贝，getter 返回不可变集合——构造后外部修改不影响配置内部状态
- **`ChangeSet` / `ContainerChangeNode` / `ContainerChange`**：record + 构造期 `List.copyOf`
- **例外**：`ObjectNode`/`CollectionNode` 构造器不拷贝传入的 map/list——快照构建采用「先登记后填充」流程（循环引用支持），构造期拷贝会静默丢失填充内容；构建完成后传入集合即被框架接管，调用方不得再修改

### 值类型契约

视为值类型（`PrimitiveNode`）的类**必须不可变**——快照持有的是业务对象引用，值类型可变会导致 track 之后业务修改污染旧快照，变更静默丢失。可变对象一律按复杂对象脱水展开。

`AtomicBoolean` / `AtomicInteger` / `AtomicLong` 例外：其内部字段受 JDK 模块强封装无法反射脱水，快照时**读取当前值做拷贝**（`PrimitiveNode` 持有不可变值拷贝，不持有业务引用），同样满足不可变契约。

### 内存特性

- **快照是脱水后的 `ValueNode` 树**：不持有业务对象引用（`PrimitiveNode` 的值类型除外——值类型不可变，引用安全），业务对象后续修改不影响旧快照
- **快照创建期间**：`IdentityHashMap` visited 缓存持有所有被访问对象引用直到快照完成——大聚合根（万级 items）创建期间内存峰值约为对象图本身大小的引用开销；快照完成后缓存释放
- **变更集不缓存**：`getAllChanges()` / `getLeafChanges()` 每次调用实时转换（O(变更数)）；`calculateChanges()` 重复调用不累积内存

## 扩展开发

### 自定义追踪能力

```java
// 1. 实现 SnapshotStrategy
public class JsonSnapshotStrategy implements SnapshotStrategy<JsonSnapshot> {
    public JsonSnapshot createSnapshot(Object entity) { ... }
}

// 2. 实现 TrackingCapabilityProvider
public class JsonTrackingCapabilityProvider implements TrackingCapabilityProvider {
    public String getName() { return "json-jackson"; }
    public TrackingCapability<?> create() { ... }
}

// 3. 在 META-INF/services 中注册
// 创建文件: META-INF/services/com.nona.changeTracking.spi.TrackingCapabilityProvider
// 内容: com.example.JsonTrackingCapabilityProvider

// 4. 使用
ChangeTrackerFactory.builder()
    .withDefaults()
    .capability("json-jackson")
    .build();
```

## 系统要求

- Java 17+
- Maven 3.6+

## 许可证

MIT License
