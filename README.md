# Change Tracking Framework

一个轻量级的 Java **属性级变更追踪框架（变更检测器）**，支持嵌套对象和集合的深度比较，通过 SPI 机制灵活扩展。

> **命名说明**：本框架本质是**变更检测器**——注册对象 → 检测属性变更（UPDATE），
> 不管理 INSERT/UPDATE/DELETE 全生命周期，因此核心类命名为 `ChangeTracker`
> 而非经典工作单元（Unit of Work）。`excludeNew` / `excludeRemoved` 是**排除机制**
> （标记不追踪），不是生命周期语义。

## 动机

DDD 项目中，聚合根在内存中完成业务操作后，持久化层需要精确知道**哪些属性变了**，才能生成准确的 UPDATE。
传统 ORM 脏检查与数据库会话绑定，且无法表达集合重排与增删的区别。

changeTracking 以框架无关（不绑定 ORM）的方式解决这个问题：注册对象 → 建立快照基线 → 对比生成
属性级变更集，由持久化层消费。快照与变更集都是普通 Java 对象，任何持久化方案（JPA / MyBatis / 自研）
都可以使用。

## 亮点

- **属性级精确追踪**：字段级变更检测，覆盖嵌套对象与集合的深度比较
- **业务标识符匹配**：集合项按业务标识符（而非索引）匹配——集合重排序不产生虚假变更，适合 DDD 聚合根
- **明确的值语义**：数组按值比较（顺序敏感）；基本值、对象、集合的变更类型清晰可辨
- **双视图变更集**：树形视图保留完整结构，扁平视图可直接转换为数据库操作
- **循环引用安全**：快照构建与差异比较在循环对象图上不栈溢出
- **SPI 可扩展**：快照策略、比较策略、标识符提取器均可插拔，`ServiceLoader` 自动发现
- **基线安全**：快照持有不可变拷贝，`track()` 之后修改业务对象不污染基线；反射使用无需 JVM 参数

## 快速使用

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

// 4. 计算变更（重复调用返回相同结果；基线不推进，需要推进时重新 track()）
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

## 系统要求

- Java 25
- Maven 3.6+

## 许可证

MIT License
