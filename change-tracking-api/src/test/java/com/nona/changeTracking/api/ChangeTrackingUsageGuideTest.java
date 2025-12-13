package com.nona.changeTracking.api;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.changeTracking.domain.model.unitofwork.UnitOfWork;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Change Tracking Framework 使用指南测试类。
 * <p>
 * 这个测试类旨在通过具体的测试用例，展示框架的核心功能和使用方法。
 * 每个测试用例都是一个独立的使用场景示例。
 */
@DisplayName("Change Tracking 使用指南")
class ChangeTrackingUsageGuideTest {

    // ==================== 测试领域模型 ====================

    /**
     * 用户实体 - 演示简单对象的变更追踪
     */
    static class User {
        private Long id;
        private String name;
        private String email;
        private Address address;

        User(Long id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        // Getters and Setters
        Long getId() { return id; }
        String getName() { return name; }
        void setName(String name) { this.name = name; }
        String getEmail() { return email; }
        void setEmail(String email) { this.email = email; }
        Address getAddress() { return address; }
        void setAddress(Address address) { this.address = address; }
    }

    /**
     * 地址值对象 - 演示嵌套对象的变更追踪
     */
    static class Address {
        private String city;
        private String street;

        Address(String city, String street) {
            this.city = city;
            this.street = street;
        }

        String getCity() { return city; }
        void setCity(String city) { this.city = city; }
        String getStreet() { return street; }
        void setStreet(String street) { this.street = street; }
    }

    /**
     * 订单实体 - 演示集合的变更追踪
     */
    static class Order {
        private Long id;
        private String orderNumber;
        private List<LineItem> items = new ArrayList<>();

        Order(Long id, String orderNumber) {
            this.id = id;
            this.orderNumber = orderNumber;
        }

        Long getId() { return id; }
        String getOrderNumber() { return orderNumber; }
        List<LineItem> getItems() { return items; }
        void addItem(LineItem item) { items.add(item); }
        void removeItem(LineItem item) { items.remove(item); }
    }

    /**
     * 订单行项目 - 演示集合项的变更追踪
     */
    static class LineItem {
        private Long id;
        private String productName;
        private int quantity;
        private double price;

        LineItem(Long id, String productName, int quantity, double price) {
            this.id = id;
            this.productName = productName;
            this.quantity = quantity;
            this.price = price;
        }

        Long getId() { return id; }
        String getProductName() { return productName; }
        int getQuantity() { return quantity; }
        void setQuantity(int quantity) { this.quantity = quantity; }
        double getPrice() { return price; }
        void setPrice(double price) { this.price = price; }
    }

    // ==================== 基础用法 ====================

    @Nested
    @DisplayName("1. 基础用法")
    class BasicUsage {

        @Test
        @DisplayName("1.1 追踪简单字段变更")
        void trackSimpleFieldChange() {
            // 1. 创建 UnitOfWork 实例
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            // 2. 创建实体并注册为 "clean"（需要追踪变更的对象）
            User user = new User(1L, "张三", "zhangsan@example.com");
            uow.registerClean(user);

            // 3. 修改实体属性
            user.setName("李四");
            user.setEmail("lisi@example.com");

            // 4. 计算变更
            ChangeSet changeSet = uow.calculateChanges();

            // 5. 验证变更结果
            assertThat(changeSet.isEmpty()).isFalse();

            // 使用 getLeafChanges() 获取扁平的变更列表（不包含容器节点）
            List<Change> leafChanges = changeSet.getLeafChanges();
            assertThat(leafChanges).hasSize(2);

            // 验证具体的变更内容
            assertThat(leafChanges)
                    .filteredOn(c -> c instanceof FieldChange)
                    .extracting(c -> ((FieldChange) c).path())
                    .containsExactlyInAnyOrder("name", "email");
        }

        @Test
        @DisplayName("1.2 未修改对象不产生变更")
        void noChangeForUnmodifiedObject() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");
            uow.registerClean(user);

            // 不修改任何属性

            ChangeSet changeSet = uow.calculateChanges();

            // 没有变更
            assertThat(changeSet.isEmpty()).isTrue();
            assertThat(changeSet.getLeafChanges()).isEmpty();
        }
    }

    // ==================== 嵌套对象 ====================

    @Nested
    @DisplayName("2. 嵌套对象变更")
    class NestedObjectChanges {

        @Test
        @DisplayName("2.1 追踪嵌套对象的属性变更")
        void trackNestedObjectFieldChange() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");
            user.setAddress(new Address("北京", "朝阳区"));
            uow.registerClean(user);

            // 修改嵌套对象的属性
            user.getAddress().setCity("上海");
            user.getAddress().setStreet("浦东新区");

            ChangeSet changeSet = uow.calculateChanges();

            // 验证嵌套对象的变更
            List<Change> leafChanges = changeSet.getLeafChanges();
            assertThat(leafChanges).hasSize(2);
            assertThat(leafChanges)
                    .filteredOn(c -> c instanceof FieldChange)
                    .extracting(c -> ((FieldChange) c).path())
                    .containsExactlyInAnyOrder("address.city", "address.street");
        }

        @Test
        @DisplayName("2.2 将嵌套对象设置为 null")
        void setNestedObjectToNull() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");
            user.setAddress(new Address("北京", "朝阳区"));
            uow.registerClean(user);

            // 将嵌套对象设置为 null
            user.setAddress(null);

            ChangeSet changeSet = uow.calculateChanges();

            // 验证 address 字段变更为 null
            assertThat(changeSet.isEmpty()).isFalse();
        }
    }

    // ==================== 集合变更 ====================

    @Nested
    @DisplayName("3. 集合变更追踪")
    class CollectionChanges {

        @Test
        @DisplayName("3.1 追踪集合项新增")
        void trackCollectionItemAdded() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            Order order = new Order(1L, "ORD-001");
            order.addItem(new LineItem(1L, "商品A", 2, 100.0));
            uow.registerClean(order);

            // 新增集合项
            order.addItem(new LineItem(2L, "商品B", 1, 200.0));

            ChangeSet changeSet = uow.calculateChanges();

            // 验证集合项新增
            assertThat(changeSet.isEmpty()).isFalse();
            List<Change> leafChanges = changeSet.getLeafChanges();
            assertThat(leafChanges)
                    .filteredOn(c -> c instanceof ItemAddedChange)
                    .hasSize(1);
        }

        @Test
        @DisplayName("3.2 追踪集合项删除")
        void trackCollectionItemRemoved() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            LineItem item1 = new LineItem(1L, "商品A", 2, 100.0);
            LineItem item2 = new LineItem(2L, "商品B", 1, 200.0);
            Order order = new Order(1L, "ORD-001");
            order.addItem(item1);
            order.addItem(item2);
            uow.registerClean(order);

            // 删除集合项
            order.removeItem(item2);

            ChangeSet changeSet = uow.calculateChanges();

            // 验证集合项删除
            assertThat(changeSet.isEmpty()).isFalse();
            List<Change> leafChanges = changeSet.getLeafChanges();
            assertThat(leafChanges)
                    .filteredOn(c -> c instanceof ItemRemovedChange)
                    .hasSize(1);
        }

        @Test
        @DisplayName("3.3 追踪集合项属性修改")
        void trackCollectionItemModified() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            LineItem item = new LineItem(1L, "商品A", 2, 100.0);
            Order order = new Order(1L, "ORD-001");
            order.addItem(item);
            uow.registerClean(order);

            // 修改集合项的属性
            item.setQuantity(5);
            item.setPrice(150.0);

            ChangeSet changeSet = uow.calculateChanges();

            // 验证集合项属性变更
            assertThat(changeSet.isEmpty()).isFalse();
            List<Change> leafChanges = changeSet.getLeafChanges();
            assertThat(leafChanges)
                    .filteredOn(c -> c instanceof FieldChange)
                    .hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // ==================== 注册方法语义 ====================

    @Nested
    @DisplayName("4. 注册方法语义")
    class RegistrationSemantics {

        @Test
        @DisplayName("4.1 registerClean - 追踪属性变更")
        void registerClean_tracksPropertyChanges() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");

            // registerClean: 注册需要追踪属性变更的对象
            uow.registerClean(user);

            user.setName("李四");

            ChangeSet changeSet = uow.calculateChanges();

            // clean 对象的变更会被追踪
            assertThat(changeSet.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("4.2 registerNew - 不追踪新对象")
        void registerNew_doesNotTrackNewObjects() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");

            // registerNew: 标记为新对象，不追踪变更
            uow.registerNew(user);

            user.setName("李四");

            ChangeSet changeSet = uow.calculateChanges();

            // new 对象不产生变更（这是排除机制）
            assertThat(changeSet.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("4.3 registerRemoved - 停止追踪已删除对象")
        void registerRemoved_stopsTrackingRemovedObjects() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");
            uow.registerClean(user);

            user.setName("李四");

            // registerRemoved: 标记为已删除，停止追踪
            uow.registerRemoved(user);

            ChangeSet changeSet = uow.calculateChanges();

            // removed 对象不产生变更（这是排除机制）
            assertThat(changeSet.isEmpty()).isTrue();
        }
    }

    // ==================== 变更视图 ====================

    @Nested
    @DisplayName("5. 变更视图")
    class ChangeViews {

        @Test
        @DisplayName("5.1 getAllChanges - 完整树形视图")
        void getAllChanges_returnsCompleteTreeView() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");
            user.setAddress(new Address("北京", "朝阳区"));
            uow.registerClean(user);

            user.getAddress().setCity("上海");

            ChangeSet changeSet = uow.calculateChanges();

            // getAllChanges() 返回完整树形结构，包含 ContainerChange
            List<Change> allChanges = changeSet.getAllChanges();

            // 包含容器节点
            assertThat(allChanges)
                    .filteredOn(c -> c instanceof ContainerChange)
                    .isNotEmpty();
        }

        @Test
        @DisplayName("5.2 getLeafChanges - 仅叶子节点视图")
        void getLeafChanges_returnsOnlyLeafNodes() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");
            user.setAddress(new Address("北京", "朝阳区"));
            uow.registerClean(user);

            user.getAddress().setCity("上海");

            ChangeSet changeSet = uow.calculateChanges();

            // getLeafChanges() 只返回叶子节点，不包含 ContainerChange
            List<Change> leafChanges = changeSet.getLeafChanges();

            // 不包含容器节点，仅包含 FieldChange、ItemAddedChange、ItemRemovedChange
            assertThat(leafChanges)
                    .filteredOn(c -> c instanceof ContainerChange)
                    .isEmpty();

            assertThat(leafChanges)
                    .filteredOn(c -> c instanceof FieldChange)
                    .isNotEmpty();
        }
    }

    // ==================== 变更内容提取 ====================

    @Nested
    @DisplayName("6. 变更内容提取")
    class ChangeContentExtraction {

        @Test
        @DisplayName("6.1 提取字段变更的详细信息")
        void extractFieldChangeDetails() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");
            uow.registerClean(user);

            user.setName("李四");

            ChangeSet changeSet = uow.calculateChanges();

            // 遍历变更，提取详细信息
            for (Change change : changeSet.getLeafChanges()) {
                if (change instanceof FieldChange fieldChange) {
                    String path = fieldChange.path();       // 字段路径: "name"
                    Object oldValue = fieldChange.oldValue(); // 旧值: "张三"
                    Object newValue = fieldChange.newValue(); // 新值: "李四"

                    assertThat(path).isEqualTo("name");
                    assertThat(oldValue).isEqualTo("张三");
                    assertThat(newValue).isEqualTo("李四");
                }
            }
        }

        @Test
        @DisplayName("6.2 遍历 ObjectChange 获取被追踪对象")
        void iterateObjectChanges() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user1 = new User(1L, "张三", "zhangsan@example.com");
            User user2 = new User(2L, "王五", "wangwu@example.com");
            uow.registerClean(user1);
            uow.registerClean(user2);

            user1.setName("李四");
            user2.setEmail("wangwu_new@example.com");

            ChangeSet changeSet = uow.calculateChanges();

            // 遍历每个对象的变更
            for (ObjectChange objectChange : changeSet.changes()) {
                Object target = objectChange.target();  // 被追踪的对象
                ChangeNode changeTree = objectChange.changeTree();  // 变更树

                assertThat(target).isIn(user1, user2);
                assertThat(changeTree).isNotNull();
            }

            assertThat(changeSet.changes()).hasSize(2);
        }
    }

    // ==================== 多对象追踪 ====================

    @Nested
    @DisplayName("7. 多对象追踪")
    class MultipleObjectTracking {

        @Test
        @DisplayName("7.1 同时追踪多个对象")
        void trackMultipleObjects() {
            UnitOfWork uow = UnitOfWorkFactory.builder()
                    .withDefaults()
                    .build();

            User user = new User(1L, "张三", "zhangsan@example.com");
            Order order = new Order(1L, "ORD-001");

            // 注册多个对象
            uow.registerClean(user);
            uow.registerClean(order);

            // 修改多个对象
            user.setName("李四");
            order.addItem(new LineItem(1L, "商品A", 1, 100.0));

            ChangeSet changeSet = uow.calculateChanges();

            // 两个对象都有变更
            assertThat(changeSet.changes()).hasSize(2);
        }
    }
}
