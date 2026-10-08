# 迁移说明：统一变更结果模型

从旧版变更结果模型（`ChangeNode` 中间表示）升级到统一变更模型（单一 `Change` 层次）的迁移说明。

## 概述

比较策略现在直接产出可供消费的变更树：`Change` 的五个实现（`ValueChange`、`ObjectFieldChange`、`ContainerChange`、`ItemAddedChange`、`ItemRemovedChange`）同时承担原子变化与分组表达，`ChangeSet` 的完整视图与叶子视图由同一份结果节点派生。`ChangeNode` 中间层次及其到扁平视图的转换链路已移除；快照层（`ValueNode`、`ValueNodeSnapshotStrategy`）不变。

## 兼容性结论

本次调整属不兼容变更，升级方必须重新编译：

- 方法返回类型变化（`ComparisonStrategy.compare`）与 record 组件变化（`ObjectChange`）都会破坏已有二进制链接（见 [JLS 13.4.15 Binary Compatibility](https://docs.oracle.com/javase/specs/jls/se25/html/jls-13.html#jls-13.4.15)）。
- 已编译的第三方比较策略与直接构造变更记录类型的代码在升级后不可二进制链接，须按下表迁移并重新编译。
- 不提供兼容转换链路：尚未升级的外部策略继续绑定旧版。

## 逐项迁移

| 变更项 | 迁移要求 |
|---|---|
| 比较 SPI 返回类型 | `ComparisonStrategy.compare` 返回非 `null` 的只读 `List<Change>`；空比较返回空列表，不返回 `null`，不产包装根节点 |
| `ObjectChange` 组件与构造器 | 由持有根节点改为持有目标根下的非空结果列表：`ObjectChange(target, changes)`，无人工根容器；更新手工构造、访问与模式匹配代码 |
| 定位访问 | 位置、字段与集合关系集中在不可变 `ChangeLocation`；五类变更的定位访问是对它的薄委托。`path()` 与 `fullPath()` 均返回完整路径，相对路径改用 `relativePath()` |
| `Change` 与具体 record | 按最终模型更新构造、解构与相等性断言后重新编译；五个具体类型保留 |
| 空容器与空单目标结果 | 空 `ContainerChange` 与空 `ObjectChange` 不再是合法结果，构造即拒绝；无变化使用空比较列表或空 `ChangeSet` 表达 |
| 完整视图的路径与集合元数据 | `getAllChanges()` 展开为完整路径列表；集合元数据取实际上下文，不再由扁平视图重置 |
| 空路径叶子 | 真实根值变化以空路径原子变化出现在完整视图与叶子视图，消费方更新预期 |
| 非根空路径节点 | 嵌于非根容器下的空路径分组或叶子不再合法，由结果构建入口拒绝 |
| 视图节点复用 | 视图直接选择结果节点；公开契约不承诺跨入口的 Java 引用身份相等，不得新增 `==` 依赖 |
| 失败行为 | `null` 策略结果由追踪器以结果契约违反拒绝（`IllegalStateException`）；非法包含定位与空结果结构在构造时拒绝（`IllegalArgumentException`）；快照类型不匹配沿用 `ClassCastException`；失败不推进追踪基线 |
| 默认装配 | 默认工厂、provider 名称与快照 SPI 装配组合沿用，无需改动 |

## 行为差异

- **嵌套匿名集合项的集合归属**：当集合的元素本身是集合或数组时，内层集合项（如 `outer[pos:0][a]`）的 `collectionFieldName()` 取包含位置自身的字段名；包含位置是集合项（无字段名）时返回 `null`，不再回溯为外层集合字段名（`outer`）。这与「集合项不伪造字段名、根集合不伪造名称」的定位语义同源。

## 迁移示例

```java
// 旧：比较策略产出节点树
ChangeNode compare(S oldSnapshot, S newSnapshot);

// 新：比较策略产出统一变更列表，空列表表示无变化
List<Change> compare(S oldSnapshot, S newSnapshot);

// 旧：结果持有变更树的根节点
ObjectChange objectChange = new ObjectChange(target, changeTree);

// 新：直接持有目标根下的结果列表
ObjectChange objectChange = new ObjectChange(target, List.of(valueChange, container));

// 旧：树视图子节点的 path() 是相对路径
String relative = change.path();

// 新：path() 与 fullPath() 都是完整路径，相对路径显式获取
String relative = change.relativePath();
```
