# 数据库迁移约定

当前项目的历史版本同时包含初始化 SQL、增量 SQL 和少量运行时兼容迁移。生产环境不要把 `sql/football_forecast.sql` 当作升级脚本执行；它会重建表并覆盖数据。

后续增量迁移统一遵循：

1. 文件名使用唯一、递增的 `VYYYYMMDDNN__description.sql` 版本号；同一天也必须递增 `NN`，禁止重复版本号。当前目录已将历史重复日期文件整理为 `V2026082101` 至 `V2026082203`。
2. 迁移只允许新增列、索引、表或可回滚的数据修复；删除/改名必须先提供备份和回滚脚本。包含 `DELETE`、`DROP` 或 `TRUNCATE` 的迁移必须显式使用 `-AllowDestructive`。
3. 在应用发布前，先对生产库执行只读预检（表、列、唯一键、孤儿记录），再执行迁移；禁止依赖应用启动时静默 `CREATE/ALTER` 作为唯一迁移机制。
4. 迁移执行结果、操作者、数据库版本和备份文件名必须写入发布记录。

执行 `scripts/migration-check.ps1` 做文件名、版本顺序和破坏性 SQL 预检；执行 `scripts/apply-migrations.ps1 -DryRun` 预览，确认备份后再正式执行。生产服务设置 `APP_SCHEMA_REQUIRE_MIGRATIONS=true` 与 `APP_RUNTIME_DDL_ENABLED=false`，缺表会在启动时快速失败，不再静默修复结构。
