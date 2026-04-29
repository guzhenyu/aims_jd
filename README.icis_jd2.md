# icis_jd2

`icis_jd2` 是 `icis_jd` 的渐进式重构版。

当前仓库已经不是“只有骨架”的状态，而是已经落地了可运行的新链路：

- 内置 6 个驱动全部走 `DriverPlugin + FrameDecoder + SessionRuntime + ObservationPublisher`
- 入站 TCP 设备仍由 `JdTcpServer + DeviceConnManager` 承载连接生命周期
- 迈瑞 PDS realtime 已作为独立 outbound supervisor 接入
- `LegacyDeviceConnHandler` 仍保留，但当前内置驱动不再依赖它

这份 README 面向两类读者：

- 人：快速理解工程架构、配置入口、扩展点和排障入口
- Codex：快速定位“新增驱动、改参数映射、改 source/target 行为”应该改哪些文件

如果你只想先抓住一句话：

`icis_jd2` 当前的主实现已经是“连接壳/会话运行时/协议驱动/统一发布”分层，且同时支持 inbound source 和 PDS outbound source 两种采集模式。

## 1. 当前真实架构

### 1.1 两条主运行链路

当前仓库存在两条真实运行链路。

#### A. 入站 TCP source 设备链路

```text
设备主动 TCP 连接
  -> JdTcpServer
  -> DeviceConnManager.accept/read/heartbeatDevices
  -> DeviceConnFactory.newHandler(...)
  -> PluginBackedDeviceConnHandler
  -> SessionRuntime
  -> FrameAccumulator + FrameDecoder
  -> ProtocolSession
  -> ObservationPublisher
  -> DataBridgeServiceClient
  -> DataBridge gRPC
```

这条链路用于：

- `jd_cms_philips_intellivue`
- `jd_cms_mindray_egateway`
- `jd_vent_mindray_sv300`
- `jd_vent_drager_evita`
- `jd_bga_radiometer_abl90`

#### B. 迈瑞 PDS realtime outbound 链路

```text
MindrayPdsRealtimeSupervisor
  -> DataBridgeService.GetAllDevices
  -> 识别 PDS source + target
  -> 向 PDS bed list 端口主动连接并拉在线监护仪列表
  -> 合法 target 与在线列表取交集
  -> 每个 target 启一条 realtime session
  -> SessionRuntime
  -> MindrayPdsRealtime
  -> ObservationPublisher
  -> DataBridgeServiceClient
```

这条链路用于：

- `jd_cms_mindray_pds_realtime`

注意：当前仓库里虽然有 `SourceSupervisor` / `InMemorySourceSupervisor`，但它们还是预留抽象，不是现网主链路核心。

### 1.2 当前的职责分层

当前代码可以按下面这几层理解：

- 连接层：`DeviceConnHandler`
  - 只保留 socket 读、心跳、关闭、并发防抖
- 运行时层：`SessionRuntime`
  - 负责 `chunk -> frame -> protocol session`
- 分帧层：`FrameDecoder` / `FrameAccumulator`
  - 把字节流切成完整协议帧，并做 overflow 防护
- 驱动层：`DriverPlugin`
  - 每个连接/会话一个 plugin 实例
- 发布层：`ObservationPublisher`
  - 驱动不再直接依赖 `DataBridgeServiceClient`

### 1.3 Legacy 的当前位置

`LegacyDeviceConnHandler` 还在，作用是兼容层。

但当前项目内置驱动已经都不再走这条路径：

- 不再依赖 legacy 的 `messageBuffer + messageQueue + progress()`
- 不再直接在驱动里调用 `bridgeClient.addDeviceData(...)`
- 不再直接在驱动里调用 `bridgeClient.addRawBgaRecord(...)`

如果你看到它，应该把它当成“保底兼容层”，而不是当前主扩展点。

## 2. 目录索引

### 2.1 关键目录

- `src/main/java/com/jingyicare/icis_jd/service`
  - 进程级服务。TCP 服务端、设备定义加载、PDS outbound supervisor 在这里。
- `src/main/java/com/jingyicare/icis_jd/driver`
  - 连接壳、工厂、legacy 兼容层。
- `src/main/java/com/jingyicare/icis_jd/driver/api`
  - `DriverContext`、`DriverPlugin`、`SourceMode`、`SourceTopology` 等抽象。
- `src/main/java/com/jingyicare/icis_jd/driver/frame`
  - 分帧器和缓冲器。
- `src/main/java/com/jingyicare/icis_jd/driver/session`
  - 协议状态机接口与统一 runtime。
- `src/main/java/com/jingyicare/icis_jd/driver/registry`
  - 驱动注册中心。
- `src/main/java/com/jingyicare/icis_jd/driver/impl`
  - 具体驱动实现。
- `src/main/java/com/jingyicare/icis_jd/driver/impl/base`
  - 驱动公共基类。
- `src/main/java/com/jingyicare/icis_jd/driver/model`
  - `Frame`、`ObservationBatch`、`BgaBatch`、PDS 目标标识等模型。
- `src/main/java/com/jingyicare/icis_jd/tool/publisher`
  - 统一发布接口和 gRPC 落地实现。
- `src/main/resources/config/device_driver.txt`
  - 驱动配置和参数映射总表。
- `src/main/proto`
  - 设备定义、BGA、DataBridge gRPC proto。
- `src/test/java`
  - 当前已有驱动、工厂、分帧器、publisher 的单元测试。

### 2.2 最关键文件

| 文件 | 作用 |
| --- | --- |
| `src/main/java/com/jingyicare/icis_jd/service/JdTcpServer.java` | 入站 TCP 服务端 selector |
| `src/main/java/com/jingyicare/icis_jd/service/DeviceConnManager.java` | 同步设备定义、接入 IP、调度 read/heartbeat，同时实现 `DriverDefinitionRepository` |
| `src/main/java/com/jingyicare/icis_jd/service/MindrayPdsRealtimeSupervisor.java` | PDS bed list + realtime outbound 采集管理器 |
| `src/main/java/com/jingyicare/icis_jd/driver/DeviceConnFactory.java` | `driver_code -> plugin handler` 工厂，内置 driver 已全注册到 registry |
| `src/main/java/com/jingyicare/icis_jd/driver/DeviceConnHandler.java` | 薄连接生命周期基类 |
| `src/main/java/com/jingyicare/icis_jd/driver/PluginBackedDeviceConnHandler.java` | 入站 plugin runtime 承载层 |
| `src/main/java/com/jingyicare/icis_jd/driver/LegacyDeviceConnHandler.java` | legacy 兼容层，内置驱动已不使用 |
| `src/main/java/com/jingyicare/icis_jd/driver/frame/FrameAccumulator.java` | 流式分帧与 overflow 保护 |
| `src/main/java/com/jingyicare/icis_jd/driver/session/SessionRuntime.java` | 统一会话 runtime |
| `src/main/java/com/jingyicare/icis_jd/tool/publisher/ObservationPublisher.java` | 统一发布出口 |
| `src/main/java/com/jingyicare/icis_jd/tool/publisher/GrpcObservationPublisher.java` | `ObservationBatch/BgaBatch -> DataBridge gRPC` |
| `src/main/java/com/jingyicare/icis_jd/driver/impl/base/AbstractHl7MllpCentralStationDriver.java` | HL7/MLLP 中央站驱动公共基类 |
| `src/main/java/com/jingyicare/icis_jd/driver/impl/base/AbstractSingleDeviceProtocolDriver.java` | 单设备协议驱动公共基类 |

## 3. 当前内置驱动

当前内置 6 个驱动全部已经迁到新骨架。

| driver_code | 类 | source_mode | source_topology | 协议类型 |
| --- | --- | --- | --- | --- |
| `jd_cms_philips_intellivue` | `MllpHl7` | `INBOUND_SERVER` | `CENTRAL_STATION_FANOUT` | HL7/MLLP |
| `jd_cms_mindray_egateway` | `MindrayEgateway` | `INBOUND_SERVER` | `CENTRAL_STATION_FANOUT` | HL7/MLLP |
| `jd_cms_mindray_pds_realtime` | `MindrayPdsRealtime` | `OUTBOUND_CLIENT` | `CENTRAL_STATION_FANOUT` | HL7/MLLP over PDS |
| `jd_vent_mindray_sv300` | `VentMindraySv300` | `INBOUND_SERVER` | `SINGLE_DEVICE` | 私有二进制 |
| `jd_vent_drager_evita` | `VentDragerEvita` | `INBOUND_SERVER` | `SINGLE_DEVICE` | 私有二进制 |
| `jd_bga_radiometer_abl90` | `BgaRadiometerAbl90` | `INBOUND_SERVER` | `SINGLE_DEVICE` | ASTM 风格文本块传输 |

扩展时优先参考：

- 新 HL7/MLLP 中央站：`MllpHl7`、`MindrayEgateway`、`MindrayPdsRealtime`
- 新单设备二进制协议：`VentMindraySv300`、`VentDragerEvita`
- 新 BGA / 批量结果协议：`BgaRadiometerAbl90`

## 4. source / target 设备模型

### 4.1 `DeviceInfoPB` 的关键字段

当前 `src/main/proto/icis_device.proto` 里和运行时强相关的字段有：

- `id`
- `department_id`
- `device_type`
- `device_ip`
- `device_driver_code`
- `source_mode`
- `source_topology`
- `upstream_device_id`
- `pds_ip_seq`

### 4.2 语义约定

#### 直连接入 source 设备

- `source_mode = 1` 即 `INBOUND_SERVER`
- `upstream_device_id = 0`

这种设备会：

- 被 `DeviceConnManager.syncBridgeDevices()` 放进 `bridgeDevices`
- 允许按 `device_ip` 接收入站连接
- 进入 `DeviceConnFactory -> PluginBackedDeviceConnHandler`

#### PDS source 设备

- `source_mode = 1` 或 `source_mode = 2`
- `device_driver_code = "jd_cms_mindray_pds_realtime"`
- `upstream_device_id = 0`

这种设备会由 `MindrayPdsRealtimeSupervisor` 主动连接；当 `source_mode = 2` 即 `OUTBOUND_CLIENT` 时，不会走入站 TCP handler。

#### target 逻辑设备

- `source_mode = 3` 即 `NON_DIRECT_CONNECT`
- `upstream_device_id = 所属 source 的 device id`

这种设备：

- 不接受入站连接
- 也不会被 `DeviceConnFactory.newHandler(...)` 当作 source 创建设备连接
- 只作为“数据归属对象”参与联合采集

### 4.3 PDS target 匹配规则

当前 PDS 第一版按下面的 key 匹配 target：

- `device_ip`
- `pds_ip_seq`

对应模型：

- `PdsMonitorKey`
- `PdsBedEntry`

也就是说：

- PDS bed list 解析出来的是在线监护仪列表
- DataBridge 下发的是合法 target 设备列表
- 两边按 `device_ip + pds_ip_seq` 取交集后，得到真正需要开启 realtime session 的目标

## 5. 配置入口

### 5.1 `application.properties`

主配置文件：

- `src/main/resources/application.properties`

最重要的配置项：

- DataBridge：
  - `jingyi_bridge_url`
  - `jingyi_bridge_port`
- 设备定义同步与心跳：
  - `device.sync.interval_mins`
  - `device.heartbeat.interval_ms`
  - `device.driver.txt`
- TCP server：
  - `tcp.server.port`
  - `tcp.server.select_timeout_ms`
- PDS：
  - `mindray.pds.enabled`
  - `mindray.pds.sync.interval_ms`
  - `mindray.pds.bedlist.*`
  - `mindray.pds.realtime.*`
- 调试：
  - `txtdumper.path`

### 5.2 `device_driver.txt`

文件：

- `src/main/resources/config/device_driver.txt`

它是整个仓库最常改的配置文件，承担两类职责：

1. 驱动的协议提取配置
2. `ob_code -> monitoring_param_code` 映射

当前内置配置包括：

- Philips Intellivue
- Mindray eGateway
- Mindray PDS realtime
- SV300
- Drager Evita
- ABL90

### 5.3 `DriverContext`

`DeviceConnFactory` 和 PDS supervisor 都会构造 `DriverContext`，驱动运行时读取的基本信息都在这里：

- `remoteIp`
- `deviceInfo`
- `observationConfig`
- `paramMap`
- `observationPages`
- `charset`
- `zoneId`

驱动里不要自己再去拉 DataBridge 设备定义；优先从 `DriverContext` 读。

## 6. 如何调整参数映射

先判断你改的是哪一层。

### 6.1 只改 `ob_code -> monitoring_param_code`

这是最常见场景，通常只改：

- `src/main/resources/config/device_driver.txt`

例如 HL7 驱动：

```text
config {
    driver_code: "jd_cms_mindray_egateway"
    item {
        ob_code: "MDC_PULS_OXIM_PULS_RATE"
        monitoring_param_code: "pr"
    }
}
```

例如 Drager 的分页配置：

```text
obs_page {
    page_name: "page1"
    item {
        ob_code: "76"
        monitoring_param_code: "vent_peak_flow_rate"
    }
}
```

适用场景：

- 统一参数编码调整
- 新增一个已能解析到的设备参数
- 屏蔽某个不想再发布的参数

### 6.2 改 HL7 床号 / OBX 路径

同样只改 `device_driver.txt`：

- `hl7_bed_number_path`
- `hl7_ob_path_prefix`
- `hl7_ob_code_path`
- `hl7_ob_value_path`
- `hl7_ob_value_at_path`

这类改动会影响：

- `AbstractHl7MllpCentralStationDriver`
- 以及继承它的 `MllpHl7` / `MindrayEgateway` / `MindrayPdsRealtime`

### 6.3 配置不够时，再改驱动代码

如果协议原始码不能直接作为 `ob_code`，就必须改 driver 代码。

典型入口：

- `VentMindraySv300.getObCode(...)`
  - 设备给的是 `cmd + 单字节参数码`
- `VentDragerEvita`
  - 设备给的是两字节 ASCII HEX，且带单位换算
- `BgaRadiometerAbl90.resolveObCode(...)`
  - 设备给的是 `^^^pH^M` 这种复合字段

判断原则：

- 已经能得到语义化 `ob_code`：优先只改 `device_driver.txt`
- 还需要翻译、拆字段、单位换算：必须改 driver 代码

### 6.4 PDS 的映射规则

PDS realtime 使用 Mindray PDS 的 Parameter ID 做 `ob_code`。

也就是：

- `hl7_ob_code_path = "3-1"`
- `jd_cms_mindray_pds_realtime` 在 `device_driver.txt` 的 `item.ob_code` 配置为 PDS Parameter ID
- 例如 NIBP-S/D/M 对应 `170/171/172`

如果你要加一个 PDS 参数，通常先同步改：

- `jd_cms_mindray_pds_realtime`
- 以及是否需要给日志或测试补对应样例

## 7. 如何新增一个驱动

当前推荐按 plugin 路线新增，不要再基于 `LegacyDeviceConnHandler` 开新实现。

### 7.1 先决定驱动类型

#### A. 单设备入站协议

优先继承：

- `AbstractSingleDeviceProtocolDriver`

典型：

- `VentMindraySv300`
- `VentDragerEvita`
- `BgaRadiometerAbl90`

#### B. HL7/MLLP 中央站协议

优先继承：

- `AbstractHl7MllpCentralStationDriver`

典型：

- `MllpHl7`
- `MindrayEgateway`
- `MindrayPdsRealtime`

#### C. 很特殊的协议

如果现有两个 base class 都不适合，再直接实现：

- `DriverPlugin`

但先确认是不是只需要补一个 `FrameDecoder` 或在 base class 上覆写少量钩子，不要一开始就自己重做整套 runtime。

### 7.2 补 driver 配置

在：

- `src/main/resources/config/device_driver.txt`

新增一个 `config` 块。

至少补：

- `driver_code`
- `item`

按协议类型补：

- HL7 驱动再补 `hl7_*` 路径
- 多页面协议再补 `obs_page`

### 7.3 实现 plugin 类

通常要补这几样：

1. `driverCode()`
2. `frameDecoder()`
3. `protocolSession()`
4. 一个 `Factory implements DriverPluginFactory`

单设备协议一般长这样：

- `frameDecoder()` 返回具体 decoder
- `protocolSession().onFrame(...)` 解析一帧
- `protocolSession().onTick(...)` 发周期命令
- 通过 `publishObservationBatch(...)` 或 `publishBgaBatch(...)` 发布

HL7 中央站一般长这样：

- 直接复用 `AbstractHl7MllpCentralStationDriver` 的公共逻辑
- 只覆写：
  - `driverCode()`
  - `shouldProcessMessage(...)`
  - `buildAck(...)`
  - 必要时覆写 `newBatch(...)`

### 7.4 新增分帧器

如果协议不是 MLLP，就在：

- `src/main/java/com/jingyicare/icis_jd/driver/frame`

新增一个 `FrameDecoder` 实现。

当前可参考：

- `MllpFrameDecoder`
- `Sv300FrameDecoder`
- `DragerEvitaFrameDecoder`
- `Abl90FrameDecoder`

重要约定：

- 尽量让 payload 语义稳定
- 实现 `softTrim(...)`，这样 `FrameAccumulator` 在 buffer 过大时才能保留协议感知的尾巴，而不是直接硬截断

### 7.5 注册到 `DriverRegistry`

当前内置驱动是在：

- `DeviceConnFactory` 的 static block

里注册的。

新增驱动时至少要加：

```java
registerDriverPluginFactory(new XxxDriver.Factory());
```

如果这一步不做，设备配置和代码都对，也不会被创建出来。

### 7.6 如果是 outbound source

如果你的新驱动是“采集程序主动连接对方”，只注册 plugin 还不够。

你还需要补一个类似：

- `MindrayPdsRealtimeSupervisor`

的上层管理器，负责：

- 周期发现 source/target
- 建立 outbound socket
- 为每个目标创建 `SessionRuntime`

当前仓库还没有一个通用 outbound source 框架，所以这类驱动先按“独立 supervisor + plugin”落地最稳。

### 7.7 补测试

至少补这几类测试：

- 工厂路由：`DeviceConnFactoryTest`
- 分帧器：`FrameAccumulatorTest` 或协议专用 decoder test
- 驱动行为：`src/test/java/com/jingyicare/icis_jd/driver/impl/*`
- publisher 映射：必要时补 `GrpcObservationPublisherTest`

## 8. 如何修改连接 / source 行为

### 8.1 改“哪些设备允许入站连接”

看：

- `DeviceConnManager.syncBridgeDevices()`
- `DeviceConnManager.accept(...)`

当前规则是：

- `source_mode = 1` 即 `INBOUND_SERVER`

才允许进入入站链路。

### 8.2 改“哪些设备属于 PDS source / target”

看：

- `MindrayPdsRealtimeSupervisor.isPdsSource(...)`
- `MindrayPdsRealtimeSupervisor.isLegalPdsTarget(...)`

当前规则是：

- source：`source_mode in (1, 2)` 且 `driver_code=jd_cms_mindray_pds_realtime`
- target：`source_mode = 3` 且 `upstream_device_id > 0`

### 8.3 改 target 匹配规则

看：

- `PdsMonitorKey`
- `MindrayPdsRealtimeSupervisor.refreshAndReconcile(...)`
- `MindrayPdsProtocol.parseOnlineBedEntries(...)`

当前是 `device_ip + pds_ip_seq`。

## 9. 发布模型与 DataBridge 落地

### 9.1 `ObservationBatch`

用于监护仪、呼吸机等连续观测值。

关键字段：

- `deviceId`
- `deviceType`
- `deviceBedNumber`
- `values`
- `attributes`
- `rawMessage`

实际落地时，`GrpcObservationPublisher` 会把：

- `deviceId`
- `deviceType`
- `deviceBedNumber`
- `values`

写到 `AddDeviceDataReq`。

另外它还会读取：

- `attributes["department_id"]`

并填到 `AddDeviceDataReq.department_id`。

### 9.2 `BgaBatch`

用于血气结果。

关键字段：

- `mrnOrBednum`
- `bgaCategoryId`
- `effectiveTimeIso8601`
- `details`
- `rawMessage`

实际落地时会转成：

- `AddRawBgaRecordReq`

### 9.3 PDS 的发布身份

`MindrayPdsRealtime` 很特殊：

- 连接的是上游 PDS source
- 发布身份用的是 target 设备

也就是：

- `deviceId = target.id`
- `deviceType = target.device_type`
- `department_id = target.department_id`

如果你在查 PDS 数据归属，先看这个类。

## 10. 调试与排障

### 10.1 看原始报文

配置：

```properties
txtdumper.path=D:/tmp/icis_jd2/debug.txt
```

入口：

- `TxtDumper`

常见用途：

- 看 HL7 原文
- 看二进制帧十六进制
- 看 driver 映射后的输出

### 10.2 看工厂和 handler 路由

看：

- `DeviceConnFactory`
- `DeviceConnFactoryTest`

如果设备连上了但没有进入驱动，大概率问题在：

- `device_driver_code`
- `source_mode`
- registry 未注册

### 10.3 看 PDS realtime

看：

- `MindrayPdsRealtimeSupervisor`
- `MindrayPdsProtocol`
- `MindrayPdsRealtimeTest`
- `MindrayPdsProtocolTest`

先分三层定位：

1. `GetAllDevices` 是否拿到了 source 和 target
2. bed list 是否识别到了在线监护仪
3. realtime session 是否建立、是否持续 echo、是否收到了 HL7

### 10.4 看分帧问题

看：

- `FrameAccumulator`
- 具体 `FrameDecoder`
- `FrameAccumulatorTest`

如果现场有半包、粘包、噪声前缀或 buffer 爆长，优先改 decoder 和 `softTrim(...)`，不要先在 driver 里补各种临时字符串切割。

## 11. 构建与验证

构建：

```bash
mvn -q -DskipTests compile
```

跑测试：

```bash
mvn -q test
```

当前测试主要覆盖：

- `DeviceConnFactory`
- `FrameAccumulator`
- HL7 中央站驱动
- SV300 / Drager / ABL90
- PDS protocol / realtime
- `GrpcObservationPublisher`

## 12. 当前技术债

为了避免误判，后续开发时请记住下面几条不是 bug，而是当前阶段的边界。

- `SourceSupervisor` 已存在，但还不是统一主运行模型。
- `MindrayPdsRealtimeSupervisor` 目前是单独的 outbound 管理器，而不是通用 outbound 框架。
- `GrpcObservationPublisher` 还是同步薄适配，没有完整补回旧链路里的 RPC success/failure 统计语义。
- `LegacyDeviceConnHandler` 仍然保留，仅用于兼容外部遗留驱动。

## 13. 给后续研发和 Codex 的索引建议

按需求类型直接跳：

- 改设备接入判定：`DeviceConnManager`
- 改驱动创建：`DeviceConnFactory`、`DriverRegistry`
- 改统一 runtime：`PluginBackedDeviceConnHandler`、`SessionRuntime`
- 改分帧：`driver/frame`
- 改 HL7 中央站共性：`AbstractHl7MllpCentralStationDriver`
- 改单设备协议共性：`AbstractSingleDeviceProtocolDriver`
- 改 DataBridge 发布：`ObservationPublisher`、`GrpcObservationPublisher`
- 改 PDS source/target：`MindrayPdsRealtimeSupervisor`、`MindrayPdsProtocol`
- 改参数映射：`device_driver.txt`
- 改 proto 设备字段：`icis_device.proto`

---

根目录仍保留了 `README.txt` 作为旧部署备忘。工程说明、架构和扩展方法以这份 `README.md` 为准。
