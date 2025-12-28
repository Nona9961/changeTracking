# Change Tracking Framework

一个轻量级的 Java 属性级变更追踪框架，基于工作单元（Unit of Work）模式设计，支持嵌套对象和集合的深度比较。通过 SPI 机制支持灵活扩展。

## 特性

- **属性级变更追踪**：精确追踪对象属性的变化，包括嵌套对象和集合
- **业务标识符匹配**：基于业务标识符（而非索引）进行集合项匹配，支持集合重排序
- **SPI 扩展机制**：通过 `ServiceLoader` 支持自定义追踪能力
- **双视图变更表示**：`getAllChanges()` 树形视图 和 `getLeafChanges()` 扁平视图
- **循环引用处理**：自动检测和处理对象间的循环引用
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
// 1. 创建 UnitOfWork
UnitOfWork uow = UnitOfWorkFactory.builder()
    .withDefaults()
    .build();

// 2. 注册需要追踪的对象
User user = userRepository.findById(1L);
uow.registerClean(user);

// 3. 修改对象属性
user.setName("新名称");
user.setEmail("new@example.com");

// 4. 计算变更
ChangeSet changeSet = uow.calculateChanges();

// 5. 获取变更列表
List<Change> leafChanges = changeSet.getLeafChanges();
for (Change change : leafChanges) {
    if (change instanceof FieldChange fc) {
        System.out.printf("字段 %s: %s -> %s%n",
            fc.path(), fc.oldValue(), fc.newValue());
    }
}
```

### 配置业务标识符

```java
// 通过 Provider 配置业务标识符提取器
DefaultTrackingCapabilityProvider provider = new DefaultTrackingCapabilityProvider();
provider.withIdentifier(Order.class, Order::getId)
        .withIdentifier(LineItem.class, LineItem::getSku)
        .withValueType(Money.class)           // 自定义值类型
        .withValuePackage("com.example.vo");  // 整个包的值类型
```

## 核心概念

### 注册方法语义

| 方法 | 说明 | 快照创建 | 变更生成 |
|------|------|:--------:|:--------:|
| `registerClean(entity)` | 注册需要追踪属性变更的对象 | ✅ | ✅ |
| `registerNew(entity)` | 排除机制：标记新对象，不追踪 | ❌ | ❌ |
| `registerRemoved(entity)` | 排除机制：标记删除对象，停止追踪 | ❌ | ❌ |

### 变更类型

| 类型 | 说明 |
|------|------|
| `FieldChange` | 字段值变更（叶子节点） |
| `ContainerChange` | 容器（对象/集合）变更（仅 `getAllChanges()` 包含） |
| `ItemAddedChange` | 集合项新增 |
| `ItemRemovedChange` | 集合项删除 |

### 路径访问

每个 `Change` 提供两种路径访问方式：
- `path()`：相对路径，如 `"name"`
- `fullPath()`：从根到当前节点的完整路径，如 `"items[1].name"`

```java
for (Change change : changeSet.getLeafChanges()) {
    String relativePath = change.path();      // "name"
    String absolutePath = change.fullPath();  // "items[1].name"
}
```

### 变更视图

- `getAllChanges()`：返回完整的树形结构，包含容器节点
- `getLeafChanges()`：仅返回叶子节点（字段变更、项新增/删除），适合转换为数据库操作

### 快照节点类型

| 类型 | 说明 |
|------|------|
| `PrimitiveNode` | 基本类型/包装类/String/枚举/UUID/LocalDate 等 |
| `ObjectNode` | 复杂对象，包含字段映射和业务标识符 |
| `CollectionNode` | Collection 和 Map（作为 Entry 集合） |
| `NullNode` | null 值的专门表示 |

## 模块结构

```
change-tracking/
├── change-tracking-api/           # 公共 API 入口
│   └── UnitOfWorkFactory          # 工厂类，Builder 模式
│
└── change-tracking-core/          # 核心实现
    ├── spi/                       # SPI 扩展点
    │   ├── TrackingCapabilityProvider
    │   ├── SnapshotStrategy
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
    │       ├── unitofwork/        # 工作单元
    │       │   └── UnitOfWork
    │       ├── snapshot/          # 快照模型 (sealed)
    │       │   ├── Snapshot<T>
    │       │   ├── ValueNodeSnapshot
    │       │   └── ValueNode (PrimitiveNode, ObjectNode, CollectionNode, NullNode)
    │       └── changeset/         # 变更集模型 (sealed)
    │           ├── ChangeSet, ObjectChange
    │           ├── ChangeNode (FieldChangeNode, ContainerChangeNode, ItemAddedNode, ItemRemovedNode)
    │           └── Change (FieldChange, ContainerChange, ItemAddedChange, ItemRemovedChange)
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
1. UnitOfWorkFactory.builder().withDefaults().build()
   ↓ (ServiceLoader 发现 SPI)
2. UnitOfWork.registerClean(entity)
   ↓ (创建初始快照)
3. 业务逻辑修改对象属性
   ↓
4. UnitOfWork.calculateChanges()
   ↓ (创建新快照，比较差异)
5. ChangeSet.getLeafChanges() / getAllChanges()
```

### 集合项匹配机制

框架使用**业务标识符**进行集合项匹配，而非基于索引：

1. 通过 `withIdentifier(Class, Function)` 配置标识符提取器
2. 支持继承链和接口查找
3. 默认使用 `System.identityHashCode()` 作为回退

这允许：
- 检测集合项新增/删除/修改
- 集合重排序不产生虚假变更
- 适合 DDD 聚合根场景

## 扩展开发

### 自定义追踪能力

```java
// 1. 实现 SnapshotStrategy
public class JsonSnapshotStrategy implements SnapshotStrategy {
    public Snapshot<?> createSnapshot(Object entity) { ... }
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
UnitOfWorkFactory.builder()
    .withDefaults()
    .capability("json-jackson")
    .build();
```

## 系统要求

- Java 17+
- Maven 3.6+

## 许可证

MIT License