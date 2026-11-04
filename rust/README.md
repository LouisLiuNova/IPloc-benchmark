# Rust 候选（not_implemented）

本轮只保留 workspace，不提供 Rust core、JNI 或性能成绩。Java 构建无需 Rust 工具链。

后续实现必须通过 `iprange-api` 的公共契约，以 `rust-jni` 注册候选工厂；复用契约测试、相同输入和 JMH 路径。使用同等连续边界及 UTF-8 载荷布局，先完成正确性，再独立进程交替评估。JNI 加载、句柄与关闭规则遵循原技术设计。禁止以纯 Java 代理或空实现生成 Rust 成绩。
