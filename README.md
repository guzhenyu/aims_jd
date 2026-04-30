# aims_jd

`aims_jd` 是 AIMS 侧设备采集服务，工程结构参考 `icis_jd2`，但运行时不依赖 `icis_data_bridge`。

## 数据流

1. 周期性读取 AIMS 引擎库 `${aims.engine.schema}.device_infos`。
2. 不复制设备定义，不写 `device_infos`，运行态直接使用内存中的查询结果。
3. 按设备 `source_mode` 采集：
   - `1`: 设备主动连入 `tcp.server.port`，按远端 IP 匹配设备。
   - `2`: `aims_jd` 主动连接 `device_ip:device_port`，当前用于宝莱特 Q5。
   - `3`: 初版忽略。
4. 将映射后的参数写入 `${aims.jd.schema}.device_data`，同设备、同参数、同 UTC 分钟仅保留首次写入。

## 支持设备

| driverCode | 设备 | source_mode |
| --- | --- | --- |
| `JY_monitor_mindray_n` | 迈瑞 N12 监护仪 | `1` |
| `JY_monitor_blt_q5` | 宝莱特 Q5 监护仪 | `2` |
| `JY_anesthesia_mindray_wato_ex55` | 迈瑞 WATO EX35/EX55 麻醉机 | `1` |

参数映射在 `src/main/resources/config/device_driver.txt` 中维护。

## 初始化

`aims_jd` 直接读写 AIMS 引擎 schema。默认：

- `aims.engine.schema=public`: 只读 `public.device_infos`
- `aims.jd.schema=public`: 写入 `public.device_data`

表结构由 `jingyi_aims_engine` 负责创建。`schema.postgresql.sql` 仅保留可选索引，确认表已存在后再执行：

```sql
\i src/main/resources/config/db/schema.postgresql.sql
```

默认配置在 `src/main/resources/application.properties`：

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/jingyi_aims
spring.datasource.username=postgres
spring.datasource.password=
aims.engine.schema=public
aims.jd.schema=public
tcp.server.port=50005
```

宝莱特 Q5 心跳、查询周期和时间戳后缀配置：

```properties
bltq5.heartbeat.enabled=true
bltq5.heartbeat.interval_ms=1000
bltq5.query.enabled=true
bltq5.query.interval_ms=1000
bltq5.query.timestamp_suffix=true
```

## 构建

```powershell
mvn -DskipTests compile
```

`README.icis_jd2.md` 仅保留为原项目参考文档，不代表本项目运行方式。
