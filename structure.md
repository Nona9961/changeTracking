# 结构文档

> 代码地图：模块划分、分层与依赖方向（概述级）。类级职责与契约见各文件 Javadoc。
> 本文件描述当前现状，随代码演进更新。

## 模块划分

| 模块 | 职责 | 项目内依赖 |
|------|------|-----------|
| `change-tracking-api` | 公共 API：`ChangeTrackerFactory` 对外入口 | 依赖 `change-tracking-core`（当前现状，见下注） |
| `change-tracking-core` | 全部实现：领域模型、能力、SPI、内部实现 | 无 |

> 注：模块命名约定为 "api 不依赖 core"，但当前 `change-tracking-api/pom.xml` 实际依赖
> core（工厂实现位于 core 内部）。本表如实记录现状，命名约定与实现不一致的问题待解决。

## 分层结构（core 内部）

自底向上：

1. `domain` —— 领域模型：`model/snapshot`（快照）、`model/changeset`（变更集）、
   `model/tracking`（注册追踪）、`capability`（追踪配置与比较能力）
2. `spi` —— 扩展点接口：`SnapshotStrategy`、`ComparisonStrategy`、`TrackingCapabilityProvider`、
   `CreationContext`，供自定义实现
3. `internal` —— 默认实现与工具：`snapshot`（反射快照策略）、`capability`（默认能力）、
   `util`（反射工具），依赖 domain 与 spi

依赖方向：`internal` → `spi` → `domain`；`api` 模块 → core。

## 包地图

| 包 | 职责 |
|----|------|
| `com.nona.changeTracking.api`（api 模块） | `ChangeTrackerFactory` 公共入口 |
| `com.nona.changeTracking.domain.model.snapshot` | 快照节点模型（只读对象树镜像） |
| `com.nona.changeTracking.domain.model.changeset` | 变更集模型（树形 + 扁平双视图） |
| `com.nona.changeTracking.domain.model.tracking` | 追踪器：注册、快照推进、变更计算 |
| `com.nona.changeTracking.domain.capability` | 追踪配置：标识符提取、比较策略 |
| `com.nona.changeTracking.spi` | 扩展点接口 |
| `com.nona.changeTracking.internal.snapshot` | 默认快照策略（反射实现） |
| `com.nona.changeTracking.internal.capability` | 默认能力与能力提供者 |
| `com.nona.changeTracking.internal.util` | 反射工具 |

## 阅读起点

1. `ChangeTrackerFactory`（api 模块）——公共入口，从哪开始用
2. `ChangeTracker`（domain/model/tracking）——追踪流程：注册 → 快照 → 计算变更
3. `ChangeSet` / `Change`（domain/model/changeset）——变更结果怎么读
4. `internal` 包——默认实现如何工作
