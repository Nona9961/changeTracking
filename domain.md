# 领域文档

> 本文件回答三个问题：这个项目属于什么领域、做什么事情、代码如何映射到领域。
> 它是当前状态的快照，随代码演进更新；设计理由与决策记录不在此处。

## 领域定位

changeTracking 属于**变更检测（change detection）**领域：它不感知业务语义，只回答一个
问题——"对象的属性状态发生了什么变化"。

changeTracking 是一个**独立的对象属性级变更检测框架**：对任意 Java 对象树建立快照，
两次快照之间计算差异并产出结构化变更集，供持久化层等消费方使用。

## 做什么

1. **注册追踪**：对任意 Java 对象（或其标识符）建立追踪，框架自动为对象树建立快照。
2. **计算变更**：两次快照之间计算差异，生成结构化变更集——精确到"哪个对象的哪个属性
   变了"以及"集合中哪个元素被增删"。
3. **呈现变更**：变更集以树形与扁平两种视图呈现，消费方（如持久化层）按属性路径定位变更。
4. **按需扩展**：快照如何建立、值如何比较、标识符如何提取，均可通过 SPI 替换默认实现。

## 领域概念与代码映射

| 领域概念 | 是什么 | 代码位置 |
|---------|--------|---------|
| 注册器 / 追踪器 | 追踪的入口：注册对象、推进快照、取回变更集 | `domain/model/tracking` → `ChangeTracker` |
| 快照 | 对象树在某个时刻的状态镜像（只读），是变更计算的基础 | `domain/model/snapshot` → `Snapshot`、`ObjectNode`、`ValueNode`、`ArrayNode`、`CollectionNode`、`PrimitiveNode`、`NullNode` |
| 变更集 | 两个快照之间差异的结构化结果，树形 + 扁平双视图 | `domain/model/changeset` → `ChangeSet`、`ChangeNode`、`FieldChangeNode`、`ContainerChangeNode`、`ItemAddedNode`、`ItemRemovedNode` |
| 变更 | 单个属性或集合元素的变化描述 | `domain/model/changeset` → `Change`、`ValueChange`、`ObjectFieldChange`、`ContainerChange`、`ItemAddedChange`、`ItemRemovedChange` |
| 追踪配置 | 一次追踪的参数：标识符提取、比较策略 | `domain/capability` → `TrackingCapability`、`TrackingConfiguration`、`ComparisonStrategy` |
| SPI 扩展点 | 自定义快照策略 / 比较策略 / 标识符提取器 / 能力提供者 | `spi` 包 → `SnapshotStrategy`、`ComparisonStrategy`、`TrackingCapabilityProvider`、`CreationContext` |
| 默认实现 | 反射快照、类型驱动的比较、基于方法名的标识符提取 | `internal` 包 → `ValueNodeSnapshotStrategy`、`ValueNodeComparisonStrategy`、`ReflectionUtils` |
| 对外入口 | 框架的公共 API（工厂） | `change-tracking-api` 模块 → `ChangeTrackerFactory` |

## 边界

- 不管会话与事务：对象生命周期与事务边界由消费方管理。
- 不管持久化：框架只产出变更描述，不写库。
- 不管业务语义：不感知领域对象的具体含义，只做结构化的属性级比较。
