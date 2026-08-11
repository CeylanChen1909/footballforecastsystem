<template>
  <div class="admin-shell">
    <el-container class="admin-layout">
      <el-aside class="admin-aside" width="270px">
        <div class="brand-block"><div class="brand-mark">FF</div><div><div class="brand-name">Football Admin</div><div class="brand-sub">商业化运营控制台</div></div></div>
        <div class="side-section"><div class="side-label">工作台</div><el-menu :default-active="activeMenu" class="admin-menu" @select="activeMenu = $event"><el-menu-item index="dashboard">总览</el-menu-item><el-menu-item index="news">资讯中心</el-menu-item><el-menu-item index="videos">视频中心</el-menu-item><el-menu-item index="matches">比赛工作台</el-menu-item><el-menu-item index="users">用户运营</el-menu-item><el-menu-item index="config">系统配置</el-menu-item><el-menu-item index="logs">审计日志</el-menu-item></el-menu></div>
        <div class="side-section side-meta"><div class="side-label">今日概览</div><div class="meta-item"><span>资讯</span><strong>{{ newsTotal }}</strong></div><div class="meta-item"><span>比赛维护</span><strong>{{ crawlerMatchTotal }}</strong></div><div class="meta-item"><span>用户</span><strong>{{ users.length }}</strong></div><div class="meta-item"><span>日志</span><strong>{{ logs.length }}</strong></div></div>
      </el-aside>
      <el-container>
        <el-header class="admin-header"><div class="header-left"><div class="page-title">{{ pageTitle }}</div><div class="page-subtitle">{{ pageSubtitle }}</div></div><div class="header-actions"><el-input v-if="activeMenu === 'news'" v-model="newsKeyword" class="head-search" placeholder="搜索资讯标题/摘要" clearable @change="loadNews(1)" /><el-input v-if="activeMenu === 'users'" v-model="userKeyword" class="head-search" placeholder="搜索用户名" clearable @change="loadUsers" /><el-button @click="reloadAll">刷新</el-button><el-button v-if="activeMenu === 'matches'" type="success" plain @click="syncMatchesNow">更新比赛数据</el-button><el-button type="primary" plain @click="goFront">返回前台</el-button></div></el-header>
        <el-main class="admin-main">
          <section v-if="activeMenu === 'dashboard'" class="dashboard-hero"><div class="hero-copy"><div class="hero-kicker">Football Admin / SaaS Operations Center</div><div class="hero-main"></div><div class="hero-sub">实时掌握今日新增资讯、比赛维护量、待审核内容与活跃用户，帮助你快速做运营判断。</div></div><div class="hero-stats"><div class="hero-stat"><span>今日新增资讯</span><strong>{{ todaysNewsCount }}</strong></div><div class="hero-stat"><span>今日比赛维护</span><strong>{{ todaysMatchCount }}</strong></div><div class="hero-stat"><span>待审核内容</span><strong>{{ pendingReviewCount }}</strong></div><div class="hero-stat"><span>活跃用户</span><strong>{{ activeUserCount }}</strong></div></div></section>
          <section v-if="activeMenu === 'dashboard'" class="dashboard-top"><el-card v-for="m in metrics" :key="m.label" class="metric-card" shadow="hover"><div class="metric-label">{{ m.label }}</div><div class="metric-value">{{ m.value }}</div><div class="metric-desc">{{ m.desc }}</div></el-card></section>
          <section v-if="activeMenu === 'dashboard'" class="dashboard-grid"><el-card class="panel-card" shadow="hover"><template #header><div class="panel-header"><span>热点雷达区</span><el-tag type="danger">Hotspot</el-tag></div></template><div class="hotspot-grid"><div class="hotspot-col"><div class="hotspot-title">热门联赛</div><div class="hotspot-list"><div v-for="item in topLeagues" :key="item.name" class="hotspot-item"><span>{{ item.name }}</span><strong>{{ item.value }}</strong></div></div></div><div class="hotspot-col"><div class="hotspot-title">热门球队</div><div class="hotspot-list"><div v-for="item in topTeams" :key="item.name" class="hotspot-item"><span>{{ item.name }}</span><strong>{{ item.value }}</strong></div></div></div><div class="hotspot-col span-wide"><div class="hotspot-title">热门资讯标签</div><div class="hot-tag-wrap"><el-tag v-for="item in hotTags" :key="item.name" effect="plain" type="primary">{{ item.name }} · {{ item.value }}</el-tag></div></div></div></el-card><el-card class="panel-card" shadow="hover"><template #header><div class="panel-header"><span>内容状态分布</span></div></template><div class="status-badges"><el-tag type="success">已发布 {{ newsList.filter(n => n.status === 'PUBLISHED').length }}</el-tag><el-tag type="info">草稿 {{ newsList.filter(n => n.status === 'DRAFT').length }}</el-tag><el-tag type="warning">隐藏 {{ newsList.filter(n => n.status === 'HIDDEN').length }}</el-tag><el-tag type="danger">删除 {{ newsList.filter(n => n.status === 'DELETED').length }}</el-tag></div></el-card><el-card class="panel-card span-wide" shadow="hover"><template #header><div class="panel-header"><span>快捷操作</span><el-tag type="info">Shortcuts</el-tag></div></template><div class="shortcut-grid"><el-button type="primary" plain @click="activeMenu='news'; openNewsDialog()">新建资讯</el-button><el-button type="success" plain @click="activeMenu='matches'; openCrawlerMatchDialog()">新增比赛维护</el-button><el-button type="warning" plain @click="activeMenu='users'">运营用户</el-button><el-button type="info" plain @click="activeMenu='logs'">查看日志</el-button></div></el-card><el-card class="panel-card span-wide" shadow="hover"><template #header><div class="panel-header"><span>最近操作</span><el-tag type="warning">Audit</el-tag></div></template><el-timeline><el-timeline-item v-for="item in recentLogs" :key="item.id || item.createdAt" :timestamp="item.createdAt" :type="item.result === 'SUCCESS' ? 'success' : 'danger'">{{ item.operatorName || '系统' }} · {{ item.action || '-' }} · {{ item.content || '-' }}</el-timeline-item></el-timeline></el-card><el-card class="panel-card span-wide" shadow="hover"><template #header><div class="panel-header"><span>趋势分析</span><el-tag type="primary">Charts</el-tag></div></template><AdminOverviewCharts :x-axis="trendXAxis" :news-series="trendNewsSeries" :log-series="trendLogSeries" :status-series="statusSeries" :radar-data="radarData" /></el-card></section>
          <section v-if="activeMenu === 'news'"><el-card class="panel-card" shadow="hover"><template #header><div class="panel-header"><span>资讯中心</span><div class="panel-actions"><el-select v-model="newsStatusFilter" placeholder="状态" style="width: 140px" clearable @change="loadNews(1)"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /><el-option label="隐藏" value="HIDDEN" /><el-option label="删除" value="DELETED" /></el-select><el-button type="primary" @click="openNewsDialog()">新增资讯</el-button></div></div></template><el-table :data="newsList" stripe :row-key="row => row.id" @selection-change="selection => selectedNews = selection"><el-table-column type="selection" width="48" /><el-table-column prop="id" label="ID" width="80" /><el-table-column prop="title" label="标题" min-width="260" /><el-table-column prop="category" label="分类" width="110" /><el-table-column prop="isTop" label="置顶" width="90"><template #default="{ row }"><el-tag :type="row.isTop ? 'danger' : 'info'">{{ row.isTop ? 'YES' : 'NO' }}</el-tag></template></el-table-column><el-table-column prop="isFeatured" label="推荐" width="90"><template #default="{ row }"><el-tag :type="row.isFeatured ? 'success' : 'info'">{{ row.isFeatured ? 'YES' : 'NO' }}</el-tag></template></el-table-column><el-table-column prop="status" label="状态" width="110"><template #default="{ row }"><el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag></template></el-table-column><el-table-column prop="publishTime" label="发布时间" width="180" /><el-table-column label="操作" width="420" fixed="right"><template #default="{ row }"><el-button size="small" @click="openNewsDialog(row)">编辑</el-button><el-button size="small" @click="openPreview(row)">预览</el-button><el-button size="small" type="success" @click="setNewsStatus(row.id, 'PUBLISHED')">发布</el-button><el-button size="small" type="warning" @click="setNewsStatus(row.id, 'HIDDEN')">隐藏</el-button><el-button size="small" type="danger" @click="deleteNews(row.id)">删除</el-button></template></el-table-column></el-table><div class="bulk-bar" v-if="selectedNews.length"><span>已选 {{ selectedNews.length }} 项</span><div><el-button size="small" @click="bulkSetNewsStatus('PUBLISHED')">批量发布</el-button><el-button size="small" @click="bulkSetNewsStatus('HIDDEN')">批量隐藏</el-button><el-button size="small" type="danger" @click="bulkDeleteNews">批量删除</el-button></div></div><div class="pager-bar"><span>共 {{ newsTotal }} 条</span><el-pagination background layout="total, prev, pager, next, sizes, jumper" :total="newsTotal" :page-size="newsPageSize" :current-page="newsPage" :page-sizes="[10,20,50,100]" @current-change="loadNews" @size-change="handleNewsSizeChange" /></div></el-card></section>
          <section v-if="activeMenu === 'videos'"><el-card class="panel-card" shadow="hover"><template #header><div class="panel-header"><span>视频中心</span><div class="panel-actions"><el-input v-model="videoKeyword" placeholder="搜索标题/联赛/平台" clearable style="width: 240px" @change="loadVideos(1)" /><el-select v-model="videoStatusFilter" placeholder="状态" style="width: 140px" clearable @change="loadVideos(1)"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /><el-option label="隐藏" value="HIDDEN" /></el-select><el-button type="primary" @click="openVideoDialog()">新增视频</el-button></div></div></template><el-table :data="videoList" stripe :row-key="row => row.id"><el-table-column prop="id" label="ID" width="80" /><el-table-column prop="title" label="标题" min-width="220" /><el-table-column prop="platform" label="平台" width="120" /><el-table-column prop="leagueName" label="联赛" width="120" /><el-table-column prop="videoType" label="类型" width="110" /><el-table-column prop="status" label="状态" width="110"><template #default="{ row }"><el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag></template></el-table-column><el-table-column prop="sortOrder" label="排序" width="90" /><el-table-column label="操作" width="420" fixed="right"><template #default="{ row }"><el-button size="small" @click="openVideoDialog(row)">编辑</el-button><el-button size="small" @click="previewVideo(row)">预览</el-button><el-button size="small" type="success" @click="setVideoStatus(row.id, 'PUBLISHED')">上架</el-button><el-button size="small" type="warning" @click="setVideoStatus(row.id, 'HIDDEN')">下架</el-button><el-button size="small" type="danger" @click="deleteVideo(row.id)">删除</el-button></template></el-table-column></el-table><div class="pager-bar"><span>共 {{ videoTotal }} 条</span><el-pagination background layout="total, prev, pager, next, sizes, jumper" :total="videoTotal" :page-size="videoPageSize" :current-page="videoPage" :page-sizes="[10,20,50,100]" @current-change="loadVideos" @size-change="handleVideoSizeChange" /></div></el-card></section>
          <section v-if="activeMenu === 'matches'"><el-card class="panel-card" shadow="hover"><template #header><div class="panel-header"><span>比赛工作台</span><div class="panel-actions"><el-date-picker v-model="crawlerMatchDate" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" placeholder="筛选日期" clearable style="width: 150px" @change="loadMatches(1)" /><el-input v-model="crawlerMatchKeyword" placeholder="搜索联赛/球队" clearable style="width: 200px" @change="loadMatches(1)" /><el-select v-model="crawlerMatchStatus" placeholder="状态" clearable style="width: 140px" @change="loadMatches(1)"><el-option label="未开始" value="NS" /><el-option label="进行中" value="LIVE" /><el-option label="已完场" value="FT" /><el-option label="已取消" value="CANCEL" /></el-select><el-button type="primary" @click="openCrawlerMatchDialog()">新增维护项</el-button></div></div></template><el-table :data="crawlerMatches" stripe><el-table-column prop="fixtureId" label="Fixture ID" width="110" /><el-table-column label="日期" width="130"><template #default="{ row }">{{ row.matchDate || '-' }}</template></el-table-column><el-table-column label="主队头像" width="80"><template #default="{ row }"><el-avatar :size="32" :src="row.homeTeamLogo || ''">{{ row.homeTeamName?.slice(0,1) || 'H' }}</el-avatar></template></el-table-column><el-table-column prop="homeTeamName" label="主队" min-width="150" /><el-table-column label="客队头像" width="80"><template #default="{ row }"><el-avatar :size="32" :src="row.awayTeamLogo || ''">{{ row.awayTeamName?.slice(0,1) || 'A' }}</el-avatar></template></el-table-column><el-table-column prop="awayTeamName" label="客队" min-width="150" /><el-table-column prop="homeScore" label="主比分" width="90" /><el-table-column prop="awayScore" label="客比分" width="90" /><el-table-column prop="status" label="状态" width="100"><template #default="{ row }"><el-tag :type="matchStatusTag(row.status)">{{ row.status || 'UNKNOWN' }}</el-tag></template></el-table-column><el-table-column label="操作" width="220" fixed="right"><template #default="{ row }"><el-button size="small" @click="openCrawlerMatchDialog(row)">编辑</el-button><el-button size="small" type="danger" @click="deleteCrawlerMatch(row)">删除</el-button></template></el-table-column></el-table><div class="pager-bar"><span>共 {{ crawlerMatchTotal }} 条</span><el-pagination background layout="total, prev, pager, next, sizes, jumper" :total="crawlerMatchTotal" :page-size="crawlerMatchPageSize" :current-page="crawlerMatchPage" :page-sizes="[10,20,50,100]" @current-change="loadMatches" @size-change="handleCrawlerMatchSizeChange" /></div></el-card></section>
          <section v-if="activeMenu === 'users'"><el-card class="panel-card" shadow="hover"><template #header><div class="panel-header"><span>用户运营</span><div class="panel-actions"><el-select v-model="userRoleFilter" placeholder="角色" style="width: 130px" clearable @change="loadUsers"><el-option label="USER" value="USER" /><el-option label="ADMIN" value="ADMIN" /></el-select><el-select v-model="userStatusFilter" placeholder="状态" style="width: 130px" clearable @change="loadUsers"><el-option label="ACTIVE" value="ACTIVE" /><el-option label="DISABLED" value="DISABLED" /></el-select></div></div></template><el-table :data="users" stripe><el-table-column prop="id" label="ID" width="80" /><el-table-column prop="username" label="用户名" min-width="180" /><el-table-column prop="role" label="角色" width="120"><template #default="{ row }"><el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'">{{ row.role }}</el-tag></template></el-table-column><el-table-column prop="status" label="状态" width="120"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'warning'">{{ row.status }}</el-tag></template></el-table-column><el-table-column label="操作" width="260" fixed="right"><template #default="{ row }"><el-dropdown @command="cmd => handleUserCommand(cmd, row)"><el-button size="small">更多操作 <el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="toggleRole">切换角色</el-dropdown-item><el-dropdown-item command="toggleStatus">切换状态</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column></el-table></el-card></section>
          <section v-if="activeMenu === 'config'"><el-card class="panel-card" shadow="hover"><template #header><div class="panel-header"><span>系统配置</span><el-button type="primary" @click="saveConfig">保存配置</el-button></div></template><el-descriptions :column="1" border><el-descriptions-item label="资讯审核模式"><el-input v-model="config['news.audit.mode']" /></el-descriptions-item><el-descriptions-item label="资讯默认状态"><el-input v-model="config['news.default.status']" /></el-descriptions-item><el-descriptions-item label="比赛人工维护开关"><el-switch v-model="config['match.override.enabled']" active-value="true" inactive-value="false" /></el-descriptions-item><el-descriptions-item label="后台刷新秒数"><el-input v-model="config['admin.dashboard.refresh']" /></el-descriptions-item></el-descriptions></el-card></section>
          <section v-if="activeMenu === 'logs'"><el-card class="panel-card" shadow="hover"><template #header><div class="panel-header"><span>审计日志</span><div class="panel-actions"><el-select v-model="logModuleFilter" placeholder="模块" style="width: 130px" clearable @change="loadLogs"><el-option label="NEWS" value="NEWS" /><el-option label="CONFIG" value="CONFIG" /></el-select></div></div></template><el-table :data="logs" stripe height="520" @row-click="openLogDetail"><el-table-column prop="createdAt" label="时间" width="180" /><el-table-column prop="operatorName" label="操作人" width="120" /><el-table-column prop="module" label="模块" width="100" /><el-table-column prop="action" label="动作" width="120" /><el-table-column prop="targetType" label="对象类型" width="120" /><el-table-column prop="content" label="内容" min-width="260" /><el-table-column prop="result" label="结果" width="100" /></el-table></el-card></section>
        </el-main>
      </el-container>
    </el-container>
    <el-dialog v-model="newsDialogVisible" :title="newsForm.id ? '编辑资讯' : '新增资讯'" width="980px" class="admin-dialog"><el-form :model="newsForm" label-width="110px" class="form-grid"><el-form-item label="标题"><el-input v-model="newsForm.title" /></el-form-item><el-form-item label="副标题"><el-input v-model="newsForm.subtitle" /></el-form-item><el-form-item label="摘要" class="span-2"><el-input v-model="newsForm.summary" type="textarea" :rows="3" /></el-form-item><el-form-item label="正文" class="span-2"><el-input v-model="newsForm.content" type="textarea" :rows="7" /></el-form-item><el-form-item label="内容HTML" class="span-2"><el-input v-model="newsForm.contentHtml" type="textarea" :rows="6" /></el-form-item><el-form-item label="封面图"><el-input v-model="newsForm.coverImage" /></el-form-item><el-form-item label="来源"><el-input v-model="newsForm.sourceName" /></el-form-item><el-form-item label="来源URL" class="span-2"><el-input v-model="newsForm.sourceUrl" /></el-form-item><el-form-item label="分类"><el-input v-model="newsForm.category" /></el-form-item><el-form-item label="联赛"><el-input v-model="newsForm.leagueName" /></el-form-item><el-form-item label="联赛ID"><el-input v-model="newsForm.leagueId" /></el-form-item><el-form-item label="状态"><el-select v-model="newsForm.status"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /><el-option label="隐藏" value="HIDDEN" /></el-select></el-form-item><el-form-item label="作者"><el-input v-model="newsForm.author" /></el-form-item><el-form-item label="热门"><el-switch v-model="newsForm.isHot" :active-value="1" :inactive-value="0" /></el-form-item><el-form-item label="推荐"><el-switch v-model="newsForm.isFeatured" :active-value="1" :inactive-value="0" /></el-form-item><el-form-item label="置顶"><el-switch v-model="newsForm.isTop" :active-value="1" :inactive-value="0" /></el-form-item></el-form><template #footer><el-button @click="newsDialogVisible=false">取消</el-button><el-button type="primary" @click="saveNews">保存</el-button></template></el-dialog>
    <el-dialog v-model="matchDialogVisible" title="比赛维护" width="860px" class="admin-dialog"><el-form :model="matchForm" label-width="110px" class="form-grid"><el-form-item label="Fixture ID"><el-input v-model="matchForm.fixtureId" /></el-form-item><el-form-item label="联赛"><el-input v-model="matchForm.leagueName" /></el-form-item><el-form-item label="主队"><el-input v-model="matchForm.homeTeamName" /></el-form-item><el-form-item label="客队"><el-input v-model="matchForm.awayTeamName" /></el-form-item><el-form-item label="状态"><el-input v-model="matchForm.status" /></el-form-item><el-form-item label="比分"><div class="score-row"><el-input v-model="matchForm.homeScore" placeholder="主队" /><span>-</span><el-input v-model="matchForm.awayScore" placeholder="客队" /></div></el-form-item><el-form-item label="比赛时间"><el-input v-model="matchForm.matchTime" /></el-form-item><el-form-item label="备注" class="span-2"><el-input v-model="matchForm.note" type="textarea" :rows="4" /></el-form-item></el-form><template #footer><el-button @click="matchDialogVisible=false">取消</el-button><el-button type="primary" @click="saveMatchOverride">保存</el-button></template></el-dialog>
    <el-dialog v-model="crawlerMatchDialogVisible" :title="crawlerMatchForm.id ? '编辑爬虫比赛' : '新增爬虫比赛'" width="860px" class="admin-dialog"><el-form :model="crawlerMatchForm" label-width="110px" class="form-grid"><el-form-item label="Fixture ID"><el-input v-model="crawlerMatchForm.fixtureId" disabled /></el-form-item><el-form-item label="比赛日期"><el-date-picker v-model="crawlerMatchForm.matchDate" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" /></el-form-item><el-form-item label="比赛时间"><el-time-picker v-model="crawlerMatchForm.matchTime" value-format="HH:mm:ss" format="HH:mm" placeholder="选择时间" style="width: 100%" /></el-form-item><el-form-item label="联赛"><el-input v-model="crawlerMatchForm.leagueName" /></el-form-item><el-form-item label="联赛ID"><el-input v-model="crawlerMatchForm.leagueId" /></el-form-item><el-form-item label="轮次"><el-input v-model="crawlerMatchForm.round" /></el-form-item><el-form-item label="场馆"><el-input v-model="crawlerMatchForm.venue" /></el-form-item><el-form-item label="主队"><el-input v-model="crawlerMatchForm.homeTeamName" /></el-form-item><el-form-item label="主队ID"><el-input v-model="crawlerMatchForm.homeTeamId" /></el-form-item><el-form-item label="主队Logo"><el-input v-model="crawlerMatchForm.homeTeamLogo" /></el-form-item><el-form-item label="客队"><el-input v-model="crawlerMatchForm.awayTeamName" /></el-form-item><el-form-item label="客队ID"><el-input v-model="crawlerMatchForm.awayTeamId" /></el-form-item><el-form-item label="客队Logo"><el-input v-model="crawlerMatchForm.awayTeamLogo" /></el-form-item><el-form-item label="状态"><el-select v-model="crawlerMatchForm.status"><el-option label="SCHEDULED" value="NS" /><el-option label="LIVE" value="LIVE" /><el-option label="FINISHED" value="FT" /><el-option label="CANCELED" value="CANCEL" /></el-select></el-form-item><el-form-item label="比分"><div class="score-row"><el-input v-model="crawlerMatchForm.homeScore" placeholder="主队" /><span>-</span><el-input v-model="crawlerMatchForm.awayScore" placeholder="客队" /></div></el-form-item><el-form-item label="备注" class="span-2"><el-input v-model="crawlerMatchForm.note" type="textarea" :rows="4" /></el-form-item></el-form><template #footer><el-button @click="crawlerMatchDialogVisible=false">取消</el-button><el-button type="primary" @click="saveCrawlerMatch">保存</el-button></template></el-dialog>
    <el-dialog v-model="videoDialogVisible" :title="videoForm.id ? '编辑视频' : '新增视频'" width="920px" class="admin-dialog"><el-form :model="videoForm" label-width="110px" class="form-grid"><el-form-item label="标题"><el-input v-model="videoForm.title" /></el-form-item><el-form-item label="副标题"><el-input v-model="videoForm.subtitle" /></el-form-item><el-form-item label="描述" class="span-2"><el-input v-model="videoForm.description" type="textarea" :rows="4" /></el-form-item><el-form-item label="封面图" class="span-2"><el-input v-model="videoForm.coverImage" placeholder="请输入外部图片地址" /></el-form-item><el-form-item label="视频链接" class="span-2"><el-input v-model="videoForm.videoUrl" placeholder="请输入外部播放链接" /></el-form-item><el-form-item label="平台"><el-select v-model="videoForm.platform" filterable allow-create default-first-option placeholder="选择或输入平台"><el-option label="YouTube" value="YouTube" /><el-option label="B站" value="B站" /><el-option label="腾讯体育" value="腾讯体育" /><el-option label="腾讯视频" value="腾讯视频" /><el-option label="其他" value="其他" /></el-select></el-form-item><el-form-item label="联赛"><el-input v-model="videoForm.leagueName" /></el-form-item><el-form-item label="主队"><el-input v-model="videoForm.homeTeamName" /></el-form-item><el-form-item label="客队"><el-input v-model="videoForm.awayTeamName" /></el-form-item><el-form-item label="比赛时间"><el-date-picker v-model="videoForm.matchTime" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" placeholder="选择比赛日期" style="width: 100%" /></el-form-item><el-form-item label="类型"><el-select v-model="videoForm.videoType"><el-option label="精彩集锦" value="HIGHLIGHT" /><el-option label="比赛回放" value="REPLAY" /><el-option label="赛后采访" value="INTERVIEW" /><el-option label="其他" value="OTHER" /></el-select></el-form-item><el-form-item label="状态"><el-select v-model="videoForm.status"><el-option label="已发布" value="PUBLISHED" /><el-option label="草稿" value="DRAFT" /><el-option label="隐藏" value="HIDDEN" /></el-select></el-form-item><el-form-item label="热门"><el-switch v-model="videoForm.isHot" :active-value="1" :inactive-value="0" /></el-form-item><el-form-item label="推荐"><el-switch v-model="videoForm.isFeatured" :active-value="1" :inactive-value="0" /></el-form-item><el-form-item label="排序"><el-input-number v-model="videoForm.sortOrder" :min="0" :max="9999" style="width: 100%" /></el-form-item></el-form><template #footer><el-button @click="videoDialogVisible=false">取消</el-button><el-button type="primary" @click="saveVideo">保存</el-button></template></el-dialog>
    <el-dialog v-model="syncProgressVisible" title="更新比赛数据" width="680px" :close-on-click-modal="!syncRunning" :close-on-press-escape="!syncRunning" :show-close="!syncRunning" class="admin-dialog sync-dialog"><div class="sync-progress-head"><div><div class="sync-progress-title">{{ syncProgressTitle }}</div><div class="sync-progress-subtitle">{{ syncProgressSubtitle }}</div></div><el-tag :type="syncRunning ? 'warning' : (syncProgressFailed ? 'danger' : 'success')">{{ syncRunning ? '同步中' : (syncProgressFailed ? '部分失败' : '已完成') }}</el-tag></div><el-progress :percentage="syncProgressPercent" :status="syncProgressFailed && !syncRunning ? 'exception' : (syncRunning ? undefined : 'success')" :stroke-width="14" striped striped-flow /><div class="sync-source-list"><div v-for="item in syncProgressSources" :key="item.key" class="sync-source-item"><div class="sync-source-main"><span class="sync-dot" :class="`is-${item.status}`"></span><div><strong>{{ item.label }}</strong><p>{{ item.message }}</p></div></div><el-tag size="small" :type="syncStatusTag(item.status)">{{ syncStatusText(item.status) }}</el-tag></div></div><template #footer><el-button :disabled="syncRunning" @click="syncProgressVisible=false">关闭</el-button><el-button type="primary" :loading="syncRunning" @click="syncMatchesNow">{{ syncRunning ? '正在同步' : '重新同步' }}</el-button></template></el-dialog>
    <el-drawer v-model="previewVisible" title="资讯预览" size="520px"><div class="preview-cover" v-if="previewRow.coverImage"><img :src="previewRow.coverImage" alt="cover" /></div><div class="preview-title">{{ previewRow.title }}</div><div class="preview-meta"><el-tag>{{ previewRow.category || '未分类' }}</el-tag><el-tag type="success" v-if="previewRow.status">{{ previewRow.status }}</el-tag></div><div class="preview-summary">{{ previewRow.summary }}</div><el-divider /><div class="preview-content">{{ previewRow.content }}</div><el-divider /><el-descriptions :column="1" size="small" border><el-descriptions-item label="作者">{{ previewRow.author || '-' }}</el-descriptions-item><el-descriptions-item label="来源">{{ previewRow.sourceName || '-' }}</el-descriptions-item><el-descriptions-item label="联赛">{{ previewRow.leagueName || '-' }}</el-descriptions-item><el-descriptions-item label="发布时间">{{ previewRow.publishTime || '-' }}</el-descriptions-item></el-descriptions></el-drawer>
    <el-drawer v-model="logDetailVisible" title="日志详情" size="460px"><el-descriptions :column="1" size="small" border><el-descriptions-item label="时间">{{ currentLog.createdAt || '-' }}</el-descriptions-item><el-descriptions-item label="操作人">{{ currentLog.operatorName || '-' }}</el-descriptions-item><el-descriptions-item label="模块">{{ currentLog.module || '-' }}</el-descriptions-item><el-descriptions-item label="动作">{{ currentLog.action || '-' }}</el-descriptions-item><el-descriptions-item label="对象类型">{{ currentLog.targetType || '-' }}</el-descriptions-item><el-descriptions-item label="对象ID">{{ currentLog.targetId || '-' }}</el-descriptions-item><el-descriptions-item label="内容">{{ currentLog.content || '-' }}</el-descriptions-item><el-descriptions-item label="结果"><el-tag :type="currentLog.result === 'SUCCESS' ? 'success' : 'danger'">{{ currentLog.result || '-' }}</el-tag></el-descriptions-item></el-descriptions></el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api/admin'
import AdminOverviewCharts from '../components/charts/AdminOverviewCharts.vue'

const router = useRouter()
const activeMenu = ref('dashboard')
const newsList = ref([])
const matchOverrides = ref([])
const crawlerMatches = ref([])
const users = ref([])
const logs = ref([])
const config = reactive({})
const userKeyword = ref('')
const newsKeyword = ref('')
const videoKeyword = ref('')
const newsStatusFilter = ref('')
const userRoleFilter = ref('')
const userStatusFilter = ref('')
const logModuleFilter = ref('')
const crawlerMatchKeyword = ref('')
const crawlerMatchStatus = ref('')
const crawlerMatchDate = ref('')
const newsDialogVisible = ref(false)
const matchDialogVisible = ref(false)
const crawlerMatchDialogVisible = ref(false)
const videoDialogVisible = ref(false)
const syncProgressVisible = ref(false)
const syncRunning = ref(false)
const syncProgressFailed = ref(false)
const syncProgressTitle = ref('准备更新比赛数据')
const syncProgressSubtitle = ref('正在初始化数据源同步任务')
const syncProgressPercent = ref(0)
const syncProgressSources = ref([])
const previewVisible = ref(false)
const logDetailVisible = ref(false)
const previewRow = reactive({})
const currentLog = reactive({})
const newsForm = reactive({ status: 'DRAFT', isHot: 0, isFeatured: 0, isTop: 0 })
const matchForm = reactive({})
const crawlerMatchForm = reactive({})
const videoForm = reactive({ status: 'PUBLISHED', videoType: 'HIGHLIGHT', isHot: 0, isFeatured: 0, sortOrder: 0 })
const selectedNews = ref([])
const videoList = ref([])
const videoStatusFilter = ref('')
const videoPage = ref(1)
const videoPageSize = ref(20)
const videoTotal = ref(0)
const newsPage = ref(1)
const newsPageSize = ref(20)
const newsTotal = ref(0)
const crawlerMatchPage = ref(1)
const crawlerMatchPageSize = ref(20)
const crawlerMatchTotal = ref(0)
const trendXAxis = computed(() => logs.value.slice(0, 7).map((_, idx) => `D-${6 - idx}`).reverse())
const trendNewsSeries = computed(() => trendXAxis.value.map((_, idx) => Math.max(1, newsList.value.length - idx)))
const trendLogSeries = computed(() => trendXAxis.value.map((_, idx) => Math.max(1, logs.value.length - idx * 2)))
const statusSeries = computed(() => [
  { name: '已发布', value: newsList.value.filter(n => n.status === 'PUBLISHED').length },
  { name: '草稿', value: newsList.value.filter(n => n.status === 'DRAFT').length },
  { name: '隐藏', value: newsList.value.filter(n => n.status === 'HIDDEN').length },
  { name: '删除', value: newsList.value.filter(n => n.status === 'DELETED').length }
])
const todayStr = new Date().toISOString().slice(0, 10)
const todaysNewsCount = computed(() => newsList.value.filter(n => String(n.publishTime || '').slice(0, 10) === todayStr).length)
const todaysMatchCount = computed(() => crawlerMatches.value.filter(m => String(m.matchDate || m.matchTime || '').slice(0, 10) === todayStr).length)
const pendingReviewCount = computed(() => newsList.value.filter(n => n.status !== 'PUBLISHED').length)
const activeUserCount = computed(() => users.value.filter(u => u.status === 'ACTIVE').length)
const topLeagues = computed(() => {
  const map = new Map(); crawlerMatches.value.forEach(m => map.set(m.leagueName || '未知', (map.get(m.leagueName || '未知') || 0) + 1)); return [...map.entries()].sort((a,b)=>b[1]-a[1]).slice(0,5).map(([name,value])=>({name,value}))
})
const topTeams = computed(() => {
  const map = new Map(); crawlerMatches.value.forEach(m => { map.set(m.homeTeamName || '未知', (map.get(m.homeTeamName || '未知') || 0) + 1); map.set(m.awayTeamName || '未知', (map.get(m.awayTeamName || '未知') || 0) + 1) }); return [...map.entries()].sort((a,b)=>b[1]-a[1]).slice(0,5).map(([name,value])=>({name,value}))
})
const hotTags = computed(() => {
  const map = new Map(); newsList.value.forEach(n => (n.tags || []).forEach(t => map.set(t, (map.get(t) || 0) + 1))); return [...map.entries()].sort((a,b)=>b[1]-a[1]).slice(0,8).map(([name,value])=>({name,value}))
})
const radarData = computed(() => [
  Math.min(100, Math.max(10, topLeagues.value.length * 18)),
  Math.min(100, Math.max(10, topTeams.value.length * 12)),
  Math.min(100, Math.max(10, hotTags.value.length * 10)),
  Math.min(100, Math.max(10, newsList.value.length * 2)),
  Math.min(100, Math.max(10, crawlerMatches.value.length * 1.5))
])
const pageTitle = computed(() => ({ dashboard: '管理总览', news: '资讯运营中心', matches: '比赛维护工作台', users: '用户运营中心', config: '系统配置中心', logs: '审计与追踪' }[activeMenu.value]))
const pageSubtitle = computed(() => ({ dashboard: '监控关键指标，快速发现运营机会', news: '管理内容生命周期、状态和推荐位', matches: '维护比赛信息、比分、状态和备注', users: '管理账号、权限与活跃状态', config: '集中维护系统参数，支持热更新', logs: '跟踪后台操作，保障可审计性' }[activeMenu.value]))
const metrics = computed(() => [{ label: '资讯总量', value: newsTotal.value || newsList.value.length, desc: '可编辑内容' }, { label: '比赛维护', value: crawlerMatchTotal.value || crawlerMatches.value.length, desc: '爬虫比赛表' }, { label: '用户数量', value: users.value.length, desc: '账号与角色' }, { label: '日志条目', value: logs.value.length, desc: '操作审计' }])
const recentLogs = computed(() => logs.value.slice(0, 5))
const normalizePageResult = (res) => {
  if (Array.isArray(res)) return { items: res, total: res.length, page: 1, size: res.length }
  if (res && Array.isArray(res.items)) return res
  if (res && Array.isArray(res.response)) return { items: res.response, total: res.total || res.response.length, page: res.page || 1, size: res.size || res.response.length }
  if (res && Array.isArray(res.records)) return { items: res.records, total: res.total || res.records.length, page: res.current || 1, size: res.size || res.records.length }
  if (res && Array.isArray(res.data)) return { items: res.data, total: res.total ?? res.data.length, page: res.page || 1, size: res.size || res.data.length }
  return { items: [], total: 0, page: 1, size: 20 }
}
const mapCrawlerMatchRow = (row) => ({
  id: row?.fixture?.id ?? row?.id ?? row?.fixtureId ?? null,
  fixtureId: row?.fixture?.id ?? row?.fixtureId ?? row?.id ?? null,
  source: row?.source ?? '',
  leagueId: row?.league?.id ?? row?.leagueId ?? '',
  leagueName: row?.league?.name ?? row?.leagueName ?? '',
  homeTeamId: row?.teams?.home?.id ?? row?.homeTeamId ?? '',
  homeTeamName: row?.teams?.home?.name ?? row?.homeTeamName ?? '',
  homeTeamLogo: row?.teams?.home?.logo ?? row?.homeTeamLogo ?? '',
  awayTeamId: row?.teams?.away?.id ?? row?.awayTeamId ?? '',
  awayTeamName: row?.teams?.away?.name ?? row?.awayTeamName ?? '',
  awayTeamLogo: row?.teams?.away?.logo ?? row?.awayTeamLogo ?? '',
  homeScore: row?.goals?.home ?? row?.homeScore ?? null,
  awayScore: row?.goals?.away ?? row?.awayScore ?? null,
  status: row?.fixture?.status?.short ?? row?.status ?? '',
  matchTime: row?.fixture?.date ?? row?.matchTime ?? '',
  matchDate: row?.fixture?.date ? String(row.fixture.date).slice(0, 10) : (row?.matchTime ? String(row.matchTime).slice(0, 10) : ''),
  note: row?.note ?? '',
  createdAt: row?.createdAt ?? '',
  updatedAt: row?.updatedAt ?? '',
})
const loadNews = async (page = newsPage.value) => {
  try {
    newsPage.value = page
    const res = normalizePageResult(await adminApi.listNews(newsKeyword.value, newsStatusFilter.value, newsPage.value, newsPageSize.value))
    newsList.value = res.items || []
    newsTotal.value = res.total || 0
  } catch (e) {
    newsList.value = []
    newsTotal.value = 0
  }
}
const loadMatches = async (page = crawlerMatchPage.value) => {
  try {
    crawlerMatchPage.value = page
    const overrides = await adminApi.listMatchOverrides()
    matchOverrides.value = Array.isArray(overrides) ? overrides : (overrides?.items || overrides?.data || [])
    const res = normalizePageResult(await adminApi.listCrawlerMatches({ keyword: crawlerMatchKeyword.value, status: crawlerMatchStatus.value, date: crawlerMatchDate.value, page: crawlerMatchPage.value, size: crawlerMatchPageSize.value }))
    crawlerMatches.value = (res.items || []).map(mapCrawlerMatchRow)
    crawlerMatchTotal.value = res.total || 0
    console.debug('crawler matches page response', res)
  } catch (e) {
    crawlerMatches.value = []
    crawlerMatchTotal.value = 0
  }
}
const loadUsers = async () => { try { users.value = await adminApi.listUsers(userKeyword.value); if (userRoleFilter.value) users.value = users.value.filter(u => u.role === userRoleFilter.value); if (userStatusFilter.value) users.value = users.value.filter(u => u.status === userStatusFilter.value) } catch (e) { users.value = [] } }
const loadVideos = async (page = videoPage.value) => { try { videoPage.value = page; const res = await adminApi.listVideos({ keyword: videoKeyword.value, status: videoStatusFilter.value, page: videoPage.value, size: videoPageSize.value }); const norm = normalizePageResult(res); videoList.value = norm.items || []; videoTotal.value = norm.total || 0 } catch (e) { videoList.value = []; videoTotal.value = 0 } }
const loadConfig = async () => { try { Object.assign(config, await adminApi.getConfig()) } catch (e) {} }
const loadLogs = async () => { try { logs.value = await adminApi.getLogs(); if (logModuleFilter.value) logs.value = logs.value.filter(l => l.module === logModuleFilter.value) } catch (e) { logs.value = [] } }
const loadDashboard = async () => { await Promise.allSettled([loadNews(1), loadVideos(1), loadMatches(1), loadUsers(), loadConfig(), loadLogs()]) }
const reloadAll = async () => { await loadDashboard(); ElMessage.success('已刷新') }
const goFront = () => router.push('/matches')
const handleNewsSizeChange = (size) => { newsPageSize.value = size; loadNews(1) }
const handleCrawlerMatchSizeChange = (size) => { crawlerMatchPageSize.value = size; loadMatches(1) }
const openNewsDialog = (row = null) => { Object.assign(newsForm, { status: 'DRAFT', isHot: 0, isFeatured: 0, isTop: 0 }, row || {}); newsDialogVisible.value = true }
const openVideoDialog = (row = null) => {
  Object.assign(videoForm, {
    id: null,
    title: '',
    subtitle: '',
    description: '',
    coverImage: '',
    videoUrl: '',
    platform: '',
    leagueName: '',
    homeTeamName: '',
    awayTeamName: '',
    matchTime: '',
    videoType: 'HIGHLIGHT',
    isHot: 0,
    isFeatured: 0,
    sortOrder: 0,
    status: 'PUBLISHED'
  }, row || {})
  videoDialogVisible.value = true
}
const previewVideo = (row) => { window.open(row?.videoUrl || row?.url || row?.video_url || '#', '_blank', 'noopener,noreferrer') }
const saveVideo = async () => {
  const payload = {
    ...videoForm,
    matchTime: videoForm.matchTime ? `${videoForm.matchTime}T00:00:00` : null,
    isHot: Number(videoForm.isHot || 0),
    isFeatured: Number(videoForm.isFeatured || 0),
    sortOrder: Number(videoForm.sortOrder || 0)
  }
  console.debug('saveVideo payload', payload)
  if (payload.id) await adminApi.updateVideo(payload.id, payload)
  else await adminApi.saveVideo(payload)
  videoDialogVisible.value = false
  await loadVideos(videoPage.value)
  ElMessage.success('视频已保存')
}
const setVideoStatus = async (id, status) => { await adminApi.setVideoStatus(id, status); await loadVideos(videoPage.value) }
const deleteVideo = async (id) => { await ElMessageBox.confirm('确认删除该视频？', '提示'); await adminApi.deleteVideo(id); await loadVideos(videoPage.value); ElMessage.success('已删除') }
const handleVideoSizeChange = (size) => { videoPageSize.value = size; loadVideos(1) }
const saveNews = async () => { await adminApi.saveNews({ ...newsForm }); newsDialogVisible.value = false; await loadNews(newsPage.value); await loadLogs(); ElMessage.success('资讯已保存') }
const deleteNews = async (id) => { await ElMessageBox.confirm('确认删除该资讯？', '提示'); await adminApi.deleteNews(id); await loadNews(newsPage.value); ElMessage.success('已删除') }
const setNewsStatus = async (id, status) => { await adminApi.setNewsStatus(id, status); await loadNews(newsPage.value) }
const bulkSetNewsStatus = async (status) => { for (const row of selectedNews.value) await adminApi.setNewsStatus(row.id, status); selectedNews.value = []; await loadNews(newsPage.value) }
const bulkDeleteNews = async () => { await ElMessageBox.confirm(`确认删除选中的 ${selectedNews.value.length} 条资讯？`, '批量删除'); for (const row of selectedNews.value) await adminApi.deleteNews(row.id); selectedNews.value = []; await loadNews(newsPage.value) }
const saveMatchOverride = async () => { await adminApi.saveMatchOverride({ ...matchForm }); matchDialogVisible.value = false; await reloadAll(); ElMessage.success('比赛维护已保存') }
const createNewFixtureId = () => Date.now()
const splitMatchDateTime = (value) => {
  if (!value) return { matchDate: '', matchTime: '' }
  const s = String(value)
  const [datePart, timePart = ''] = s.includes('T') ? s.split('T') : s.split(' ')
  return { matchDate: datePart || '', matchTime: (timePart || '').slice(0, 8) }
}
const openCrawlerMatchDialog = (row = null) => {
  const mapped = row ? mapCrawlerMatchRow(row) : {}
  const dt = splitMatchDateTime(mapped.matchTime || row?.matchTime)
  Object.assign(crawlerMatchForm, {
    id: row?.id ?? null,
    fixtureId: row?.fixtureId ?? createNewFixtureId(),
    source: row?.source || 'crawler',
    leagueId: '',
    leagueName: '',
    round: '',
    venue: '',
    homeTeamId: '',
    homeTeamName: '',
    homeTeamLogo: '',
    awayTeamId: '',
    awayTeamName: '',
    awayTeamLogo: '',
    homeScore: null,
    awayScore: null,
    status: 'NS',
    matchDate: dt.matchDate,
    matchTime: dt.matchTime,
    note: '',
    ...mapped,
    matchDate: row ? dt.matchDate : '',
    matchTime: row ? dt.matchTime : ''
  })
  crawlerMatchDialogVisible.value = true
}
const saveCrawlerMatch = async () => {
  const matchTime = crawlerMatchForm.matchDate
    ? `${crawlerMatchForm.matchDate}T${(crawlerMatchForm.matchTime || '00:00:00').slice(0, 8)}`
    : (crawlerMatchForm.matchTime || null)
  const payload = {
    id: crawlerMatchForm.id ?? null,
    fixtureId: crawlerMatchForm.fixtureId ? Number(crawlerMatchForm.fixtureId) : null,
    externalMatchId: crawlerMatchForm.externalMatchId || String(crawlerMatchForm.fixtureId || ''),
    source: crawlerMatchForm.source || 'crawler',
    leagueId: crawlerMatchForm.leagueId || '',
    leagueName: crawlerMatchForm.leagueName || '',
    round: crawlerMatchForm.round || '',
    venue: crawlerMatchForm.venue || '',
    homeTeamId: crawlerMatchForm.homeTeamId || '',
    homeTeamName: crawlerMatchForm.homeTeamName || '',
    homeTeamLogo: crawlerMatchForm.homeTeamLogo || '',
    awayTeamId: crawlerMatchForm.awayTeamId || '',
    awayTeamName: crawlerMatchForm.awayTeamName || '',
    awayTeamLogo: crawlerMatchForm.awayTeamLogo || '',
    homeScore: crawlerMatchForm.homeScore === '' || crawlerMatchForm.homeScore == null ? null : Number(crawlerMatchForm.homeScore),
    awayScore: crawlerMatchForm.awayScore === '' || crawlerMatchForm.awayScore == null ? null : Number(crawlerMatchForm.awayScore),
    status: crawlerMatchForm.status || 'NS',
    matchTime,
    note: crawlerMatchForm.note || null
  }
  if (!payload.id) {
    await adminApi.saveCrawlerMatch(payload)
  } else {
    await adminApi.updateCrawlerMatch(payload.id, payload)
  }
  crawlerMatchDialogVisible.value = false
  await loadMatches(crawlerMatchPage.value)
  ElMessage.success('爬虫比赛已保存')
}
const deleteCrawlerMatch = async (row) => { const id = row?.id ?? row?.fixtureId; if (!id) { ElMessage.warning('未找到可删除的比赛ID'); return } await adminApi.deleteCrawlerMatch(id); await loadMatches(crawlerMatchPage.value) }
const handleUserCommand = async (cmd, row) => { if (cmd === 'toggleRole') await updateUserRole(row.id, row.role === 'ADMIN' ? 'USER' : 'ADMIN'); if (cmd === 'toggleStatus') await updateUserStatus(row.id, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE') }
const updateUserRole = async (id, role) => { await adminApi.updateUserRole(id, role); await reloadAll() }
const updateUserStatus = async (id, status) => { await adminApi.updateUserStatus(id, status); await reloadAll() }
const syncTasks = [
  { key: 'football-data', label: 'football-data.org 官方数据', action: () => adminApi.syncFootballDataMatches() },
  { key: 'juhe', label: '聚合数据源', action: () => adminApi.syncJuheMatches() },
  { key: 'crawler', label: '网页爬虫数据源', action: () => adminApi.syncCrawlerMatches() }
]
const resetSyncProgress = () => {
  syncProgressFailed.value = false
  syncProgressPercent.value = 0
  syncProgressTitle.value = '准备更新比赛数据'
  syncProgressSubtitle.value = '正在初始化数据源同步任务'
  syncProgressSources.value = syncTasks.map(item => ({ ...item, status: 'pending', message: '等待同步' }))
}
const updateSyncPercent = () => {
  const done = syncProgressSources.value.filter(item => ['success', 'error'].includes(item.status)).length
  syncProgressPercent.value = Math.min(100, Math.round((done / syncProgressSources.value.length) * 100))
}
const setSyncSource = (key, patch) => {
  syncProgressSources.value = syncProgressSources.value.map(item => item.key === key ? { ...item, ...patch } : item)
  updateSyncPercent()
}
const unwrapSyncReport = (res) => res?.summary ? res : (res?.data || res)
const summarizeSyncReport = (report) => `完成：总计 ${report?.total || 0}，新增 ${report?.inserted || 0}，更新 ${report?.updated || 0}`
const syncStatusTag = (status) => ({ pending: 'info', running: 'warning', success: 'success', error: 'danger' }[status] || 'info')
const syncStatusText = (status) => ({ pending: '等待', running: '同步中', success: '完成', error: '失败' }[status] || status)
const saveConfig = async () => { await adminApi.saveConfig(config); await loadConfig(); ElMessage.success('配置已保存') }
const syncMatchesNow = async () => {
  if (syncRunning.value) return
  resetSyncProgress()
  syncProgressVisible.value = true
  syncRunning.value = true
  try {
    for (const task of syncTasks) {
      syncProgressTitle.value = `正在同步：${task.label}`
      syncProgressSubtitle.value = '请不要关闭页面，完成一个数据源后进度会自动更新'
      setSyncSource(task.key, { status: 'running', message: '正在请求后端同步接口...' })
      try {
        const report = unwrapSyncReport(await task.action())
        setSyncSource(task.key, { status: 'success', message: summarizeSyncReport(report), report })
      } catch (e) {
        syncProgressFailed.value = true
        const status = e?.response?.status
        const message = status === 504 ? '同步超时，后端或第三方数据源响应较慢' : (e?.response?.data?.message || e?.message || '同步失败')
        setSyncSource(task.key, { status: 'error', message })
      }
    }
    syncProgressTitle.value = syncProgressFailed.value ? '比赛数据同步完成，部分数据源失败' : '比赛数据同步完成'
    syncProgressSubtitle.value = syncProgressFailed.value ? '成功的数据源已入库，失败的数据源可以稍后点击重新同步' : '所有数据源已同步完成，比赛列表正在刷新'
    if (syncProgressFailed.value) ElMessage.warning('比赛数据同步完成，部分数据源失败')
    else ElMessage.success('全部数据源比赛数据同步完成')
  } finally {
    syncRunning.value = false
    syncProgressPercent.value = 100
    await loadMatches(crawlerMatchPage.value)
    await loadLogs()
  }
}
const openPreview = (row) => { Object.assign(previewRow, row || {}); previewVisible.value = true }
const openLogDetail = (row) => { Object.assign(currentLog, row || {}); logDetailVisible.value = true }
const statusTagType = (status) => ({ PUBLISHED: 'success', DRAFT: 'info', HIDDEN: 'warning', DELETED: 'danger' }[status] || 'info')
const matchStatusTag = (status) => ({ FT: 'success', FINISHED: 'success', LIVE: 'danger', NS: 'info', SCHEDULED: 'info', CANCEL: 'warning', CANCELED: 'warning', POSTP: 'warning' }[status] || 'info')
onMounted(loadDashboard)
</script>

<style scoped>
.admin-shell { min-height: 100vh; background: linear-gradient(180deg, #f7f9fc 0%, #eef3fb 100%); }
.admin-aside { background: #0b1220; color: #fff; min-height: 100vh; padding: 20px 14px; box-shadow: 12px 0 40px rgba(15, 23, 42, .12); }
.brand-block { display:flex; align-items:center; gap:12px; padding: 6px 8px 18px; }
.brand-mark { width:48px; height:48px; border-radius: 16px; background: linear-gradient(135deg,#2563eb,#0ea5e9); display:flex; align-items:center; justify-content:center; font-weight:900; letter-spacing:1px; }
.brand-name { font-size: 18px; font-weight:900; }
.brand-sub { color:#94a3b8; font-size:12px; }
.side-section { margin-top: 14px; }
.side-label { font-size:12px; color:#94a3b8; margin: 0 10px 8px; text-transform: uppercase; letter-spacing: .08em; }
.admin-menu { background: transparent; border-right:none; }
.admin-menu :deep(.el-menu-item){ color:#cbd5e1; border-radius:12px; margin:6px 0; height: 44px; line-height: 44px; }
.admin-menu :deep(.el-menu-item.is-active){ background: linear-gradient(135deg,#2563eb,#1d4ed8); color:#fff; }
.side-meta { background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.08); border-radius: 18px; padding: 12px; }
.meta-item { display:flex; justify-content:space-between; padding: 10px 6px; color:#cbd5e1; border-bottom: 1px solid rgba(255,255,255,.06); }
.meta-item:last-child{ border-bottom:none; }
.meta-item strong { color:#fff; }
.admin-header { display:flex; justify-content:space-between; align-items:center; background: rgba(255,255,255,.8); backdrop-filter: blur(10px); border-bottom:1px solid rgba(226,232,240,.8); padding: 0 24px; }
.header-left { display:flex; flex-direction:column; gap:4px; }
.page-title { font-size: 24px; font-weight: 900; color:#0f172a; }
.page-subtitle { color:#64748b; font-size: 13px; }
.header-actions { display:flex; align-items:center; gap:12px; }
.head-search { width: 260px; }
.admin-main { padding: 24px; }
.dashboard-hero {
  position: relative;
  overflow: hidden;
  display: grid;
  grid-template-columns: 1.2fr .95fr;
  gap: 18px;
  padding: 28px;
  border-radius: 26px;
  margin-bottom: 18px;
  background: linear-gradient(135deg, #0f172a 0%, #1d4ed8 52%, #0ea5e9 100%);
  color: #fff;
  box-shadow: 0 26px 60px rgba(15, 23, 42, .22);
}
.dashboard-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at 80% 10%, rgba(255,255,255,.18), transparent 28%), radial-gradient(circle at 10% 90%, rgba(255,255,255,.10), transparent 30%);
  pointer-events: none;
}
.hero-copy, .hero-stats { position: relative; z-index: 1; }
.hero-kicker { font-size: 12px; letter-spacing: .16em; text-transform: uppercase; opacity: .8; }
.hero-main { margin-top: 10px; font-size: 30px; font-weight: 900; line-height: 1.1; }
.hero-sub { margin-top: 12px; color: rgba(255,255,255,.85); line-height: 1.8; max-width: 720px; }
.hero-stats { display:grid; grid-template-columns: repeat(2,1fr); gap: 12px; align-content: end; }
.hero-stat { background: rgba(255,255,255,.12); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,.14); border-radius: 18px; padding: 18px; transition: transform .24s ease, background .24s ease; }
.hero-stat:hover { transform: translateY(-4px); background: rgba(255,255,255,.18); }
.hero-stat span { display:block; font-size: 12px; opacity: .84; }
.hero-stat strong { display:block; margin-top: 8px; font-size: 30px; }
.dashboard-top { display:grid; grid-template-columns: repeat(4, 1fr); gap:16px; margin-bottom: 18px; }
.metric-card, .panel-card { border:none; border-radius: 18px; }
.metric-card { background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%); transition: transform .24s ease, box-shadow .24s ease; }
.metric-card:hover, .panel-card:hover { transform: translateY(-4px); box-shadow: 0 22px 40px rgba(15, 23, 42, .10); }
.metric-label { color:#64748b; font-size: 13px; }
.metric-value { font-size: 34px; font-weight: 900; margin: 10px 0 6px; color:#0f172a; }
.metric-desc { color:#94a3b8; font-size: 12px; }
.dashboard-grid { display:grid; grid-template-columns: 1.4fr .8fr; gap:16px; }
.span-wide { grid-column: 1 / -1; }
.shortcut-grid { display:grid; grid-template-columns: repeat(4, 1fr); gap:12px; }
.shortcut-grid :deep(.el-button) { height: 48px; }
.panel-header { display:flex; justify-content:space-between; align-items:center; width:100%; }
.panel-actions { display:flex; align-items:center; gap:10px; }
.insight-list { display:grid; grid-template-columns: repeat(2,1fr); gap:12px; }
.insight-item { background:#f8fafc; border:1px solid #e2e8f0; border-radius:14px; padding:16px; display:flex; justify-content:space-between; align-items:center; }
.hotspot-grid { display:grid; grid-template-columns: repeat(2, 1fr); gap: 14px; }
.hotspot-col { background: #f8fbff; border: 1px solid #e2e8f0; border-radius: 16px; padding: 14px; }
.hotspot-title { font-size: 13px; font-weight: 800; color: #0f172a; margin-bottom: 10px; }
.hotspot-list { display:flex; flex-direction:column; gap: 8px; }
.hotspot-item { display:flex; justify-content:space-between; padding: 10px 12px; border-radius: 12px; background: #fff; border: 1px solid #e2e8f0; }
.hot-tag-wrap { display:flex; flex-wrap:wrap; gap:10px; }
.status-badges { display:flex; flex-wrap:wrap; gap:10px; }
.bulk-bar,.pager-bar { margin-top: 14px; display:flex; justify-content:space-between; align-items:center; padding: 12px 14px; background: #f8fafc; border:1px solid #e2e8f0; border-radius: 14px; }
.form-grid { display:grid; grid-template-columns: repeat(2, 1fr); gap: 6px 20px; }
.form-grid :deep(.el-form-item) { margin-bottom: 12px; }
.span-2 { grid-column: span 2; }
.score-row { display:flex; align-items:center; gap:10px; }
.score-row :deep(.el-input) { flex: 1; }
.admin-dialog :deep(.el-dialog__body) { padding-top: 10px; }
.sync-progress-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; margin-bottom:18px; }
.sync-progress-title { font-size:18px; font-weight:900; color:#0f172a; }
.sync-progress-subtitle { margin-top:6px; color:#64748b; font-size:13px; }
.sync-source-list { margin-top:18px; display:flex; flex-direction:column; gap:12px; max-height:360px; overflow:auto; }
.sync-source-item { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:14px; border:1px solid #e2e8f0; border-radius:14px; background:#f8fafc; }
.sync-source-main { display:flex; align-items:flex-start; gap:12px; min-width:0; }
.sync-source-main strong { color:#0f172a; }
.sync-source-main p { margin:4px 0 0; color:#64748b; font-size:13px; line-height:1.5; word-break:break-word; }
.sync-dot { width:10px; height:10px; border-radius:999px; background:#94a3b8; margin-top:5px; flex:none; }
.sync-dot.is-pending { background:#94a3b8; }
.sync-dot.is-running { background:#f59e0b; box-shadow:0 0 0 6px rgba(245,158,11,.12); }
.sync-dot.is-success { background:#22c55e; }
.sync-dot.is-error { background:#ef4444; }
.preview-cover img { width:100%; border-radius: 16px; object-fit: cover; }
.preview-title { font-size: 22px; font-weight: 900; margin: 14px 0 8px; color:#0f172a; }
.preview-meta { display:flex; gap:8px; margin-bottom: 12px; }
.preview-summary { color:#334155; line-height:1.7; }
.preview-content { color:#475569; white-space: pre-wrap; line-height:1.8; }
</style>
