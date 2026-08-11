# 足球预测系统 UML 图集

---

## 一、用户登录流程

### 顺序图

```mermaid
sequenceDiagram
    participant 用户
    participant 前端
    participant 网关
    participant 认证服务
    participant 数据库

    用户->>前端: 输入账号密码
    前端->>网关: POST /api/users/login
    网关->>认证服务: login(username, password)
    认证服务->>数据库: 查询用户
    数据库-->>认证服务: 返回用户记录
    认证服务->>认证服务: 验证密码
    认证服务->>认证服务: 生成JWT Token
    认证服务-->>网关: 返回Token
    网关-->>前端: 200 成功
    前端-->>用户: 登录成功
```

### 协作图

```mermaid
graph LR
    A[👤 用户] --> B[🖥️ 前端]
    B --> C[🚪 网关]
    C --> D[🔐 认证服务]
    D --> E[🗄️ 数据库]
    E --> D
    D --> C
    C --> B
    B --> A
```

---

## 二、用户提交预测流程

### 顺序图

```mermaid
sequenceDiagram
    participant 用户
    participant 前端
    participant 网关
    participant 预测控制器
    participant 预测服务
    participant Python服务
    participant 数据库

    用户->>前端: 选择比赛提交预测
    前端->>网关: POST /api/predictions
    网关->>预测控制器: predict()
    预测控制器->>预测服务: predictAndSave()
    预测服务->>预测服务: 加载球队特征
    预测服务->>数据库: 查询ELO和战绩
    数据库-->>预测服务: 返回数据

    alt Python服务可用
        预测服务->>Python服务: POST /predict
        Python服务-->>预测服务: 返回胜率
    else Python服务不可用
        预测服务->>预测服务: 使用ELO基准预测
    end

    预测服务->>数据库: 保存预测记录
    数据库-->>预测服务: 返回ID
    预测服务-->>预测控制器: 返回结果
    预测控制器-->>网关: 返回响应
    网关-->>前端: 200 成功
    前端-->>用户: 显示预测结果
```

### 协作图

```mermaid
graph TB
    A[👤 用户] --> B[🖥️ 前端]
    B --> C[🚪 网关]
    C --> D[🔮 预测控制器]
    D --> E[⚡ 预测服务]
    E --> F[🗄️ 数据库]
    F --> E
    E --> G[🐍 Python服务]
    G --> E
    E --> D
    D --> C
    C --> B
    B --> A
```

---

## 三、数据同步流程

### 顺序图

```mermaid
sequenceDiagram
    participant 调度器
    participant 同步服务
    participant API客户端
    participant 数据库

    调度器->>同步服务: syncAllLeagues()

    loop 遍历各联赛
        同步服务->>API客户端: 获取球队数据
        API客户端-->>同步服务: 返回球队列表

        loop 遍历每支球队
            同步服务->>数据库: 保存球队
        end

        同步服务->>API客户端: 获取比赛数据
        API客户端-->>同步服务: 返回比赛列表

        loop 遍历每场比赛
            同步服务->>数据库: 保存比赛
        end

        同步服务->>同步服务: 计算球队战绩
        同步服务->>同步服务: 更新ELO评分
    end

    同步服务-->>调度器: 同步完成
```

### 协作图

```mermaid
graph TB
    A[⏰ 调度器] --> B[⚙️ 同步服务]
    B --> C[🔗 API客户端]
    C --> B
    B --> D[🗄️ 数据库]
    D --> B
    B --> A
```

---

## 四、爬虫采集流程

### 顺序图

```mermaid
sequenceDiagram
    participant 管理员
    participant 爬虫控制器
    participant 爬虫服务
    participant HTTP客户端
    participant 解析器
    participant 数据库

    管理员->>爬虫控制器: 触发爬取
    爬虫控制器->>爬虫服务: 爬取比赛数据

    爬虫服务->>HTTP客户端: 请求网页
    HTTP客户端-->>爬虫服务: 返回HTML

    爬虫服务->>解析器: 解析数据
    解析器-->>爬虫服务: 返回结构化数据

    loop 遍历每场比赛
        爬虫服务->>数据库: 保存或更新比赛
    end

    爬虫控制器->>爬虫服务: 爬取积分榜
    爬虫服务->>数据库: 保存积分榜数据

    爬虫控制器-->>管理员: 返回结果统计
```

### 协作图

```mermaid
graph TB
    A[👨‍💼 管理员] --> B[🎣 爬虫控制器]
    B --> C[📅 爬虫服务]
    C --> D[🌐 HTTP客户端]
    D --> C
    C --> E[📝 解析器]
    E --> C
    C --> F[🗄️ 数据库]
    F --> C
    C --> B
    B --> A
```

---

## 五、查看比赛列表流程

### 顺序图

```mermaid
sequenceDiagram
    participant 用户
    participant 前端
    participant 网关
    participant 比赛控制器
    participant 比赛服务
    participant 缓存
    participant 数据库

    用户->>前端: 请求今日比赛
    前端->>网关: GET /api/matches/today
    网关->>比赛控制器: today()
    比赛控制器->>比赛服务: 获取今日比赛

    比赛服务->>缓存: 查询缓存

    alt 缓存命中
        缓存-->>比赛服务: 返回数据
    else 缓存未命中
        缓存-->>比赛服务: null
        比赛服务->>数据库: 查询比赛
        数据库-->>比赛服务: 返回列表
        比赛服务->>缓存: 设置缓存
    end

    比赛服务-->>比赛控制器: 返回数据
    比赛控制器-->>网关: 返回响应
    网关-->>前端: 返回比赛列表
    前端-->>用户: 显示比赛卡片
```

### 协作图

```mermaid
graph TB
    A[👤 用户] --> B[🖥️ 前端]
    B --> C[🚪 网关]
    C --> D[⚽ 比赛控制器]
    D --> E[📡 比赛服务]
    E --> F[⚡ 缓存]
    F --> E
    E --> G[🗄️ 数据库]
    G --> E
    E --> D
    D --> C
    C --> B
    B --> A
```

---

## 六、收藏球队流程

### 顺序图

```mermaid
sequenceDiagram
    participant 用户
    participant 前端
    participant 网关
    participant 用户控制器
    participant 收藏服务
    participant 数据库

    用户->>前端: 点击收藏球队
    前端->>网关: POST /api/users/favorites
    网关->>用户控制器: addFavorite()

    用户控制器->>收藏服务: 添加收藏
    收藏服务->>数据库: 检查是否已收藏

    alt 已收藏
        数据库-->>收藏服务: 已存在
        收藏服务-->>用户控制器: false
        用户控制器-->>网关: 400 已收藏
        网关-->>前端: 错误
        前端-->>用户: 提示已收藏
    else 未收藏
        数据库-->>收藏服务: 不存在
        收藏服务->>数据库: 插入收藏记录
        数据库-->>收藏服务: 成功
        收藏服务-->>用户控制器: true
        用户控制器-->>网关: 200 成功
        网关-->>前端: 成功
        前端-->>用户: 收藏成功
    end
```

### 协作图

```mermaid
graph LR
    A[👤 用户] --> B[🖥️ 前端]
    B --> C[🚪 网关]
    C --> D[👤 用户控制器]
    D --> E[⭐ 收藏服务]
    E --> F[🗄️ 数据库]
    F --> E
    E --> D
    D --> C
    C --> B
    B --> A
```

---

## 七、查看预测统计流程

### 顺序图

```mermaid
sequenceDiagram
    participant 用户
    participant 前端
    participant 网关
    participant 预测控制器
    participant 预测服务
    participant 数据库

    用户->>前端: 请求预测统计
    前端->>网关: GET /api/predictions/statistics
    网关->>预测控制器: statistics()

    预测控制器->>预测服务: 获取统计数据

    预测服务->>数据库: 查询预测记录
    数据库-->>预测服务: 返回列表

    预测服务->>预测服务: 计算统计数据
    预测服务->>预测服务: 统计准确率
    预测服务->>预测服务: 分组统计

    预测服务-->>预测控制器: 返回统计
    预测控制器-->>网关: 返回响应
    网关-->>前端: 返回统计数据
    前端-->>用户: 显示统计图表
```

### 协作图

```mermaid
graph TB
    A[👤 用户] --> B[🖥️ 前端]
    B --> C[🚪 网关]
    C --> D[🔮 预测控制器]
    D --> E[⚡ 预测服务]
    E --> F[🗄️ 数据库]
    F --> E
    E --> D
    D --> C
    C --> B
    B --> A
```

---

## 八、比赛结果验证流程

### 顺序图

```mermaid
sequenceDiagram
    participant 调度器
    participant 同步服务
    participant 预测服务
    participant 数据库

    调度器->>同步服务: verifyPredictions()

    同步服务->>数据库: 查询已结束比赛
    数据库-->>同步服务: 返回比赛列表

    loop 遍历每场比赛
        同步服务->>同步服务: 判断比赛结果

        同步服务->>数据库: 查询该比赛的所有预测
        数据库-->>同步服务: 返回预测列表

        loop 遍历每个预测
            同步服务->>同步服务: 比较预测与实际结果
            同步服务->>数据库: 更新预测正确性
        end
    end

    同步服务-->>调度器: 验证完成
```

### 协作图

```mermaid
graph TB
    A[⏰ 调度器] --> B[⚙️ 同步服务]
    B --> C[🗄️ 数据库]
    C --> B
    B --> D[🔮 预测服务]
    D --> B
    B --> A
```
