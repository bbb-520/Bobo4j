# 服务器中间件使用说明

以下端口已于 2026-10-12 从服务器 `/opt/agentdemo/compose.json` 和运行容器核对。全部中间件只监听服务器回环地址，通过 SSH 隧道访问。

## 本机访问

在本机 PowerShell 执行并保持窗口打开：

```powershell
ssh -N -L 8081:127.0.0.1:8081 -L 8080:127.0.0.1:8080 -L 13306:127.0.0.1:13306 -L 16379:127.0.0.1:16379 -L 19090:127.0.0.1:19090 -L 9411:127.0.0.1:9411 root@47.114.108.160
```

| 组件 | 本机地址 | 用途 |
| --- | --- | --- |
| Nacos 控制台 | http://localhost:8081 | 服务注册、配置管理 |
| Sentinel Dashboard | http://localhost:8080 | 流量、限流与熔断；实际生效规则需确认服务端持久化方式 |
| MySQL | 127.0.0.1:13306 | 用户、订单、账务及文档等持久数据 |
| Redis | 127.0.0.1:16379 | 缓存、会话与运行状态 |
| Prometheus | http://localhost:19090 | 指标查询；Status → Targets 检查采集目标 |
| Zipkin | http://localhost:9411 | 查询调用链和服务耗时 |
| Milvus | 服务器 127.0.0.1:19530 | 文档向量检索；按需增加 `-L 19530:127.0.0.1:19530` |

如果本机端口被占用，可修改 `-L` 左侧端口。例如 `-L 18081:127.0.0.1:8081` 对应访问 `http://localhost:18081`。数据库工具若自己配置 SSH 隧道，就不必同时使用上面的手工隧道。

## Nacos

控制台为 8081；8848 和 9848 为应用连接 Nacos 使用的服务端口。登录使用部署时的实际账户，仓库中的默认值不是线上凭据说明。

- 服务管理：选择应用所在命名空间，查看服务、实例健康状态和注册信息。
- 配置管理：项目代码导入 `AGENT_GROUP` 的配置，Data ID 与服务文件同名，例如 `agent-auth-service.yml`、`agent-chat-service.yml`。先查看现有内容并导出备份，再编辑发布。
- 本地 `infra/nacos/import/` 提供模板；不要直接导入覆盖线上配置。容器中的数据库等地址使用 Docker 服务名，例如 `mysql:3306`、`redis:6379`，不能填写本机隧道地址。
- 应用配置导入开启 `refreshEnabled=true`，但这不保证所有连接池和客户端都能动态重建。数据库、Redis、支付客户端等配置修改后，应检查实际生效值并重新创建/重启对应服务。

## 配置来源与生效

线上部署入口是 `/opt/agentdemo/compose.json`，此外还有 Nacos 配置与应用内部 YAML。环境变量和其他属性源可能覆盖 Nacos 同名配置；先确定实际配置来源，再修改。配置文件包含密钥，不要贴到公开日志或提交 GitHub。

修改 Compose 前备份。修改容器环境变量后，需要重新创建容器，`docker restart` 不会重新读取 Compose 环境变量：

```bash
cd /opt/agentdemo
cp -a compose.json "compose.json.bak.$(date +%Y%m%d-%H%M%S)"
# 编辑并核对 compose.json 后，仅更新目标服务：
docker compose -p agentdemo -f compose.json up -d --no-deps agent-auth-service
docker logs --tail 100 agentdemo-agent-auth-service-1
```

## 数据库和 Redis

DBeaver/Navicat 连接本机 13306，RedisInsight 连接本机 16379。使用部署配置中的真实数据库名、用户名和密码。

也可以在服务器进入容器，密码由交互提示输入：

```bash
docker exec -it agentdemo-mysql-1 mysql -uroot -p
```

进入 MySQL 后：

```sql
SHOW DATABASES;
-- 选择实际业务库：USE 库名;
SHOW TABLES;
-- 查看结构：DESCRIBE 表名;
-- 查询时使用明确字段与 LIMIT，避免输出整个用户或账务表。
```

Redis 容器若保留部署环境变量 `REDIS_PASSWORD`，可以在容器内使用它，不在命令行展开密码：

```bash
docker exec -it agentdemo-redis-1 sh -lc 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli'
```

Redis 内常用 `PING`、`INFO keyspace`、`SCAN 0 COUNT 100`，根据返回游标继续扫描。避免在线上执行 `KEYS *`、`FLUSHDB` 或 `FLUSHALL`。

## 日常检查

```bash
docker ps
docker stats --no-stream
docker logs --tail 100 agentdemo-nacos-1
docker logs --tail 100 agentdemo-agent-auth-service-1
```

Prometheus 可查询 `up` 判断采集是否成功；采集正常不代表支付、邮件等业务已验证。Zipkin 是否有某次请求取决于采样与链路上报配置。Milvus 通常通过项目文档上传、索引、问答接口使用；其健康端口为服务器 9091。当前监控配置不等同已经设置邮件/短信告警。
