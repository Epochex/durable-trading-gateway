# Durable Trading Gateway

Java 17 / Spring Boot 交易指令网关，扩展 [exchange-core](https://github.com/exchange-core/exchange-core) 的 HTTP 接入、请求幂等、指令持久化与崩溃恢复。

网关维护指令顺序和执行回执；撮合、风控与账户计算由上游引擎执行。上游基线为 `2f8548749839e9095c8dc597e4b61521d259fa5d`，保留 Apache-2.0 许可证与原始提交历史。[上游原 README](docs/upstream-README.md) 中的性能数据属于原引擎。

## 执行过程

```text
HTTP 请求
  -> 参数校验
  -> 有界队列 / 满载拒绝
  -> 单写线程：幂等检查 -> WAL force -> 引擎执行 -> 保存回执
  -> HTTP 响应

启动恢复：文件锁 -> 校验日志帧 -> 从固定初始状态重放 -> 重建状态和回执 -> 开放受理
```

- **并发与读取顺序**：单线程拥有幂等表和指令序号；查询也进入同一队列，再从引擎取得订单簿副本。HTTP 控制器等待 `CompletableFuture` 完成。
- **请求幂等**：`(uid, key)` 标识请求；同号同参复用回执，同号异参返回 HTTP 409。业务风控拒绝也保留原结果，队列拒绝发生在写日志之前。
- **写前日志**：16 字节帧头包含魔数、长度、长度反码和 CRC32C；事件保存格式版本、连续序号和完整请求。`FileChannel.force(true)` 完成后才提交引擎。
- **状态恢复**：从相同初始配置顺序重放，重建订单、账户及幂等表。半写尾帧截断到最后完整记录；完整帧损坏、序号跳跃、重复键或不支持的格式版本会阻止启动。
- **结果未知**：引擎执行超时后暂停后续操作，重启重放恢复；客户端重试仍须沿用原 `uid` 和 `key`。
- **资源关闭**：停止准入，等待队列排空，再关闭引擎和日志。文件锁限定一个进程拥有同一份 WAL。

## 构建与运行

需要 JDK 17 和 Maven 3.9+。先在仓库根目录安装引擎，再构建网关：

```sh
mvn -B -ntp -f pom.xml '-P!default' -Dmaven.test.skip=true install
mvn -B -ntp -f reliable-gateway/pom.xml clean verify
```

第一条关闭上游的发布和 delombok profile，安装本仓库的引擎代码；第二条运行网关全部测试并生成可执行 JAR。上游性能实验不包含在这两条命令的验证范围内。

Linux / macOS：

```sh
java --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED \
  -jar reliable-gateway/target/reliable-trading-gateway-0.1.0-SNAPSHOT.jar \
  --trading.journal=./data/commands.wal
```

Windows PowerShell（`JAVA_HOME` 指向 JDK 17、Maven 在 PATH 中）：

```powershell
./reliable-gateway/build.ps1 package
./reliable-gateway/build.ps1 run
```

默认监听 `127.0.0.1:8287`；队列容量由 `trading.queue-capacity` 配置，默认 1024。WAL 路径由 `trading.journal` 配置，默认 `data/commands.wal`，相对于启动目录。

## HTTP 示例

以下示例使用 Bash 的 curl；PowerShell 可使用 `Invoke-RestMethod` 或 `curl.exe`。

```sh
curl -sS http://127.0.0.1:8287/orders \
  -H 'Content-Type: application/json' \
  -d '{"uid":1,"key":"sell-001","side":"ASK","orderType":"GTC","price":100,"size":10}'
# {"sequence":1,"orderId":1,"result":"SUCCESS"}

curl -sS http://127.0.0.1:8287/book

curl -sS http://127.0.0.1:8287/orders/1/cancel \
  -H 'Content-Type: application/json' \
  -d '{"uid":1,"key":"cancel-001"}'

curl -sS http://127.0.0.1:8287/status
```

新建空日志时，上述下单产生订单 1；再次发送相同请求返回原回执。保持 key 不变而修改价格会返回 HTTP 409。参数错误返回 HTTP 400，队列满或网关故障返回 HTTP 503；业务拒绝放在回执的 `result` 中，与传输错误分别处理。

## 代码与验证

| 文件 | 职责 |
| --- | --- |
| `DurableGateway.java` | 有界准入、单写线程、幂等、故障标记和回执 |
| `FileJournal.java` | 独占文件锁、帧校验、强制刷盘及尾帧恢复 |
| `EngineSession.java` | 引擎初始化、指令映射、订单簿副本及资金守恒报告 |
| `TradingController.java` / `TradingErrors.java` | HTTP 接口、输入校验和异常映射 |

实现位于 [`reliable-gateway/src`](reliable-gateway/src)。JUnit 测试覆盖并发重复请求、同号异参、用户命名空间、部分成交与撤单后恢复、拒绝回执重放、刷盘后无回执恢复、队列饱和、故障后拒绝、日志损坏和第二写进程；HTTP 接口使用 Spring MockMvc 验证。

当前模型固定为交易对 `1001`，用户 `1`、`2`，各币种初始余额均为 `1_000_000_000`，使用整数价格和数量，支持 GTC / IOC。恢复要求保持相同初始配置、业务规则和引擎版本。日志与历史幂等记录完整保留，恢复成本随指令历史增长；逐条刷盘与串行执行优先保证状态语义。当前没有快照截断、多副本协调、身份鉴权或外部资金接入。

## 来源与许可证

撮合、风控、账户计算和原始测试来自 [exchange-core/exchange-core](https://github.com/exchange-core/exchange-core)。本仓库新增 `reliable-gateway/`、构建验证工作流及文档；上游测试中的 Nashorn `@Ignore` 替换为 JUnit 5 `@Disabled`，以兼容 JDK 17。[LICENSE.txt](LICENSE.txt) 保留 Apache License 2.0。
