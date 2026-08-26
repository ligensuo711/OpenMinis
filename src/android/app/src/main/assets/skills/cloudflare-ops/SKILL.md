---
name: cloudflare-ops
description: Cloudflare platform operations (Workers/R2/KV/DNS/Zones/Pages). Trigger when the user mentions Cloudflare/CF, Workers, R2, KV, D1, DNS, domain, Pages, or needs to query/operate Cloudflare account resources. Invoke via `curl https://api.cloudflare.com/client/v4`.
version: 1.0.0
---
# Cloudflare 平台操作

## 账号信息

- API Token：环境变量 `CF_API_TOKEN`（Cloudflare Dashboard → My Profile → API Tokens 创建）
- Account ID：环境变量 `CF_ACCOUNT_ID`（账户级操作需要；未配置时先 `GET /accounts` 拿）

## 调用方式（curl，token 从环境变量读，禁止回显）

```sh
curl -s -H "Authorization: Bearer $CF_API_TOKEN" \
  "https://api.cloudflare.com/client/v4/accounts/$CF_ACCOUNT_ID/workers/scripts"
```

## 常用操作模板（API 路径）

统一前缀 `API="https://api.cloudflare.com/client/v4"`，请求头 `-H "Authorization: Bearer $CF_API_TOKEN"`。

| 操作 | curl 路径 |
|---|---|
| 列账户 | `$API/accounts` |
| 查账户信息 | `$API/accounts/{accountId}` |
| 列 Workers | `$API/accounts/{accountId}/workers/scripts` |
| 查单个 Worker | `$API/accounts/{accountId}/workers/scripts/{name}` |
| 部署/更新 Worker | `PUT $API/accounts/{accountId}/workers/scripts/{name}`（body 为 Worker 脚本） |
| 列 Zone | `$API/zones?account.id={accountId}` |
| 查 DNS 记录 | `$API/zones/{zoneId}/dns_records` |
| 列 R2 桶 | `$API/accounts/{accountId}/r2/buckets` |
| Workers KV 命名空间 | `$API/accounts/{accountId}/storage/kv/namespaces` |
| D1 数据库 | `$API/accounts/{accountId}/d1/database` |

## 安全纪律

1. 任何命令不得回显 `CF_API_TOKEN` 值。
2. token 只从环境变量读取，禁止写进记忆 / 日志 / 脚本文件。
3. token 用**最小 scope**：只读用途只给对应资源的 Read scope；确需部署 Worker / 改 R2 / 改 DNS 才加 Edit scope，不给账户全局 Edit。
4. 实验性创建的资源（worker / zone / DNS 记录）用后即删，避免残留账单或配额。

## 参考

- API 文档：https://developers.cloudflare.com/api/（OpenAPI 规格，curl 直查）