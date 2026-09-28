# http — 规格说明（SPEC）

> Kotlin/Native 的 HTTP 协议库：通用 HTTP 类型、HTTP/1.1、HTTP/2，以及建在 `quic` 之上的 HTTP/3。底层是 `com.netonstream:io`。
> 仓库 `http`。
> 状态：草案 v1（2026-09-27，按 GPT 评审修订：HTTP/3 头部的三种上限分开、不继承参考跳过的测试；待评审）。实施进展见 §11。

## 0. 依据与范围

- **建设方法**：neton-io SPEC §28.14。首版复刻参考实现的全部能力，并按 neton.io 与 Kotlin 协程落地。本库也是 neton-io 的第二个协议消费者（neton-io SPEC §28.7）。
- **参考实现**（只读，版本与提交号见 `~/projects/reference/rust/README.md`）：
  - `http` 1.5.0（通用类型，记作 `H/…`）。
  - `httparse` 1.10.1（HTTP/1 头部解析，记作 `P/…`）。
  - `hyper` 1.11.1（HTTP/1.1 与 HTTP/2 引擎，记作 `Y/…`）。
  - `h2` 0.4.19（HTTP/2，记作 `H2/…`）。
  - `h3` 0.0.8（HTTP/3，记作 `H3/…`）。
  - 自有性能参考：`~/projects/Neton/geario-http`。
- **能力盘点**：2026-09-27 逐项阅读源码所得。
- **标注**：
  - ✅ 对等：与参考实现一致。
  - ⚖️ 有意不同：附理由。
  - ⛔ 不适用：附理由。
- **安全基线**：neton-io SPEC §28.7 把修订 2 / 3 冻结的 HTTP/1.1 消息边界规则作为本库的安全基线。
  - hyper 的行为与基线一致或更严时，照 hyper。
  - 不一致之处在 §3.9 逐条记录；默认取更安全的一方，可配置项的默认值也取更安全的一方。
- **不在本库**：
  - TLS：由 `IoStream` 包装的独立模块提供。
  - 压缩（content-encoding）。
  - 客户端连接池与"自动识别 h1 / h2"的服务端：hyper-util 的能力，不在本轮参考范围，见 §7 待决。

## 1. 产物与分层

| 产物 | 坐标 | 包 | 依赖 |
|---|---|---|---|
| 通用类型 + HTTP/1.1 + HTTP/2 | `com.netonstream:http` | `neton.http`（通用）、`neton.http.h1`、`neton.http.h2` | `com.netonstream:io` |
| HTTP/3 | `com.netonstream:http3` | `neton.http.h3` | `com.netonstream:http`、`com.netonstream:quic` |

- **为何拆成两个产物**：只用 HTTP/1.1 或 HTTP/2 的使用者不必带上 QUIC。两个产物仍在同一个仓库、共用本 SPEC。这与 neton-io SPEC §28.13 表中"HTTP/3 在 `neton.http.h3`"一致，只是坐标单列；待评审确认。
- **分层**：

```
neton.http        通用模型：Request / Response / HeaderMap / HeaderName / HeaderValue / Method / StatusCode / Uri / Version / Extensions；
                  Body（数据帧与 trailer 帧）；HttpService；错误类型
neton.http.h1     HTTP/1.1：头部解析器（复刻 httparse）+ 连接状态机与编解码（复刻 hyper proto/h1）+ 服务端 / 客户端连接
neton.http.h2     HTTP/2：帧、HPACK、流与连接状态机、流量控制（复刻 h2）+ hyper 的 h2 接线（默认值、BDP、keep-alive ping、CONNECT）
neton.http.h3     HTTP/3：帧、QPACK、控制流、请求流（复刻 h3），运行在 neton.quic 的连接与流上
          ↓
com.netonstream:io（IoStream / Framed / Buffer / Bytes / 反应器 / 准入 / 计时）、com.netonstream:quic（仅 http3）
```

- **协议核心尽量不做 I/O**：头部解析、编解码、HPACK / QPACK、流状态机等可以用输入字节 / 事件驱动测试。连接层在 `IoStream`（h1、h2）或 QUIC 流（h3）上驱动它们。
- **运行时抽象的映射**（⛔ 不照搬）：
  - hyper 的 `rt::Read` / `Write` / `Timer` / `Sleep` / `Executor`（`Y/src/rt/`）→ neton-io 的 `IoStream`、反应器计时、协程。
  - h2 需要的执行器 → 连接作用域内 `launch`。
  - h2 的 tokio `AsyncRead` / `AsyncWrite` 与 tokio-util 编解码 → `IoStream` + `Framed`。

## 2. 通用类型（复刻 `http` 1.5.0）

| 参考 | 要点（均 ✅，除非另注） |
|---|---|
| `Request` / `Response` / `Parts` / `Builder`（`H/src/request.rs`、`response.rs`） | builder 记住第一个错误，在 `body()` 时报出；常用方法的快捷方式 |
| `HeaderMap`（`H/src/header/map.rs`） | 见下文单列 |
| `HeaderName`（`name.rs`） | 长度 ≤ 65535，非空；只允许 tchar，大写折叠为小写；`fromLowercase` / `fromStatic` 拒绝大写（这两者照参考使用 HTTP/2 的字符表，因此接受 `"`；`fromBytes` / `fromStr` 拒绝）；81 个标准头常量（v1 误写为 82，以参考 `name.rs` 为准）；按字符串查找时不分配内存（64 字节暂存） |
| `HeaderValue`（`value.rs:558-565`） | 字节规则 `b >= 32 && b != 127 \|\| b == '\t'`（允许 obs-text）；`fromStatic` 更严格（只允许 32..126 与 tab）；`toStr` 只在全为可见 ASCII 或 tab 时成功；`isSensitive` 标记（调试输出时隐藏）；可从整数构造 |
| `Method`（`method.rs`） | 标准方法 + QUERY；扩展方法为 tchar、区分大小写；`isSafe` / `isIdempotent` |
| `StatusCode`（`status.rs`） | 100..999；`fromBytes` 要求恰好 3 位数字且首位 ≥ 1；62 个标准原因短语（v1 误写为 63，以参考 `status.rs` 为准）；按类别判断 |
| `Uri`（`uri/*.rs`） | 总长 ≤ 65534，方案 ≤ 64；12 种错误；origin-form / absolute-form / authority-form / `*` 的分派；authority 的字符与冒号 / 方括号 / 端口规则；路径与查询的字节表；`#` 片段被截掉；方案与 authority 比较时不分大小写 |
| `Version` | 0.9 / 1.0 / 1.1（默认）/ 2 / 3 |
| `Extensions` | 按类型存放的表，惰性分配 |
| `Error` | 统一的错误，各具体种类 |

**HeaderMap**（✅ 语义对等，⚖️ 内部实现按 Kotlin 设计）：
- 多值语义：`insert` 替换同名的全部值；`append` 追加；`get` 取第一个值；`getAll` 按插入顺序给出同名的全部值。
- 迭代顺序：同名的值聚在一起；移除后顺序可以改变（参考为 `swap_remove`，文档称"任意但确定"）。
- 条目上限 `MAX_SIZE = 32768`：会抛异常的 API 与返回 `MaxSizeReached` 的 `try*` API 两套并存。
- `len` 计值的个数，`keysLen` 计名字的个数；`drain` 的"同名省略名字"形式；`Entry` API。
- **防哈希碰撞**：参考默认用 FNV，按位移量或探测距离检测到攻击迹象时，改用随机密钥的 SipHash 并重建（Green → Yellow → Red）。本库照此实现，随机密钥来自平台 CSPRNG。
- 布局：参考为 u16 索引的 Robin Hood 表 + 同名额外值的双向链表；本库以同等的开放寻址结构实现，热路径不装箱。

**Body**（对应 `http_body::Body` 与 `Frame`）：
- `interface Body`：
  - `suspend fun nextFrame(): Frame?`：数据帧或 trailer 帧，null 表示结束。
  - `val isEndStream`。
  - `val sizeHint`（下限 / 上限 / 精确值）。
- 连接层用 `isEndStream` 判断有无消息体，用 `sizeHint.exact` 决定定长还是 chunked；空数据帧跳过（`Y/src/proto/h1/dispatch.rs`）✅。
- `Incoming`：本库收到的消息体。长度已知时 `sizeHint` 精确；长度为 0 时即 `isEndStream` ✅。

**Service**：`fun interface HttpService { suspend fun call(request: Request<Incoming>): Response<out Body> }` ✅（对应 `service::Service`）。可与 neton-io 的 `limitInFlight` / `Admission` 组合。

## 3. HTTP/1.1（复刻 `httparse` 1.10.1 + hyper `proto/h1`）

### 3.1 头部解析器（`P/src/lib.rs`）
- **API**：
  - `Request.parse`、`Response.parse`、`parseHeaders`（用于 trailer）。
  - 结果为 `Complete(已消费字节数)` 或 `Partial`；`Partial` 或出错时恢复调用方给的头部数组。
  - 零拷贝：名字与值都是输入缓冲的切片。
- **语法** ✅：
  - 跳过开头的空行。
  - 方法为 tchar+，后跟恰好一个空格；`GET ` / `POST ` 有快速路径。
  - URI 为 0x21–0x7E 或 0x80–0xFF，非空且为合法 UTF-8。
  - 版本只接受 `HTTP/1.0` 或 `HTTP/1.1`（按 8 字节整体比较；不足 8 字节时校验前缀并返回 Partial）。
  - 状态行：3 位数字（000–999），原因短语中出现 obs-text 时返回空原因。
  - 头部名为 tchar；值允许 HTAB、0x20–0x7E、0x80–0xFF；去掉值首尾的空白。
  - 头部个数受调用方数组长度限制。
- **行结束**：httparse 在所有位置接受单独的 LF。本库默认只接受 CRLF ⚖️（安全基线），配置项 `allowBareLf` 默认 false；单独的 CR 始终报错 ✅。
- **`ParserConfig` 的七个开关**，默认全 false ✅：
  - `allowSpacesAfterHeaderNameInResponses`
  - `allowObsoleteMultilineHeadersInResponses`
  - `allowMultipleSpacesInRequestLineDelimiters`
  - `allowMultipleSpacesInResponseStatusDelimiters`
  - `allowSpaceBeforeFirstHeaderName`
  - `ignoreInvalidHeadersInResponses`
  - `ignoreInvalidHeadersInRequests`
  - 请求一侧始终不允许冒号前的空白与 obs-fold ✅。
- **错误**：HeaderName、HeaderValue、NewLine、Status、Token、TooManyHeaders、Version ✅。
- **SIMD**：参考有 SWAR / SSE4.2 / AVX2 / NEON 的扫描。本库以 8 字节字（SWAR）扫描 URI、头部名与值 ⚖️；Kotlin/Native 的向量指令支持作为后续性能项评估。结果以参考的 263 个 URI 用例与全部解析用例保证一致。
- **chunk 大小解析**：httparse 有 `parse_chunk_size`，但 hyper 不用它。本库的 chunked 解码器照 hyper（§3.4）⚖️。

### 3.2 连接状态机（`Y/src/proto/h1/conn.rs`）
- **状态**：
  - 读：`Init | Continue | Body | KeepAlive | Closed`。
  - 写：`Init | Body | KeepAlive | Closed`。
  - keep-alive：`Idle | Busy | Disabled`；对端不要求保持连接时强制为 Disabled。
  - 均 ✅。
- **`tryKeepAlive`**（1082–1101 行）✅：读写两侧都到 KeepAlive 且处于 Busy 时回到 Init（客户端置读通知；服务端配置了头部超时时也置读通知）；否则关闭。
- **读写次序**：服务端可以先读；客户端写过之后才读 ✅。
- **`Connection` 头**：按逗号拆分、去空白、不分大小写 ✅。HTTP/1.1 除非出现 `close` 都保持连接；HTTP/1.0 只有出现 `keep-alive` 才保持。
- **`enforceVersion`**（692–712 行）✅：
  - 对端是 HTTP/1.0 时，出站消息改为 1.0；需要保持连接时补 `Connection: keep-alive`，否则关闭 keep-alive。
  - 对端是 1.1 且 keep-alive 已关闭时，插入 `connection: close`。
- **EOF** ✅：
  - 空闲时 EOF：正常关闭。
  - 头部只到一半时 EOF → `IncompleteMessage`。
  - 客户端不在空闲状态时 EOF → 错误。
  - 等待响应时消息中途 EOF → `IncompleteMessage`，除非 `halfClose`。
  - 空闲客户端收到任何字节 → `UnexpectedMessage`。
- **HTTP/2 前言识别**：尚未写出任何内容时解析出错，且读缓冲以 h2 前言开头 → `VersionH2` ✅。

### 3.3 消息边界（`Y/src/proto/h1/role.rs`）
**服务端解析请求**：
- URI 长度 > 65534 → 414 ✅（安全基线默认把请求行上限设为 8 KiB，超出 → 414 ⚖️；可配置）。
- `Transfer-Encoding` ✅：
  - HTTP/1.0 上出现 → 错误。
  - `chunked` 必须是最后一个 TE 行的最后一个编码，否则 → 400。
- `Content-Length` ✅：
  - 每个值必须是严格的十进制 u64，服务端不接受逗号列表。
  - 超过 u64 上限 − 2 → 431。
  - 多个且值不同 → 400；完全相同的重复值合并。
- **TE 与 CL 同时出现**：hyper 去掉 CL、按 TE 处理并强制关闭 keep-alive。本库默认返回 400 并关闭 ⚖️（安全基线，防请求走私），配置项 `lenientTeWithCl` 默认 false；开启时行为同 hyper。
- **既无 TE 也无 CL**：请求体长度为 0；请求永远不以关闭界定 ✅。
- **`Expect: 100-continue`**（不分大小写）与 Upgrade（仅 1.1）、CONNECT（总是视为升级）✅，见 §3.5 / §3.6。
- **自动错误响应** ✅：Method / Header / Uri / Version 错误 → 400；过大（头部过多、头部超过缓冲上限、CL 过大）→ 431；URI 过长 → 414。之后关闭连接。

**客户端解析响应** ✅：
- 101 以外的 1xx：循环读取，交给 `onInformational` 回调，继续等待最终响应。
- 101：长度为 0 的消息体 + 升级。
- 204 / 304 / 对 HEAD 的响应 / 对 CONNECT 的 2xx（同时是升级）：无消息体。
- HTTP/1.0 上出现 TE → 错误；TE 以 chunked 结尾 → chunked；TE 不含 chunked → 以关闭界定。
- CL：接受逗号列表与重复值，但所有值必须相同。
- 既无 TE 也无 CL → 读到 EOF。
- HTTP/0.9：只在 `http09Responses` 开启时、且只对第一个响应接受。
- 不规范的原因短语保留在 `ReasonPhrase` 扩展中。
- 开启 obs-fold 时，折行被展开为单个空格。
- **TE 与 CL 同时出现**：视为错误并关闭连接 ⚖️（安全基线；hyper 在客户端一侧按 TE 处理）。

**服务端编码响应** ✅：
- 101 或对 CONNECT 的 2xx：`isLast`；CONNECT 2xx 不写长度头。
- 用户返回 1xx → 替换为 500 并报 `UnsupportedStatusCode`。
- 版本 2 改为 1.1。
- 长度矩阵：HEAD / CONNECT 2xx / 1xx / 204 / 304 不能用 chunked；1xx / CONNECT 2xx / 204 / 304 不能带 CL；HEAD 可以带用户给的 CL，但不隐式写 `content-length: 0`。
- 长度未知 → chunked（HTTP/1.0 或不允许 chunked 时以关闭界定）。
- 用户同时给 CL 与 TE → `UnexpectedHeader`，并回退已写出的部分；用户给的 TE 不含 chunked 时追加 `, chunked`。
- 用户头部中有 `connection: close` → `isLast`。
- `Trailer` 头决定允许发送的 trailer 字段。

**客户端编码请求** ✅：
- 尊重用户给的 CL / TE；HTTP/1.0 去掉 TE；TE 不含 chunked 时补上。
- 长度未知时：GET / HEAD / CONNECT 无消息体，其余用 chunked。
- 请求目标原样写出，不自动加 `Host`。

### 3.4 chunked 编解码（`Y/src/proto/h1/decode.rs`、`encode.rs`）
**解码** ✅：
- 状态逐一对等。
- 十六进制大小，带溢出检查。
- 扩展被忽略，但扩展中出现单独的 LF → 错误；整个消息体的扩展字节合计 ≤ 16 KiB。
- 大小行与数据之后都严格要求 CRLF。
- chunk 大小行另设上限 1 KiB（安全基线）⚖️。
- trailer：条数上限为 `maxHeaders`，字节上限 16 KiB；安全基线为 8 KiB，本库默认 8 KiB ⚖️（可配置）。trailer 以 trailer 帧交出，不合并进头部 ✅。
- 定长消息体在达到长度前 EOF → `IncompleteBody` ✅。
- 以 EOF 界定的消息体每次读 8192 字节 ✅。

**编码** ✅：
- 三种：Chunked（可带 trailer 名单）、Length、CloseDelimited（仅服务端）。
- chunk 头用栈上缓冲，不分配；最后一块与结束标记合并写出。
- 超出声明长度的字节被截断；提前结束 → `BodyWriteAborted`。
- trailer 只在 chunked 且字段列于 `Trailer` 头时发送；12 个禁止出现在 trailer 中的字段被丢弃。
- 服务端只在请求的 `TE` 含 `trailers` 时发送 trailer。

### 3.5 `Expect: 100-continue`（`conn.rs:405-416`、`body/incoming.rs:98-122`）
- **服务端** ✅：
  - HTTP ≥ 1.1、消息体非空、带 `100-continue` 时进入 Continue 读状态。
  - 服务第一次读取消息体、且尚未写出任何内容时，才写出 `HTTP/1.1 100 Continue\r\n\r\n`。
  - HTTP/1.0 与空消息体忽略该期望。
  - 不读消息体就响应时，不发送 100。
- **客户端** ✅：不等待，立即发送消息体；跳过中间的 1xx 响应。
- 修订 3 的"不支持、回 417"随首版复刻 hyper 而撤回（neton-io §28.7）。

### 3.6 升级与 CONNECT（`Y/src/upgrade.rs`）
- 需要升级的消息，在扩展中放入 `OnUpgrade` ✅。
- 调度结束时若有待完成的升级 → 用 `Upgraded(io, readBuf)` 完成 ✅：先回放读缓冲中尚未消费的字节，再接原流（对应 `Rewind`）。
  - 本库的 `Upgraded` 是一个 `IoStream`，因此可以直接交给 `websocket` 等上层（`websocket` SPEC §1）。
- 未开启升级的连接 → `ManualUpgrade` 错误；升级被丢弃 → `Canceled`；没有升级却等待 → `NoUpgrade` ✅。
- `downcast` 取回原始流与读缓冲 ✅。

### 3.7 流水线、缓冲、超时（`Y/src/proto/h1/io.rs`、`dispatch.rs`）
- **流水线** ✅：
  - 服务端一次处理一个请求，流水线中后续的字节留在读缓冲里。
  - `pipelineFlush`：读缓冲非空时不 flush，改为合并写。
  - 调度循环最多 16 轮后让出。
  - 本库另可配合 neton-io 的 `Admission`（neton-io §28.12）。
- **读缓冲** ✅：
  - 初始 8192，上限 417,792（最小 8192）。
  - 头部尚未完整而缓冲已满 → 431。
  - 自适应读取：读满则加倍；连续两次读取不足一半才减半。
  - 客户端另有 `readBufExactSize` 模式。
  - 只重新扫描新到的字节寻找头部结束。
  - **头部上限**：安全基线为头部段合计 64 KiB。本库默认 `maxBufSize` 仍为 417,792，另设 `maxHeaderSectionSize` 默认 64 KiB ⚖️（超出 → 431）。
- **头部个数** ✅：默认 100；单个头部名 ≥ 64 KiB → 431。
- **写** ✅：
  - 头部写入 8192 字节的缓冲。
  - 两种策略：队列（writev，最多 16 块、64 个 iovec；流支持 writev 时自动选用）与合并（把消息体复制到头部缓冲）。
  - 缓冲未超过 `maxBufSize` 时可继续缓冲。
  - 写出 0 字节 → `WriteZero`。
  - 本库的 writev 走 neton-io `IoStream.writev`。
- **头部读取超时**（`conn.rs:218-277`）：
  - hyper 默认 30 s，从开始读每个头部起计时，也覆盖 keep-alive 空闲期。
  - 本库 ⚖️（安全基线）：`headerReadTimeout` 默认 10 s（从首字节到头部结束），另设 `keepAliveIdleTimeout` 默认 60 s；超时 → `HeaderTimeout`。
  - 计时来自反应器，不需要单独配置计时器；参考中"配置了超时却没有计时器就 panic"的情形不存在 ⛔。
- **未读完的请求体** ✅：服务丢弃了消息体时，只丢弃已在缓冲中的字节（一次廉价的读空）；消息体仍未结束则关闭读端，响应后关闭连接。这比安全基线中"64 KiB 且 5 s 内读掉"更保守，照 hyper。
- **请求体上限**：hyper 本身不限，由上层用限长消息体实现。安全基线默认 10 MiB（超出 → 413 并关闭）⚖️，配置项 `maxRequestBodySize`。
- **Date 头** ✅：每个反应器缓存一份 29 字节的 IMF-fixdate，最多每秒更新一次、对齐到秒；用户未设置且 `autoDateHeader` 开启时写入（参考为线程局部，本库按反应器，语义相同）。
- **头部大小写**（`role.rs:1585`、`ext/mod.rs:161`）✅：`titleCaseHeaders`；`preserveHeaderCase`（解析时记录原始名字，编码时按原样写回）；客户端把空值写成 `Name:\r\n`。
  - `preserveHeaderOrder` 在参考中只用于 ffi ⛔。
- **优雅停机** ✅：服务端 `gracefulShutdown` 关闭 keep-alive；空闲或尚未读到任何内容时立即关闭，否则完成当前交换后关闭；已升级的连接忽略。与 neton-io 的组停机（neton-io §27、§28.3）配合。

### 3.8 错误（`Y/src/error.rs`）
- 种类 ✅：Parse（Method、Version、VersionH2、Uri、UriTooLong、Header{Token、ContentLengthInvalid、TransferEncodingInvalid、TransferEncodingUnexpected}、TooLarge、Status、Internal）、User（Body、BodyWriteAborted、InvalidConnectWithBody、Service、UnexpectedHeader、UnsupportedStatusCode、NoUpgrade、ManualUpgrade、DispatchGone、AbortedByCallback）、IncompleteMessage、UnexpectedMessage、Canceled、ChannelClosed、Io、HeaderTimeout、Body、BodyWrite、Shutdown、Http2。
- 判断方法与参考同名（`isParse`、`isParseTooLarge`、`isTimeout` 等）✅。
- httparse 错误的映射 ✅。

### 3.9 安全基线对照表（neton-io §28.7 要求）
| 条目 | hyper | 安全基线 | 本库默认 |
|---|---|---|---|
| TE 与 CL 同时出现（请求） | 去掉 CL，按 TE 处理，关闭 keep-alive | 400 并关闭 | **400 并关闭**；`lenientTeWithCl` 可切回 hyper 行为 |
| TE 与 CL 同时出现（响应，客户端） | 按 TE 处理 | 错误并关闭 | **错误并关闭** |
| 多个 CL 值不同 | 400 | 400 | 400 |
| 多个 CL 值相同 | 合并 | 接受 | 合并 |
| CL 逗号列表（请求） | 拒绝 | 值不同则拒绝 | 拒绝（hyper 更严） |
| CL 溢出 | 400（`checked_mul` 失败 → `ContentLengthInvalid`） | 400 / 413 | 400（hyper；原表误记为 431，端到端测试更正） |
| 单独的 LF 行结束 | 接受（httparse） | 拒绝 | **拒绝**；`allowBareLf` |
| 单独的 CR、obs-fold（请求）、冒号前空白 | 拒绝 | 拒绝 | 拒绝 |
| 请求行 | 65534 | 8 KiB | **8 KiB**（可配置） |
| 头部段 | 约 400 KB（缓冲上限） | 64 KiB | **64 KiB**（`maxHeaderSectionSize`） |
| 头部个数 | 100 | 100 | 100 |
| chunk 大小位数 | 溢出检查 | ≤ 16 位 | ≤ 16 位 + 溢出检查 |
| chunk 大小行 | 扩展合计 16 KiB | 1 KiB | 两者同时 |
| trailer | 16 KiB，以帧交出 | 8 KiB，丢弃 | **8 KiB**，以帧交出（hyper 的交付方式） |
| HEAD / 1xx / 204 / 304 无消息体 | 是 | 是 | 是 |
| 截断 | IncompleteBody | IoException | IncompleteBody |
| 流水线 | 按序、受读缓冲约束 | 至多缓存 16 个 | 按序、受读缓冲约束 + 可选 `Admission` |
| 未读完的请求体 | 丢弃已缓冲的，否则关闭 | 64 KiB 且 5 s 内读掉 | hyper（更保守） |
| `Expect: 100-continue` | 支持 | 417 并关闭（修订 3） | 支持（hyper；修订 3 的该项撤回） |
| 1xx 后等待最终响应（客户端） | 是 | 是 | 是 |
| 头部读取 / 空闲超时 | 30 s（两者合一） | 10 s / 60 s | **10 s / 60 s** |
| 请求体上限 | 无 | 10 MiB | **10 MiB**（`maxRequestBodySize`） |
| 上传时的应用缓冲 | 一个读缓冲 + 一个块 | 同 | 同；100 MB 上传测试见 §6 |

### 3.10 在 neton.io 上的落地设计（2026-09-28）
hyper 的 `proto/h1` 是基于 `poll` 的状态机（`Dispatcher` 反复 `poll_read` / `poll_write` / `poll_flush`，请求体经通道交给服务）。本库保留全部
**语义**（状态、边界规则、背压、keep-alive、流水线、错误），实现改为协程顺序代码：

| 模块（`neton.http.h1`） | 对应参考 | 职责 |
|---|---|---|
| `parse`（已由 httparse 移植提供） | httparse | 头部字节 → 槽位（偏移），零分配 |
| `Role`：`ServerRole` / `ClientRole` | `role.rs` | 解析出的槽位 → `MessageHead`（方法 / 目标 / 版本 / `HeaderMap`）+ 消息体长度判定（§3.3 全部规则）；编码出站头部与长度选择 |
| `BodyDecoder`：Length / Chunked / Eof | `decode.rs` | 从读缓冲取消息体数据与 trailer，严格 CRLF、各项上限（§3.4） |
| `BodyEncoder`：Length / Chunked / CloseDelimited | `encode.rs` | 数据帧与 trailer 的线格式，chunk 头不分配 |
| `H1Io` | `io.rs` | 读缓冲（自适应、上限、头部段上限）、写缓冲（头部缓冲 + 队列 / 合并两种策略，经 `IoStream.writev`）、`pipelineFlush` |
| `H1Conn` | `conn.rs` | 读写两侧状态、keep-alive（Idle / Busy / Disabled）、`enforceVersion`、EOF 分类、100-continue、升级、优雅停机 |
| `serveHttp1` / `Http1Connection`（服务端）、`Http1Client`（客户端） | `dispatch.rs` + `server/conn/http1.rs` + `client/conn/http1.rs` | 调度循环与对外 API |

- **一个连接一个协程**（neton-io 的连接协程，运行在所属反应器上）：循环 { 读请求头 → 构造 `Request<Incoming>` → 调用服务 → 写响应头 → 逐帧拉取并写出
  响应体 → keep-alive 判定 }。与 hyper 一样一次处理一个请求，流水线中后续请求的字节留在读缓冲里；参考"每轮至多 16 次后让出"由反应器的任务预算与
  neton-io §28.4 的轮转保证公平，不另设计数。
- **请求体 `Incoming`**：服务读取消息体时直接驱动本连接的 `BodyDecoder`（在同一协程里读 `IoStream`），不经通道、不另起协程、不复制到中间缓冲。
  背压语义与 hyper 相同：服务不读，连接就不从套接字读消息体（hyper 的请求体通道只在接收方"想要"时才读，`body/incoming.rs`）。服务返回时未读完的
  消息体按 §3.7 处理。`Expect: 100-continue` 在第一次读消息体时写出 100。
- **响应体**：连接协程循环 `body.nextFrame()`，按 `BodyEncoder` 写出；空数据帧跳过；定长 / chunked / 以关闭界定按 §3.3 的矩阵选择。
- **超时**：头部读取超时与 keep-alive 空闲超时用 `IoStream` 的读超时（`ReadTimeout` 能力；没有该能力的流拒绝配置这两项，§28.6）；消息体读取可选
  帧读取速率（neton-io `FrameReadRate` 的同等机制）。
- **准入**：可选 `Admission`（neton-io §28.12）：等待下一个请求首字节时不持有许可，读到首字节后获取、响应交给写路径后释放。
- **升级**：`OnUpgrade` 放入扩展；响应 101（或 CONNECT 2xx）写出后，连接把"读缓冲剩余 + 原 `IoStream`"交给 `Upgraded`（本身是 `IoStream`），
  连接协程结束，不关闭底层流。
- **客户端**：`Http1Client.handshake(stream)` 返回发送端与连接任务；请求头与请求体的写出和响应的读取并行（hyper 允许服务端提前响应，此时停止发送
  请求体），用连接作用域内的一个子协程写请求体。
- **分配**：热路径（解析、查表、编码、chunk 头）零分配；每请求不可避免的对象（`Request`、`HeaderMap` 条目、`HeaderValue`）以 callgrind 实测
  并记录（§8）。

## 4. HTTP/2（复刻 `h2` 0.4.19 + hyper 的 h2 接线）

### 4.1 编解码与帧（`H2/src/codec`、`frame`）
- **读** ✅：
  - 按 3 字节长度切帧，上限为本地的 `SETTINGS_MAX_FRAME_SIZE`，超出 → GOAWAY FRAME_SIZE_ERROR。
  - 未知帧类型被忽略。
  - 头部块未结束时出现 CONTINUATION 以外的帧 → PROTOCOL_ERROR。
- **写** ✅：
  - 单个可复用的写缓冲（初始 16 KiB）。
  - DATA 负载达到阈值（支持 writev 时 256 字节，否则 1024）时不复制，以 writev 链接写出。
  - HEADERS / PUSH_PROMISE 超过帧大小时拆分出 CONTINUATION。
  - 写出 0 字节 → `WriteZero`。
- **帧**：DATA、HEADERS、PRIORITY、RST_STREAM、SETTINGS、PUSH_PROMISE、PING、GOAWAY、WINDOW_UPDATE、CONTINUATION 的解析规则逐条对等 ✅。
  - PRIORITY 解析后忽略；发送 PRIORITY 在参考中是 `unimplemented!` ⛔（RFC 9113 已弃用优先级）。
  - 错误码 0x0–0xd；流 ID 31 位，客户端用奇数。
- **头部块校验** ✅：
  - 伪头部出现在普通头部之后或重复 → 格式错误。
  - 连接相关的头部（connection、transfer-encoding、upgrade、keep-alive、proxy-connection、非 `trailers` 的 `te`）被拒绝。
  - HPACK 总是解完整个块，以保持动态表同步。
  - 头部大小按 32 + 名 + 值计：达到上限时服务端回 431 再 REFUSED_STREAM；超过 4 倍时 GOAWAY ENHANCE_YOUR_CALM。
- **请求校验** ✅：
  - 服务端：`:method` 必需；`:protocol` 只能与 CONNECT 一起出现；`:scheme` 与 `:path` 在普通 CONNECT 与扩展 CONNECT 中的规则分别对等。
  - 客户端：相对 URI → `MissingUriSchemeAndAuthority`。
  - 出站请求带连接相关头部 → `MalformedHeaders`。

### 4.2 HPACK（`H2/src/hpack`）
- **解码** ✅：
  - 默认表大小 4096；五种表示形式。
  - 表大小更新只能出现在块的开头，且不超过 SETTINGS 中最后一次排队的值。
  - 整数最多 5 个续字节。
  - 霍夫曼解码按字节状态表进行，并校验 EOS 填充。
- **编码** ✅：
  - 无论对端通告多大，表都不超过 4 KiB。
  - 表大小更新可合并为一次或两次。
  - 非空字符串一律霍夫曼编码。
  - 敏感头部以"永不索引"编码。
  - age、authorization、content-length、etag、if-modified-since、if-none-match、location、cookie、set-cookie、`:path` 只索引名字，不索引值。
  - 超过表大小 3/4 的条目不索引。
  - 暂存缓冲在块之间复用。
- **表**：以 Robin Hood 哈希索引，静态表 62 项 ✅。
- 参考不跟踪"永不索引"的字面量（TODO），本库相同 ✅。

### 4.3 连接、设置、流、流量控制（`H2/src/proto`）
- **连接状态**：Open / Closing / Closed ✅。每轮依次：清理已过期的本地重置流 → 处理 GOAWAY → 发送待发的 pong / ping / SETTINGS / SETTINGS ACK / REFUSED_STREAM → 读一帧并分派 ✅。
- **错误处理** ✅：流错误 → RST_STREAM；连接错误 → GOAWAY（带最后处理的流 ID）；缓冲为空时的 EOF 在服务端或收到 NO_ERROR GOAWAY 后视为正常关闭。
- **设置** ✅：
  - 本地设置状态：ToSend / WaitingAck / Synced；收到 ACK 后才生效（接收帧大小、头部列表大小、表大小）。
  - 对端的 SETTINGS 在继续读下一帧之前确认。
  - 还有未确认的设置时再发送新设置 → `SendSettingsWhilePending`。
  - SETTINGS 超时：参考没有；RFC 9113 允许以 SETTINGS_TIMEOUT 关闭连接，本库提供 `settingsAckTimeout`，默认关闭 ⚖️（与参考行为一致，另提供选项）。
- **流状态机**：Idle、ReservedLocal / Remote、Open、HalfClosedLocal / Remote、Closed（EndStream / Error / ErrorAfterEndStream / ScheduledLibraryReset）✅。被跳过的流 ID 隐式关闭；新流 ID 小于期望值 → GOAWAY PROTOCOL_ERROR ✅。
- **流量控制** ✅：
  - 每个窗口记录对端视角的大小（可以为负）与可用额度。
  - 未认领的额度达到窗口一半时发送 WINDOW_UPDATE。
  - 窗口超过 2^31 − 1 → FLOW_CONTROL_ERROR（流级 RST，连接级 GOAWAY）。
  - 填充与被忽略的 DATA 自动释放额度。
  - 运行中可修改连接接收窗口目标。
  - 对端修改 INITIAL_WINDOW_SIZE 时调整所有打开的流。
  - 应用必须调用 `releaseCapacity`，否则不会发送 WINDOW_UPDATE；释放过多 → `ReleaseCapacityTooBig`。
- **发送调度** ✅：按流 ID 顺序打开；连接额度以先进先出轮转分配（无权重）；每个流缓存的数据以 `maxSendBufferSize` 为上限；以非 NO_ERROR 重置的流丢弃已缓存的 DATA。
- **并发** ✅：超过接收流上限的流以 REFUSED_STREAM 拒绝；客户端在达到上限时可排队一个请求。
- **防洪泛**（逐项对等 ✅）：
  - 尚未被接受就被对端重置的流：以 `maxPendingAcceptResetStreams`（20）计数，超过 → GOAWAY ENHANCE_YOUR_CALM（快速重置，CVE-2023-44487）。
  - 本地重置的流：保留 `resetStreamDuration`（1 s），最多 `resetStreamMax`（50）个。
  - 连接生存期内由库发起的重置：上限 `maxLocalErrorResetStreams`（1024）。
  - CONTINUATION 洪泛：帧数上限 max(5, 头部上限 / 帧大小 × 1.25)。
  - 小 DATA 帧预算：每个小于 256 字节的帧消耗 (256 − 长度)，默认预算 max(连接窗口 / 2, 25600)。
  - 非结束的空 DATA 帧：超过 100 个 → GOAWAY。
- **GOAWAY 与优雅停机** ✅：
  - 服务端先发 GOAWAY(2^31 − 1, NO_ERROR) + PING，收到 pong 后发 GOAWAY(最后处理的流 ID)，所有流结束后关闭。
  - 另有 `abruptShutdown(reason)`。
  - 收到 GOAWAY 后不再打开新流，ID 大于 last_stream_id 的流出错，对端发起的流保留。
- **服务端推送** ✅：
  - `pushRequest`：只允许 GET / HEAD 且 content-length 为 0；对端已禁用推送 → `PeerDisabledServerPush`。
  - 客户端 `pushPromises()`。
  - 推送默认启用（不发送 ENABLE_PUSH）；禁用后仍收到推送 → PROTOCOL_ERROR。
- **扩展 CONNECT**（`SETTINGS_ENABLE_CONNECT_PROTOCOL`、`:protocol`）✅；**1xx**：服务端 `sendInformational`，客户端 `pollInformational` ✅；**content-length 与实际不符** → RST PROTOCOL_ERROR（HEAD 除外）✅。

### 4.4 API 与默认值
- **h2 层的 Builder**（客户端与服务端，全部选项）✅：
  - `initialWindowSize` 65535
  - `initialConnectionWindowSize`
  - `maxFrameSize` 16384
  - `maxHeaderListSize`（本地强制 16 MiB）
  - `maxConcurrentStreams`
  - `initialMaxSendStreams`
  - `maxConcurrentResetStreams` 50
  - `resetStreamDuration` 1 s
  - `maxLocalErrorResetStreams` 1024
  - `maxPendingAcceptResetStreams` 20
  - `maxSendBufferSize` 409,600
  - `enablePush`
  - `headerTableSize` 4096
  - `dataFrameBudget`
  - 服务端另有 `enableConnectProtocol`。
- **hyper 层的默认值**（本库的 HTTP/2 连接以它们为默认 ✅，`Y/src/proto/h2`）：
  - 服务端：连接窗口与流窗口均为 1 MiB，帧 16 KiB，发送缓冲 400 KiB，头部列表 16 KiB，`maxConcurrentStreams` 200，写 Date 头。
  - 客户端：连接窗口 5 MiB，流窗口 2 MiB，发送缓冲 1 MiB，`initialMaxSendStreams` 100。
  - BDP 自适应窗口（上限 16 MiB）。
  - keep-alive ping（间隔可配，超时 20 s，可选空闲时也发）。
  - 去掉连接相关头部。
  - 按 `reserveCapacity` / `pollCapacity` 循环写出消息体。
  - 通过 SendStream / RecvStream 实现 CONNECT 与扩展 CONNECT 隧道。
- **类型**：`SendRequest`、`ResponseFuture`（`pollInformational`、`pushPromises`）、服务端 `accept()` 得到 `(Request<RecvStream>, SendResponse)`、`SendStream`（`reserveCapacity`、`capacity`、挂起直到有额度、`sendData`、`sendTrailers`、`sendReset`）、`RecvStream`（`data`、`trailers`、`flowControl`）、`FlowControl`（`releaseCapacity`）、`PingPong`、`Reason`、`Error` 与 13 个 `UserError` ✅。
- **并发模型** ⚖️：
  - 参考：连接由单个任务驱动；流状态放在 `Arc<Mutex>` 中，由用户句柄共享。
  - 本库：连接与它的所有流都在所属反应器上，不加锁。
  - 用户句柄在其他线程上使用时，经反应器投递（neton-io §28.3）。

## 5. HTTP/3（复刻 `h3` 0.0.8，在 `neton.quic` 上）

- **QUIC 接口**：参考以 trait 抽象 QUIC（`H3/h3/src/quic.rs`：`Connection`、`OpenStreams`、`SendStream`、`RecvStream`、`BidiStream`），由 h3-quinn 适配。本库直接使用 `neton.quic` 的连接与流 ⚖️，另保留一层薄接口，以便测试时替换。
- **帧**：DATA、HEADERS、CANCEL_PUSH、SETTINGS、PUSH_PROMISE、GOAWAY、MAX_PUSH_ID、WEBTRANSPORT_BI_STREAM ✅。
  - HTTP/2 专有类型 → H3_FRAME_UNEXPECTED；未知类型与 GREASE 帧跳过。
  - DATA 流式处理，不缓存负载。
  - HEADERS：参考先把整个帧缓存进 `BufList` 再解码，且不限制帧长。本库的限制见下文"头部的三种上限" ⚖️。
- **SETTINGS** ✅：最多 8 项，重复 → 错误，HTTP/2 保留 ID → H3_SETTINGS_ERROR，未知 ID 忽略；GREASE 设置；控制流上 SETTINGS 必须是第一帧，第二个 SETTINGS 以及 DATA / HEADERS → H3_FRAME_UNEXPECTED。
- **单向流** ✅：
  - 控制、推送、QPACK 编码器、QPACK 解码器、WebTransport。
  - 连接建立时打开控制流（带 SETTINGS）与 QPACK 的两条流，并在后台发送 GREASE 流。
  - 第二条控制 / 编码器 / 解码器流 → H3_STREAM_CREATION_ERROR；未知类型 → STOP_SENDING。
  - 控制流关闭 → H3_CLOSED_CRITICAL_STREAM。
- **QPACK**：
  - 参考只接入了无状态模式：编码只用静态表；解码遇到动态引用 → QPACK_DECOMPRESSION_FAILED；全部字符串霍夫曼编码。首版对等 ✅。
  - 参考中完整的动态表代码只有单元测试、未接入连接，本库首版不接入 ✅，作为后续项。
- **请求生命周期** ✅：
  - 服务端第一帧必须是 HEADERS；头部过大时自动回 431；格式错误 → H3_MESSAGE_ERROR。
  - 消息体为 DATA，随后可有作为 trailer 的 HEADERS。
  - `finish` 前每个连接发送一个 GREASE 帧。
- **GOAWAY** ✅：
  - 服务端 `shutdown(maxRequests)`；ID 大于 GOAWAY 的请求 → H3_REQUEST_REJECTED。
  - 客户端 `shutdown`。
  - ID 比上次大 → H3_ID_ERROR。
- **错误码**：H3_DATAGRAM_ERROR、0x100–0x110、QPACK 0x200–0x202 ✅。
- **h3-datagram（RFC 9297）与 h3-webtransport（仅服务端）**：参考标为实验性，本库同样实验性 ✅。
- **配置**（`config.rs`）：
  - `sendGrease` true ✅；`enableExtendedConnect` / `enableWebtransport` / `enableDatagram` false ✅；`maxWebtransportSessions` 0 ✅。
  - **头部的三种上限**（⚖️；参考只有 `max_field_section_size`，默认不设上限）：HEADERS 帧编码后的字节数与 QPACK 解码后的字段段大小是两个不同的量，
    分别设限，并在收包与解码过程中逐步检查（超出即停止，不先整体分配再检查）：
    - `maxHeadersFrameSize`：HEADERS 帧负载（编码后）的字节上限，默认 64 KiB。帧头声明的长度超出即拒绝，不读取负载。
    - `maxFieldSectionSize`：解码后字段段的大小（按 RFC 9114 的计法：每个字段名 + 值 + 32），默认 64 KiB。也通过
      SETTINGS_MAX_FIELD_SECTION_SIZE 通告给对端；解码时逐字段累加，超出即停止解码。
    - `maxFieldCount`：字段个数上限，默认 100（与 HTTP/1.1 的头部个数一致）；解码时逐个计数。
    - 超出任一上限：服务端回 431 并按参考的方式结束该请求流；客户端 → 错误。
    - HTTP/2 的对应物：`maxHeaderListSize`（解码后）与 CONTINUATION 帧数上限（编码后）已按参考分开（§4.1），HTTP/1.1 的头部段与头部个数也已分开（§3.7）。
- **参考的缺口**（首版照参考、在此列明）：
  - 不支持服务端推送（解析存在，服务端忽略 MAX_PUSH_ID / CANCEL_PUSH）。
  - 不支持 1xx。
  - 头部校验弱：不查重复伪头部、普通头部之后的伪头部、连接相关头部、content-length。**本库 ⚖️ 补上这四项校验**（与 HTTP/2 的校验一致），理由是安全。
  - 编码前字段名小写化、cookie 拆分、0-RTT 保存的设置等 TODO：首版照参考，逐项在实施时评估。
- **并发模型**：参考没有中心驱动任务，共享状态用 `Arc` / 原子量，并用 tokio 的无界通道跟踪请求完成。本库中连接在所属反应器上，不加锁 ⚖️。

## 6. 测试（移植清单）

| 来源 | 数量 | 内容 |
|---|---|---|
| `http` 模块内测试与集成测试 | 约 100 + `test_parse!` 29 | 名字、值、方法、状态码、URI（路径 22、authority 18）、HeaderMap 35、HeaderMap 模型测试（随机插入 / 追加 / 删除序列与参照实现比较） |
| `httparse` | 53 + 44 个表驱动用例 + URI 263 个用例 | 解析；SIMD 与标量结果一致 |
| hyper `tests/server.rs` | 96 | 长度与 chunked、HEAD / 304 / 204、请求体、keep-alive、`expect_continue_*`、流水线、上限、EOF 与半关闭、`header_read_timeout_*`、升级与 CONNECT、错误、停机、trailer |
| hyper `tests/client.rs` | 39 个报文脚本 + 29 个函数 | 请求长度、TE 修正、同行多个 CL、obs-fold、HTTP/0.9、错误、100-continue、CONNECT、拒绝 h2、标题大小写、流水线、升级、连接丢失时的消息体错误 |
| hyper `tests/integration.rs` 与回归测试 | 14 + 4 | 客户端与服务端往返；flush、缓冲中停机、就绪、无缓冲流 |
| hyper 模块内测试 | 约 110 | role 27、decode 17、conn 11、encode 10、io 11 等 |
| h2 `tests/h2-tests` | 229 | client_request 45、flow_control 52、server 43、stream_states 36、codec_read 14、push_promise 10、prioritization 7、informational 7、ping_pong 5、trailers 5、codec_write 4、hammer 1 |
| h2 模块内测试与 HPACK 一致性 | 约 59 + fixtures | `fixtures/hpack/` 中各实现的 JSON 用例（go、haskell、nghttp2、node、python 等） |
| h3 | 19 + 38 + 约 113 | 连接（设置、控制流错误、GOAWAY）、请求、QPACK 与帧 |
| 模糊测试 | httparse 6、http 1、h2 3（客户端、端到端、HPACK）、h3 1（varint） | 语料按参考 |
| 外部一致性 | h2spec v2.1.1、h3spec v0.1.13 | 纳入验收；不继承参考的跳过项，见下 |

- **跳过项不继承**：参考在 CI 中跳过的外部一致性用例，不自动成为本库的验收豁免。每一项在本表中记录跳过的原因、对本库是否适用、替代验证；
  默认必须通过。h3spec 中参考跳过的五项：
  | 用例 | 参考跳过的原因 | 对本库 | 验收 |
  |---|---|---|---|
  | 请求流上的 CANCEL_PUSH | 参考不校验 | 适用：应以 H3_FRAME_UNEXPECTED 拒绝 | 必须通过 |
  | QPACK 容量上限 | 参考未接入动态表 | 适用：本库通告容量 0，对端写入超出容量的指令应以 QPACK_ENCODER_STREAM_ERROR 拒绝 | 必须通过；另加单元测试 |
  | Insert Count Increment 为 0 | 同上 | 适用：应以 QPACK_DECODER_STREAM_ERROR 拒绝 | 必须通过；另加单元测试 |
  | 重复的伪头部 | 参考不校验 | 适用：本库补上此项校验（§5） | 必须通过 |
  | missing_extension TLS 告警 | 取决于 TLS 实现 | 取决于 `quic` 的 TLS 选择（`quic` SPEC §4） | TLS 选定后必须通过；在此之前记为未验证，不记为豁免 |
  h2spec 在参考中没有跳过项，本库全部必须通过。
- **有意不同项各有测试**：§3.9 表中每一行加粗的默认值；§5 补上的四项头部校验；§5 的三种头部上限（每种在编码 / 解码的逐步检查中触发）。
- **修订 3 的请求走私向量**逐个断言。
- **每个字节边界拆分到达**的模糊测试。
- **100 MB 上传**：`maxRequestBodySize` 显式调到 200 MiB，handler 流式读完并核对长度与校验和，同时断言服务端 RSS 增长 ≤ 16 MiB；默认上限下另测 413 并关闭。
- **与 `curl` 互通**（h1、h2 明文）。
- 所有测试同时用 neton-io `memoryStreamPair` 与真实 TCP 运行。

## 7. 待决

- hyper-util 的能力（客户端连接池、h1 / h2 自动识别的服务端、`TokioExecutor` 等）不在本轮参考中。若需要，另外固定 hyper-util 的版本并加入对等清单。
- `http3` 单列坐标（§1）待评审确认。

## 8. 性能对照

- **基准**：移植参考的基准。
  - hyper：`end_to_end`（h1 顺序与 10 并行，空 / 10 B / 100 KB / 10 MB）、`pipeline`（16 个流水线请求）、`server`（定长与 chunked 吞吐，以原始 TCP 为基线）、`body`，以及模块内的解析与编码基准。
  - httparse：`parse`（req、resp、uri、header、many_requests）。
  - http：header_map、header_name、header_value、method、uri。
  - h2：请求吞吐与写争用。
- **对照对象**：hyper 1.11.1（h1 / h2）、h2、h3 + quinn，以及 geario-http。均以同等功能配置在 153 上运行，按 neton-io §28.4 的规程与验收指标（吞吐、每请求 CPU、每请求分配数（callgrind）、p99、每连接公平性）。不以简化路径对比完整实现。
- **热路径目标**：头部解析与编码、HeaderMap 查找、chunk 编解码、HPACK 零分配（callgrind 实测）。

## 9. 需要 neton-io 提供的能力（缺口清单）

| 需要 | 现状 | 处理 |
|---|---|---|
| `IoStream`、writev、半关闭、超时、取消 | 已有，契约按 neton-io §28.6 落地 | 按能力声明使用 |
| 升级后把"读缓冲剩余 + 原流"交出 | `IoStream` 可包装 | 本库实现 `Rewind` 式包装，无需 neton-io 改动 |
| 读取前准入 | neton-io §28.12 | 服务端可选接入 |
| 加密安全的随机数（HeaderMap 防碰撞的哈希密钥） | 无 | 与 `websocket`、`quic` 同一缺口，评估公共模块 |
| 按反应器的每秒计时（Date 缓存） | 反应器计时 | 已有 |

## 10. 实施顺序

1. `neton.http` 通用类型（含 HeaderMap 与模型测试）。
2. HTTP/1.1：头部解析器 → 编解码 → 连接状态机 → 服务端 / 客户端 → 升级 → hyper 测试移植 → 性能对照。首版里程碑，也是 neton-io 第二个消费者的验收。
3. `websocket` 仓库（依赖本库的通用类型与 HTTP/1.1）。
4. HTTP/2：帧 → HPACK → 流与连接 → 流量控制与防洪泛 → hyper 的 h2 接线 → h2 测试与 h2spec → 性能对照。
5. HTTP/3：在 `quic` 首版之后。

每一步单独验证、单独提交，结果记入本 SPEC。

## 11. 实施记录

**第 1 步：通用类型（2026-09-28，完成，`Request` / `Response` / `Body` 随 HTTP/1.1 连接层一起做）；第 2 步：HTTP/1.1 头部解析器完成；HTTP/2 的 HPACK 完成**
- 已完成并合入：`Method` / `StatusCode` / `Version` / `Extensions`（`neton.http`）；`HeaderName` / `HeaderValue`（`neton.http.header`）；`Uri` 及其部件
  （`neton.http.uri`）。参考中的测试（`#[test]`、`test_parse!` 用例与断言行为的文档示例）逐条移植，另加 SPEC 条目的测试；macOS 224/224，
  linuxX64 / mingwX64 编译通过。
- 与参考的差异均为 Kotlin 形态所迫并在 KDoc 中说明：与字符串的比较用 `eq` / `equalsIgnoreCase` / `contentEquals`（Kotlin 的 `==` 不能跨类型重载）；
  Rust 的 `Builder` / `Parts` 在 uri 包中名为 `UriBuilder` / `UriParts`；Rust panic 之处抛异常。有意保留的参考行为：`Scheme` 大写与常量不等、端口可带
  前导 `+`、`fromStatic` 的宽松检查等。
- `HeaderMap`（`neton.http.header`）：Robin Hood 开放寻址（索引表为一个 `IntArray`，名字 / 值 / 额外值链表为并行数组），查找与容量内插入不分配；
  Green / Yellow / Red 防碰撞状态机与参考逐条一致，Red 用 SipHash-1-3（密钥取自 neton-io `secureRandom`；以 Rust 的 64 个 SipHash-1-3 向量验证），以
  真实碰撞驱动 Green → Yellow → Red 与 Yellow → Green 两条路径的测试；`tests/header_map.rs` 35 个、文档示例 56 个、模型模糊测试（含 Red 状态下）。
  **发现参考的缺陷**：`insert_mult` 在同名值 ≥ 3 个时崩溃（先清空链表再解链，已用 Rust 实际运行确认）；本库先解链，返回全部值。
  与参考的差异：`try*` 返回 `kotlin.Result`（null 已表示"无旧值"）；`&mut T` 改为 setter；过期的迭代器 / 条目在运行时报错（Rust 由借用检查保证）。
- HTTP/1 头部解析器（`neton.http.h1.parse`，httparse 1.10.1）：零复制零分配（结果为调用方提供的槽位中的偏移），8 字节 SWAR 扫描；默认拒绝单独的 LF
  （`allowBareLf` 恢复参考行为，以全部 263 个 URI 用例改为 LF 验证）；参考测试全部移植：`#[test]` 51、`req!` / `res!` 42、`tests/uri.rs` 263、
  SIMD 测试改为 SWAR 与逐字节循环的等价测试；每个解析测试另在更大数组中间再跑一次，确认不越界读取。
- HPACK（`neton.http.h2.hpack`，h2 0.4.19）：解码 / 编码 / Huffman / 表；参考测试全部移植（`test_evicted_overflow` 与参考一样忽略）；hpack-test-case
  的 12 个实现目录 382 个故事、40,374 个用例（469,664 个头部）解码并重新编码往返，另加 `raw-data` 32 个故事的往返。测试资源 58 MB 在
  `http/src/nativeTest/resources/hpack-test-case/`（附 NOTICE）。
- 合计：macOS 498 个测试通过（1 个与参考一致地忽略），linuxX64 / mingwX64 编译通过。

**第 2 步（续）：HTTP/1.1 连接层（2026-09-28）**
- `Request` / `Response`（http 1.5.0，构建器首错语义）与 `Body` / `Frame` / `SizeHint`（`EmptyBody`、`FullBody`），9 个测试。消息头的 `HeaderMap` 与
  `Extensions` 在首次使用时才创建（http crate 同样不为空头部分配）。
- 消息体编解码（`BodyDecoder` / `BodyEncoder`，hyper `decode.rs` / `encode.rs`）：参考测试 17 + 10 全部移植（异步测试改为逐字节切分输入、带 / 不带
  EOF），另加 20 个安全基线与接口测试。⚖️：chunk 大小行 1 KiB（与 16 KiB 扩展合计上限并存；参考的超限测试需放宽此项）、十六进制至多 16 位、trailer
  默认 8 KiB、trailer 中单独的 LF 被拒绝、错误是粘滞的；长度参数为 `Long`（2^63 以上不可表示）。
- 角色（`Role.kt`，hyper `role.rs`）：`ServerHeadParser` / `ClientHeadParser`（每连接复用槽位；完整头部复制一次，头部值与 URI 是这份复制的切片）、
  `ServerHeadEncoder` / `ClientHeadEncoder`（长度矩阵、101 与 CONNECT 2xx、1xx → 500、HTTP/2 → 1.1、原因短语、Date、标题大小写与
  `preserveHeaderCase` 的 `HeaderCaseMap`）、`isCompleteFast`。`role.rs` 的 25 个测试全部移植（TE+CL 两组以 `lenientTeWithCl` 按参考断言，另测默认
  拒绝；`test_parse_accepts_lf_crlf_terminator` 以 `allowBareLf` 按参考断言，另测默认拒绝），另加 11 个（请求行 / 头部段上限、CL 范围、服务端标志、
  1xx 与原因短语、编码矩阵、状态行、Date 格式、客户端 `set_length`）。
  CL 超出 `Long` 但在 u64 内 → 431（参考在 u64 顶端报 431、其余接受），超出 u64 → 400。
- 连接（`H1Io` / `H1Conn` / `Server.kt` / `Client.kt` / `Incoming` / `Upgraded` / `OnUpgrade` / `HttpError`，hyper `io.rs` / `conn.rs` / `dispatch.rs` /
  `server/conn/http1.rs` / `client/conn/http1.rs`，按 §3.10 落地）：
  - 服务与响应消息体在连接协程内联调用（`InlineCall`：一次"poll"——立即完成则不另起协程）；只有当它们挂起时，才像 hyper 那样先写出已缓冲的
    字节，并在读侧等待 EOF（`mid_message_detect_eof`）：客户端关闭则取消进行中的交换，连接以 `IncompleteMessage` 结束（`halfClose` 关闭此行为）。
  - 请求体由读取者直接驱动本连接的解码器，不经通道；服务返回后未读完的请求体按 hyper 只取已缓冲的一步，未结束则关闭读端（hyper 在消息体被丢弃时
    做这件事，Kotlin 没有析构，改在交换结束时）。
  - 写：queue（默认，头部与分块帧在小缓冲中、数据按引用排队，一次 writev 至多 16 个数据块）与 flatten 两种策略，`pipelineFlush` 合并流水线响应。
  - ⚖️ 超时：`headerReadTimeoutMillis`（10 s，自首字节起）与 `keepAliveIdleTimeoutMillis`（60 s，等待下一个请求首字节，超时为安静关闭）用流的
    `ReadTimeout` 能力；流不具备时构造即拒绝（内存流须设为 0）。⚖️ `maxRequestBodySize`（10 MiB）：声明的长度超限直接回 413 并关闭，chunked 超限
    时读取者得到错误、服务失败后回 413。
  - 升级：`OnUpgrade` 只在响应确实切换协议（101 或 CONNECT 2xx）时交出 `Upgraded`（先回放读缓冲剩余字节）；未切换 → `NoUpgrade`，未开启
    `upgrades` → `ManualUpgrade`（⚖️ hyper 在连接最终结束时仍会交出流，Kotlin 没有析构，无人领取的流会泄漏）。
  - 客户端：`SendRequest`（hyper 的就绪规则：首个请求可在连接运行前发出，之后须 `ready()`）、请求体在子协程中写出、同时读取响应、空闲时读侧
    监视（`require_empty_read`）、1xx 经 `OnInformational` 报告、升级。
  - 测试：`ServerTest` 15、`ClientTest` 5、`WriteGateTest` 2；hyper `tests/server.rs` / `client.rs` / `integration.rs` 的移植另行进行（见下一条记录）。
- 性能（153，hello world，单反应器绑定一核，wrk 2 线程）：首次对照 50 连接 neton 37.6k req/s、p99 7.7 ms，hyper 1.11.1 约 120k req/s、p99 0.5 ms
  （每请求 CPU 约 3 倍）。以 cachegrind 每请求指令数（hyper 6,549 Ir）逐项定位并修正：
  | 修正 | Ir / 请求 | 分配 / 请求 |
  |---|---|---|
  | 起点 | 98,252 | — |
  | 构建器每次构建都预先创建"已使用"异常（捕获栈） | 30,718 | 42 |
  | 头部以字节常量、头部名字节、缓存的 Date 字节与十进制写出，不经 String；解析出的头部表一次定容；访问器去掉属性引用 | 22,531 | 42 |
  | 每连接复用编码器 / 解码器 / 编码计划；排队的切片复用包装缓冲（neton-io `Buffer.borrow`）；flush 路径少一层挂起；缺席头部的快速判断 | 21,463 | 35 |
  | 单线程写闸门代替 kotlinx `Mutex`；消息头部表与扩展延迟创建 | 19,879 | 32 |
  | neton-io vectored send 按 fd 缓存 pin、每线程 iovec 暂存 | 18,814 | 31 |
  | （合入 hyper 测试移植的修正后基线） | 18,751 | 31 |
  | Date 缓存每秒只读一次墙钟（`Clock.System.now` 每次分配 `Instant`）；读与 flush 内联 | 18,256 | 28 |
  | `InlineCall` 直接调用挂起函数引用（省去每次的包装与 lambda 实例） | 17,875 | 26 |
  | 响应写出与 flush 少一层挂起；基准服务按 hyper `hello.rs` 用 `Response(body)` | 17,115 | 23 |
  | 连接状态由枚举改为 Int 常量（每次读枚举项都检查类初始化） | 16,714 | 23 |
  | 状态码按数值比较；H1Io 的数组；neton-io 时钟直接读 `clock_gettime` | 15,821 | 23 |
  | neton-io：只设读超时的读仍是尾调用（每次定时读不再分配续体） | 15,434 | 23 |
  - 反例（已撤回）：把 `exchange` 与 `pumpBody` 内联进连接循环，分配 23 → 20，但指令数 15,434 → 18,994——循环的帧变大，每次恢复清零的代价
    超过省下的续体（与 neton-io 的 K/N 经验一致）。
  - 按类别（每请求）：GC 约 4.2k、HTTP 解析 4.1k、反应器 I/O 2.0k、编码 1.9k、协程机制 1.3k、连接逻辑 0.7k。剩余的主要杠杆是分配次数与解析路径。
  - 超时的代价：关闭头部 / 空闲超时为 15,197（开启时 15,821，改为尾调用后 15,434）。
- 吞吐对照（153，wrk 2 线程 50 连接，每轮交替，3 轮；该主机上 hyper 自身在各轮间波动约 ±20%）：
  - 服务端绑定 1 核：neton 76–97k req/s、p99 约 7 ms；hyper 112–158k、p99 0.4–0.5 ms。**假设（因果未确认）**：p99 来自 K/N 的 GC 线程与反应器共用这一核：GC 线程
    占用的 CPU 很少，但它一旦运行，反应器要等一个调度时间片（给进程 2 核时同样 8 s CPU 下 p99 0.72 ms、吞吐 114k）。`setMinHeap` 不改变此现象。
    线程级证据（2026-09-28，`perf sched record -C 1` 5 s，wrk 50 连接下 p50 455 µs、p90 3.39 ms、p99 7.10 ms）：
    - 该核上反应器线程运行 4,028 ms，K/N 的 "Main GC thread" 运行 972 ms（约 19%；此前"GC 线程占用很少 CPU"的说法错误——那是整个进程的
      CPU 时间，看不出线程之间的分配）。
    - 反应器 449 次唤醒的调度延迟平均 2.16 ms、最大 6.00 ms；`perf sched timehist` 中反复出现同一序列：GC 线程变为可运行后连续运行 5.996 ms
      （一个 CFS 时间片），其间反应器可运行却等待 5.996 ms，随后反应器运行 20–27 ms，周期约 30 ms。
    - 等待时长（≈6 ms 的时间片）与 p90–p99 的 3–7 ms 吻合。据此确认：单核上的尾延迟由 GC 线程与反应器在同一核上的时间片轮转造成；根源是
      GC 工作量（约 19% 的核），与每请求的分配量直接相关。可行方向：继续减少分配、给 GC 线程单独的核（2 核时 p99 0.61–0.77 ms），或在
      应用侧调整 GC 线程的调度（未实验，不作结论）。
  - 服务端绑定 2 核（hyper 仍为单线程）：neton 103–128k、p99 0.61–0.77 ms；hyper 117–157k、p99 0.38–0.55 ms。

**HTTP/2：帧与编解码器（2026-09-28）**
- `neton.http.h2.frame`（DATA、HEADERS、PRIORITY、RST_STREAM、SETTINGS、PUSH_PROMISE、PING、GOAWAY、WINDOW_UPDATE、CONTINUATION，h2 的解析
  规则与错误映射、伪头部与头部块校验、头部列表大小）与 `neton.http.h2.codec`（`FramedRead` 基于 `Buffer`、`FramedWrite` 带 DATA 链接与
  CONTINUATION 拆分、`Codec`，无 I/O），`proto/ProtoError`。
- 测试 121 个：`frame/mod.rs` 1、`data.rs` 4、`headers.rs` 6、`codec_read.rs` 14（与 h2 一样忽略 4 个空测试）、`codec_write.rs` 4，另加 §4.1 规则测试 92
  （帧 51、头部块 19、编解码 22）。合计 macOS 733 个测试（5 个与参考一致地忽略），linuxX64 / mingwX64 编译通过。
- ⚖️：拆分的头部块中出现畸形头部时，等到 END_HEADERS 再重置流并照常解完整个块（h2 在 HEADERS 帧处即重置，之后的块不再解码，HPACK 表失步，
  下一个 CONTINUATION 变成 GOAWAY）；跨片段边界的违规被带到下一片段（h2 会遗忘，使畸形头部被静默丢弃）；写出链接的判断统一用未写出部分；
  编解码器不做 I/O（`FramedRead` 用调用方的 `Buffer`、`decodeEof` 表示输入结束，`FramedWrite` 由连接经 `advance` / `unsetFrame` 驱动）。
- Kotlin 形态（行为不变）：`Head` 打包进一个 Long（解析帧头不分配）、DATA 负载为 `Bytes` 切片、PING 负载为 Long、设置值为 `Long?`、伪头部值为
  String、`Headers.encode` 不消耗帧、错误为异常（`FrameException`、`ProtoError`、`StreamIdOverflow`）。⛔ 发送 PRIORITY 抛 `NotImplementedError`
  （同 h2 的 `unimplemented!()`）。

**HTTP/1.1：hyper 集成测试移植（2026-09-28）**
- `HyperServerTest` / `HyperClientTest` / `HyperIntegrationTest` / `HyperH1StreamTest`（+ `HyperSupport`）：`server.rs` 96 → 78（18 个仅 HTTP/2）、
  `client.rs` 66 → 50（12 个 HTTP/2；`test_try_send_request` 只为 hyper-util 的旧客户端重试而存在；另 3 个的错误来自测试宏包装的旧客户端而非
  hyper）、`integration.rs` 14 → 13（每个直连与经代理各一遍；`http2_parallel_10` 与各用例的 HTTP/2 运行跳过）、`h1_flush_before_yield` /
  `h1_shutdown_while_buffered` / `unbuffered_stream` / `ready_on_poll_stream` 4 → 4。`chunked_response_trumps_length` 与参考一样忽略。合计 878 个测试
  （6 个与参考一致地忽略），连续三次全过。
- 适配：hyper 以 `without_shutdown` / `into_parts` 取回连接的测试改走 `OnUpgrade` + `downcast()`；`http1_only` 发送 HTTP/2 前言、断言 `VersionH2`；
  空闲时 `gracefulShutdown` 由本库自己关闭传输；`max_buf_size_panic_too_small` 在 `serveConnection` 时失败；超时测试时间缩小 10 倍。
- 测试发现并修正的缺陷 11 个：带体且 `Connection: close` 的请求后等待对端才关闭；用户 `transfer-encoding: chunked` 的空响应体缺结束块；
  空闲超时改为 `HeaderTimeout` 错误（同 hyper 与 §3.7）；一次读入的完整头部超过 `maxBufSize` 未报 431；快速结束判断跳过解析使未完成头部的 ⚖️
  上限（8 KiB 请求行 / 64 KiB 头部段）未检查；头部段上限先于 URI / 请求行上限检查，使结果随 TCP 切分而变；补 `, chunked` 时删除再添加改变了
  头部顺序；以关闭结束的连接读完请求体后 `nextFrame()` 报 `IncompleteMessage`；客户端连接中途结束时消息体读取报 `Io` 而非 `IncompleteMessage`；
  请求体出错使 `sendRequest` 报 `Canceled` 而非真实错误。另：读侧监视在升级交接时仍挂起一个读，会与升级后连接的读者冲突——请求要求升级时
  不再启动监视（⚖️ 这类请求的客户端关闭在写出响应时才发现），有测试（无此修正时报"并发读"）。
- ⚖️ 双跑：`http11UriTooLong`、`headerNameTooLong`、`maxBufSize`、`clientErrorParseTooLarge`、`headerReadTimeoutAsIdleTimeout` 各以放宽的选项按参考
  断言、再以默认值按安全基线断言；`postWithChunkedOverflow` 断言 16 位十六进制上限的错误（参考为溢出）。

**HTTP/1.1：Linux 验收（2026-09-28，153，Rocky 9.8）**
- http 879 个测试（6 个与参考一致地忽略）在 epoll 与 io_uring 上全过；neton-io 145 个在 epoll / io_uring / poll 上全过。
- io_uring 上发现：`maxBufSizeSplitHeaderBoundary` 偶发 `ECONNRESET`——自动错误响应后直接关闭，而客户端仍有未读的输入，内核发出的 RST 可能
  在客户端读到响应前将其丢弃。修正：先按 hyper 关闭写端，再 ⚖️ 排空客户端输入至其关闭，最多 1 s（`closeGracefully`；无半关闭或读超时能力的流
  直接关闭）。修正后该用例在 io_uring 上连续 20 次通过。


**HTTP/2：协议层与客户端 / 服务端 API（2026-09-28，h2 0.4.19）**
- 代码：`neton.http.h2.proto`（`Config`、`FlowControl`、`State`、`Stream`、`Store`、`Buffer`、`Counts`、`Prioritize`、`Send`、`Recv`、`Streams`、`Control`、
  `Peer`、`Connection`）、`Error`（`H2Error`）、`Share`（`SendStream` / `RecvStream` / `FlowControl` / `PingPong`）、`Protocol`、`client.Client`、
  `server.Server`；`FramedWrite.hasCapacity` 把空写缓冲视为有空间；`ProtoError` 增加 `BrokenPipe` / `UnexpectedEof`。
- 运行形态：每连接两个协程（驱动：h2 的 `poll` 去掉读帧，写出并决定关闭；读者：读流、解帧、推进状态机），在各自反应器上；读者在读下一帧前
  先缓冲该帧引起的控制帧（SETTINGS ACK、PONG、GOAWAY、拒绝），一批帧之后缓冲其引起的重置与 WINDOW_UPDATE，使写出顺序与 h2 的单任务一致，
  读写互不阻塞；≥ 256 字节的 DATA 负载零复制随帧头一次 writev；不按帧启动协程；流表为按原始流 ID 的开放寻址表。
- 测试：client_request 45、flow_control 52、server 43、stream_states 34、trailers 5、ping_pong 5、informational_responses 7、push_promise 10、
  prioritization 7、hammer 1（5000 条 TCP 连接）全部移植（跳过的都是 h2 自身忽略的）；proto 模块内 12 + error.rs 1；另加 TCP 端到端 5、
  SETTINGS 超时 3、错误信息 2、冒烟 3。约 18 个测试标注 ⚖️ 适配（连接自行运行而非被 poll 驱动）。合计 macOS 1114 个测试（14 个与参考一致地
  忽略），三次全过；linuxX64 / mingwX64 编译通过。
- ⚖️：句柄用 `close()` 释放（无析构；丢弃开放流的最后一个句柄会发送重置）；服务端连接由调用方启动的 `run()` 驱动，`accept()` 只取下一个请求；
  驱动 / 读者两个协程；句柄须在连接的线程上使用（§4.4 要求的跨线程投递尚未实现）；可选 `settingsAckTimeout`（§4.3，默认关闭同参考）；错误类型
  名 `H2Error`；h2 中 u32 的选项为 `Int`；`poll_*` 成为挂起函数（`awaitCapacity`、`awaitReset`、`informational`、`pushPromise`、`awaitPong`）。
- 未完成：hyper 的 h2 接线（hyper 的默认值、BDP 自适应窗口、keep-alive ping、头部剥离、CONNECT 隧道）与 h2spec、性能对照。
- 合入 HTTP/2 后的 Linux 验收（153）：1114 个测试（14 个与参考一致地忽略）在 epoll 与 io_uring 上全过。
- 请求体数据帧不超过 16 KiB 时复制出读缓冲（切片会使连接的下一次读换新数组：带体请求每个一次整缓冲分配；64 字节 POST 实测每请求分配 28.1 → 27.1，且去掉了 8 KiB 数组）。
- 单核尾延迟的机制与处置（2026-09-28，153）：
  - 分配继续减少（`HeaderMap` 每项的哈希与链接合并为一个数组；`Body.exactLength` 免去 `SizeHint`）：每请求 14,559 Ir、19 次分配。
  - GC 统计（`GCInfo`）：单核上每次回收的"请求暂停 → 暂停开始"（到达安全点）为 5,990 µs、暂停本身 12–15 µs、34 次/s；两核上到达安全点
    0–8 µs。对 GC 线程 `perf record -e cpu-clock`：约 80% 在 `sched_yield`（协调者等待安全点的自旋），标记与清扫不到 10%。即：单核上 GC 线程
    自旋占住整个 CFS 时间片（≈6 ms），它所等待的反应器却无法运行。
  - 堆下限：此前"`setMinHeap` 不改变"的结论无效——基准服务从未调用 `GcTuning.fromEnvironment()`。实际应用后，64 MiB 使回收降到 2 次/s、
    256 MiB 约 1 次/s，p90 3.65 → 0.49 ms，但 p99 仍 5.5–7.7 ms（每次回收仍要 6 ms 到达安全点）。
  - 处置：neton-io `GcTuning.lowerGcThreadPriority`（`NETON_IO_GC_THREAD_NICE`，Linux / Android，应用自行选择、库不默认设置）降低 GC 线程的
    调度优先级：到达安全点 1 µs，单核 p99 7.0 → 0.94 ms；60 s、200 连接满载单核下 RSS 稳定约 19 MB，回收 23–26 次/s，GC 跟得上。
  - 单核交替对照（该选项开启）：50 连接 neton 132–136k、p99 0.93–0.96 ms，hyper 156–157k、p99 0.39–0.42 ms（吞吐约 0.86 倍）；200 连接
    neton 108–133k、p99 2.75–3.13 ms，hyper 134–156k、p99 1.49–1.96 ms。
- 准入（§3.10 的 `Admission`，2026-09-28）：`Http1ServerConfig.admission`。等待下一个请求首字节时不持有许可；首字节到达后取得（已缓冲的
  流水线响应先写出再等待）；响应写出后释放，出错 / 关闭路径也恰好释放一次；取不到许可超时则该连接以 `Io`（原因 `AdmissionTimeoutException`）
  结束，其他连接不受影响。neton-io 的 `Admission.acquire / tryAcquire / release` 为此公开。测试 2 个（单许可跨连接串行、空闲连接不占许可、
  超时只影响等待者）。未开启时每请求成本不变（14,744 Ir、19 次分配；新增的函数层内联，避免多一个续体）。
- 流水线（hyper `pipeline` 基准的形态：每次写 16 个请求，单核，GC 线程优先级选项开启，3 轮交替）：
  - 不合并响应：neton 142–152k req/s、p99 5.9–6.6 ms；hyper 166–171k、p99 4.9–5.3 ms（约 0.88 倍）。
  - 双方都开启 `pipelineFlush`（合并流水线响应）：neton 553–573k；hyper 862k–1.06M（约 0.6 倍；此模式下 wrk 的 p99 输出异常为 0，不作延迟比较）。
    系统调用被摊薄后，差距直接反映每请求的处理成本（14.7k 对 6.5k Ir）：解析路径与分配仍是主要改进方向。
- 其后（2026-09-28）：请求行长度由解析位置计算、小写头部把 content-length 名与整行 date 各一次写出（14,152 Ir）；queue 策略下小于 1 KiB 的
  数据复制到帧缓冲后面、不再单独排队 ⚖️（hyper 全部按引用排队；此处一次普通写代替对一个小切片的向量写，线上字节相同；flatten 策略实测 13,001 Ir）：
  **13,060 Ir、18 次分配**（hyper 6,549）。
- §6 的端到端验收项（2026-09-28，153，epoll 与 io_uring，`AcceptanceTest` 21 个 + `UploadTest` 1 个，全量 1138 个测试全过）：
  - **拆分到达**：覆盖全部分帧路径的保活序列（无体 GET、定长 POST、带块扩展与 trailer 的 chunked POST、关闭的 GET）在每个字节边界拆成两次
    到达，以及逐字节到达，响应字节与服务看到的请求均与整体到达一致。
  - **§3.9 基线向量**逐个经服务端连接驱动，每个后面紧跟一个走私请求，断言它既不到达服务、也不出现在线上：TE + CL（默认 400；
    `lenientTeWithCl` 下照 hyper 读 chunked 体、关闭保活）、CL 冲突 / 逗号列表 / 溢出、相同 CL 合并、单独 LF / CR、obs-fold、冒号前空白、
    请求行 414、头部段与头部个数 431、chunk 大小位数 / 大小行 / 单独 LF / trailer 超限。
  - 由此更正 §3.9 表：CL 溢出在 hyper 中为 400（`checked_mul` 失败 → `ContentLengthInvalid`），原表误记为 431；实现本就是 400。
  - **发现并修正的缺陷**：服务把请求体的 `UserBodyTooLarge` 原样抛出（最常见的写法）时，连接不回 413 直接关闭——`HttpError` 分支先于限额检查
    原样重抛，只有被包装的错误才走到 413。现在无论服务如何传递，头部尚未写出时都回 413 并关闭。
  - **100 MB 上传**（真实 TCP，`maxRequestBodySize` 200 MiB，服务逐帧读完并核对长度与 FNV-1a 校验和）：单独运行时驻留集增长峰值
    12.1–13.1 MiB（3 次），上限 16 MiB。余量不大，且主要取决于 GC 的回收节奏：大于 16 KiB 的数据帧以切片交出，下一次读换新的整缓冲数组，
    上传期间持续产生垃圾。在全量测试中运行时堆已被先前的测试撑大，增长读数接近 0，该断言只在单独运行时有判别力。
  - 上传路径的分配（callgrind 分配调用方普查，`echoServer` release 版，curl 上传 5 × 20 MB，epoll）：每个 64 KiB 数据帧约 5 次分配——
    一个新的 64 KiB 读数组（`Buffer.allocate`：上一个数组已作为切片交出，不回池；池按 2 的幂分级，恰为 64 KiB），以及 `Bytes`、`Frame.Data`、
    `readBodyFrame` 的续体、`tryRecv` 各一个小对象。即分配量约等于上传量（每上传 1 字节约分配 1 字节），GC 按其节奏回收，驻留集增长的
    12–15 MiB 即由此而来。hyper 靠引用计数：消费方丢弃 `Bytes` 后读缓冲的内存可被 `reserve` 收回，分配接近 0。在 GC 下，把大帧复制出来
    分配量相同、还多一次复制，不是改进；唯一的杠杆是显式的"帧用完归还"接口（超出参考的 API），暂不做，记为可选设计。
  - 与 `curl` 互通（HTTP/1.x，curl 7.76.1，153，epoll 与 io_uring 各 14 项全过）：`http-bench/curl-interop.sh` 对 `echoServer` 运行，
    核对服务看到的方法 / 目标 / 版本 / 请求体长度与 FNV-1a 校验和、以及 curl 收到的内容：GET、HTTP/1.0、小 POST、5 MB POST（curl 的
    `Expect: 100-continue`，确认收到 100）、chunked 上传、`-T -` 的 PUT、chunked 响应、1 MiB 响应、HEAD（有长度无体）、保活复用、Date 头、
    未知版本 400。h2 明文部分等 hyper 的 h2 接线合入后进行（153 上的 curl 带 nghttp2）。
- 模糊测试移植（2026-09-28，macOS 与 153 Linux 全过）：httparse 的 6 个目标（parse_request / parse_response 及其 multspaces 变体、
  parse_headers、parse_chunk_size）与 http 的 `fuzz_http`（URI、头部名 / 值、状态码字节经构建器）。参考用 libFuzzer，只要求不 panic；
  本库用固定种子（可复现），每个目标 20,000 例，一半为 HTTP 词元组成的随机输入，一半为该目标合法样本的变异，并额外检查：状态码合法；
  完整解析的偏移都在已消费范围内；把输入放进更大数组的偏移处结果相同（平移）；完整头部的每个真前缀都是 PARTIAL（增量解析）。
  完整解析的例数设下限（≥ 5%），实测每个目标 1,700–1,840 例以上，防止生成器退化使前缀检查失去意义。未发现缺陷。
  h2 的 3 个目标（client、e2e、hpack）中 hpack 已有（`FuzzTest`）；client / e2e 见下；h3 的 varint 随 HTTP/3。
- 栈帧清零（2026-09-28，153，callgrind / cachegrind）：
  - `memset` 约 1,500 Ir/请求，其中 GC 清扫清零已释放单元约 975（随分配数），其余是 K/N 在进入与每次恢复时清零整个函数帧（GC 槽）：
    `pumpBody` 2.4 KB、`encodeInto` 1.45 KB、`loop` 1.35 KB、`readHead` 1.2 KB、`exchange` 1 KB，以及 neton-io 反应器的 `ensureFd`（472 B，每次读写一次）。
  - 有效的三处：`pumpBody` 的罕见路径（缓冲满、消息体未就绪、trailer）改为调用（帧 2.4 → 1.1 KB，13,045 → 12,865）；neton-io
    `ensureFd` 内联检查、增长移出（→ 12,790）；`HeaderMap.forEach` 只在一处调用 action——内联 lambda 此前在每个调用方被复制两份
    （`encodeInto` 帧 1,448 → 1,192 B，→ 12,642）。复测 12,642 / 12,667 / 12,709：**约 12,670 Ir/请求**（此前 13,050）。
  - 无效并已撤回：把 `loop` 的 watch / 升级分支与 `exchange` 的服务挂起 / 失败分支移出，帧只减 48 / 32 B，三次测得 12,716–12,766，
    不优于基线。这两个帧的大小主要不来自这些分支。
- 其后（2026-09-28，153，cachegrind；每项单独测量，无效的撤回）：
  - 新增"浏览器式请求"对照（wrk 加 8 个常见请求头，`BROWSER_HEADERS=1 cg-http.sh`）：hello 只带 Host 一个头，掩盖了按头部计的成本。起点
    neton 31,773 对 hyper 13,173 Ir/请求（2.4 倍），差距主要在头部：名字查找 5,832、httparse 移植 3,604 + 1,211、HeaderMap 插入约 3,500、
    范围检查 918。
  - 一字节请求目标（`/`、`*`）在解码前识别，共享一个不可变 `Uri`（照 http 的 `from_shared`）：hello 12,670 → 约 12,090，分配 18 → 16。
  - 范围检查内联、异常消息移出（消息模板的临时量使每次调用清零一个帧，约 50 Ir/次）。
  - 头部名：先按长度、再以 8 / 4 字节为单位与标准名的预计算块和大小写掩码比较（精确，无需先验证；hyper 的 `StandardHeader::from_bytes`
    同样先按长度）；非标准名一次遍历完成验证、小写化、哈希与复制；差分测试把新路径与原来的查表加哈希路径逐例对照。K/N 的字节循环每字节有一次
    安全点轮询和两次越界检查（反汇编确认约 18 条指令 / 字节），所以按字比较才有效。名字查找 5,832 → 2,181。
  - HeaderMap 每次插入的检查内联（非 Red 时直接用名字自带的哈希、非 Yellow 且未满时直接有空位），SipHash 与扩容移出，条目写入内联：
    浏览器式 27,125 → 25,874，hello 11,905 → 11,652。`phaseOne` 内联无可测收益，已撤回。
  - 结果：**hello 约 11,650 Ir/请求、16 次分配**（hyper 6,549）；**浏览器式约 26,070**（hyper 13,173，约 2.0 倍）。
  - 下一个结构性项：URI 仍以 String 为底，非平凡路径（如 `/index.html`）要经 UTF-8 校验、K/N 解码时再校验一次（约 28 Ir / 字符）并按字符
    扫描，约 1,400 Ir，hyper 约 330。要去掉需把 uri 包改为以字节为底、按需生成 String（hyper 以 `Bytes` 为底），牵涉 Uri / PathAndQuery /
    Authority / Scheme，单独立项。每个头部值一个 `HeaderValue` 对象同理（hyper 中为值类型）。
- hyper 的 HTTP/2 接线合入（2026-09-28）：服务端与客户端连接（hyper 的默认值、keep-alive ping 与自适应窗口、连接相关头部剥离、CONNECT
  隧道）、请求与响应消息体；`tests/integration.rs` 的 14 个用例全部移植（每个用例直连与经代理各跑一次）。移植 hyper `tests/server.rs` /
  `client.rs` 中 HTTP/2 专有用例的工作进行中。
- **h2spec v2.1.1**（h2 的 CI 所用版本）对 hyper 层 h2c 服务（`echoServer`，`NETON_HTTP_H2=1`）：153 上 epoll 与 io_uring 均
  **145 / 145 通过**，无跳过（§6 要求全部通过）。
- **curl 互通（h2c，prior knowledge）**：`H2=1 curl-interop.sh`，两种驱动各 12 项全过——GET、小 POST、5 MB POST、`-T -` 流式上传、
  未知长度响应、1 MiB 响应、HEAD、连接复用、Date 头、一个连接上 10 路并行多路复用、响应版本为 2。请求目标照 hyper 为由 `:scheme` 与
  `:authority` 构成的绝对 URI。HTTP/1.x 的 14 项同时复跑通过。

- hyper `tests/server.rs` / `client.rs` 中 HTTP/2 专有用例移植（服务端 19、客户端 12；keep-alive 用例按 1/4 时间比例，重复运行无抖动）：
  - 修正两处实现：h2 客户端连接在写出过程中释放最后一个流后不再空等下一帧（照 h2：驱动在停泊前再查一次，客户端无句柄时发 GOAWAY）；
    `SendRequest` 在 h2 连接结束时立即报告已关闭（照 hyper：分发器随连接结束）。`rst_while_closing` 的适配随之改为期望该 GOAWAY。
  - 两处断言与 hyper 测试不同：hyper 的这两个断言位于其看不到 panic 的后台任务中，且与 hyper 自身实现矛盾（已核对参考源码）——
    `H2Upgraded::poll_read` 把 NO_ERROR / CANCEL 复位视为正常结束；h2 0.4.19 `Recv::recv_data` 只丢弃不带 END_STREAM 的空 DATA 帧，
    带 END_STREAM 的空帧作为空块交付。本库按参考实现断言。
  - `http2` / `http2_only` 是测试夹具的选项而非用例；`http1_response_with_http2_version`、`http1_conn_coerces_http2_request` 属 HTTP/1，
    早已移植。
  - 153 上 epoll 与 io_uring 全量 1,195 个测试全过（14 个与参考一致地忽略）。
- h2spec 的验收对象（补记）：服务须像 h2 的 CI 示例服务那样先读完请求再响应（`echoServer`），此时 145 / 145 每轮都过。对不读请求就立即
  响应的 hello 服务，h2spec 在 4 个用例（第二个不带 END_STREAM 的 HEADERS、trailer 中的伪头部、content-length 与 DATA 不符、
  RST_STREAM 之后的 HEADERS）中会先收到响应的 DATA 帧而判失败，随时序出现（io_uring 上每轮 1–3 个）；hyper 的 hello 服务同样
  失败（每轮 1–2 个，同类用例）。这是测试方式的产物，不是协议偏差。
- HTTP/2 性能对照（153，单核，cachegrind，oha h2c 10 连接 × 每连接 10 路并发，hello；hyper 为 `http2::Builder` 默认值）：
  - 起点 neton 43,160 对 hyper 27,070 Ir/请求（1.6 倍）。
  - 头部值相等比较改为每次 8 字节：HPACK 编码器每个响应都把 date 值与动态表中同名项比较，逐字节循环每次约 645 Ir → 38,220。
  - 发送路径以名字而非字符串检查 keep-alive / proxy-connection，HeaderMap 按名字匹配时先比同一性 → **约 37,200**（1.37 倍）。
    剩余成本分散在 h2 流状态机各处，无单点。
- h2 模糊测试 `fuzz_client` / `fuzz_e2e` 移植（`H2FuzzTest`，固定种子，macOS 与 153 两种驱动全过）：
  - `fuzz_client`：任意 URI 与头部字节构成的请求在新连接上发送，只允许库自身的错误；3,000 例中 628 例发出、519 例被拒（用户错误），
    其余在构建器处失败（照参考不继续）。
  - `fuzz_e2e`：客户端对脚本化的对端保持至多 128 个 32,769 字节的 POST，直到连接结束；每个脚本有截止时间，挂起即失败。1,500 个脚本，
    411 个响应到达客户端。⚖️ 参考的 MockIo 让读写共用一条脚本取长度，libFuzzer 靠覆盖率找到可用的排布；固定种子生成做不到，所以四分之一
    沿用共享排布（原始随机字节），其余把写入配额与读取脚本分开（帧保持对齐，帧间插入"未就绪"让客户端先打开流）。
  - 两个目标都设了覆盖下限（发出与被拒各 ≥ 300，响应 ≥ 100），防止生成器退化。未发现缺陷。
- URI 以字节为底的评估（2026-09-28，暂不做）：把请求目标保留为字节、按需生成 String，只对从不读取 `uri.path` 的服务省去解码；几乎所有
  真实服务都按路径路由，而 K/N 的 `String` 必然分配并解码，成本只是推迟到首次访问。hyper 能省是因为 `path()` 返回 `&str` 切片，K/N 做不到。
  真正的节省需要按字节路由的接口，超出参考范围；保持现状。
- 双传输运行（§6 "所有测试同时用 memoryStreamPair 与真实 TCP 运行"，2026-09-28）：测试经 `testStreamPair` 取连接，
  `NETON_HTTP_TEST_TRANSPORT=tcp` 时改为回环 TCP（监听端口 0、连接、接受）。两种模式全量通过（153：内存 / TCP × epoll / io_uring 四种组合各 1,197 个，14 个忽略，0 失败；macOS 两种模式各 1,196）；TCP 下未发现库缺陷，h1 测试原样通过。
  - 修正一处测试时序：`h2_pipe_task_cancelled_on_response_future_drop` 先等客户端应用服务端 SETTINGS（初始窗口 0）再发送；
    TCP 下客户端曾在 SETTINGS 到达前按默认窗口发出消息体。断言不变。
  - **例外（§6 未完全满足）**：h2 `mock.rs` 移植的约 190 个用例保持内存传输。参考 mock 在下一次 poll 即交付字节，用例断言由此而来的
    帧顺序（如 SETTINGS ACK 先于首个 HEADERS、`accept` 返回时 DATA 已读完、带未读字节关闭为干净 EOF）；neton-io 的就绪反应器在首个
    就绪事件前不读新连接、每轮每连接至多读一次，TCP 下这些顺序（HTTP/2 允许的其他顺序）无法确定化，不削弱断言就无法迁移。
    这些库路径在 TCP 上另由 hyper 层 HTTP/2 用例、`TcpEndToEndTest`、`HammerTest` 与 h2spec 覆盖。
    另 `timeoutsNeedReadTimeoutCapability` 断言的是无 ReadTimeout 能力的流，TCP 流有此能力，保持内存传输。
- 其后（2026-09-28，153，cachegrind）：
  - 头部名扫描每次载入 8 字节、在寄存器内逐字节查 tchar 位图（展开），浏览器式约 26,070 → 25,710；差分测试 `ScanWordTest` 对照逐字节循环。
  - 二分定位一处 HTTP/1 回退（约 +100 Ir）：出现在"头部值按字比较"提交之后，但原因不在该改动——代码尺寸变化使编译器不再内联
    `Method.equals`，`method == Method.HEAD` 等比较变成虚调用。标准方法只以共享常量存在（构造器私有，解析返回常量），故 `equals`
    只对扩展方法比较名字，HTTP/1 路径与常量按同一性比较。hello 回到约 11,626。
  - 方法解析：GET / PUT / POST / HEAD 按长度后直接比较字节，范围检查内联：hello 约 11,626 → **约 11,490**（hyper 6,549）。

