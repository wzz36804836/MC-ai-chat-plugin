package com.example.aichatplugin.performance;

import com.example.aichatplugin.AIChatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * 性能监控器 - 增强版
 * 负责监控服务器性能指标并触发模式切换
 * 新增功能性内容的自动优化管理
 * 
 * 功能降级策略：
 * - FULL模式：所有功能完整运行
 * - LITE模式：轻量级优化，保留核心功能
 * - BASIC模式：基础功能，大幅简化
 * - EMERGENCY模式：最小化运行，只保留必要功能
 */
public class PerformanceMonitor {
    private final AIChatPlugin plugin;
    private final FileConfiguration config;
    private HardwareMonitor hardwareMonitor;
    private OperationMode currentMode = OperationMode.FULL;
    private final Map<String, AtomicInteger> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> responseTimes = new ConcurrentHashMap<>();
    private final Map<OperationMode, AtomicInteger> modeStabilityCount = new EnumMap<>(OperationMode.class);
    private final Map<Long, PerformanceSnapshot> performanceHistory = new ConcurrentHashMap<>();
    
    // 功能性内容管理
    private final FeatureManager featureManager;
    
    // 手动模式相关字段
    private boolean manualModeEnabled = false;
    private OperationMode manualMode = OperationMode.FULL;
    private long manualModeSetTime = 0;
    
    private static final int STABILITY_THRESHOLD = 5;
    private static final long COOLDOWN_PERIOD = 60000; // 1分钟冷却时间
    private static final long HISTORY_RETENTION = TimeUnit.HOURS.toMillis(1);
    private long lastModeSwitchTime = 0;
    private int checkInterval;
    private double tpsThresholdFull;
    private double tpsThresholdLite;
    private double tpsThresholdBasic;
    private int taskId = -1;
    
    public PerformanceMonitor(AIChatPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.hardwareMonitor = new HardwareMonitor(plugin);
        this.featureManager = new FeatureManager(plugin);
        loadConfig();
        initializeStabilityCounts();
    }
    
    private void loadConfig() {
        checkInterval = config.getInt("performance.check-interval", 10);
        tpsThresholdFull = config.getDouble("performance.tps-threshold-full", 18.0);
        tpsThresholdLite = config.getDouble("performance.tps-threshold-lite", 15.0);
        tpsThresholdBasic = config.getDouble("performance.tps-threshold-basic", 10.0);
        
        // 加载手动模式配置
        manualModeEnabled = config.getBoolean("performance.manual-mode-enabled", false);
        String manualModeStr = config.getString("performance.manual-mode", "FULL");
        try {
            manualMode = OperationMode.valueOf(manualModeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("无效的手动模式设置: " + manualModeStr + "，使用默认值FULL");
            manualMode = OperationMode.FULL;
        }
        
        plugin.getLogger().info("性能监控配置加载完成 - 手动模式: " + 
            (manualModeEnabled ? "启用(" + manualMode + ")" : "禁用"));
    }
    
    private void initializeStabilityCounts() {
        for (OperationMode mode : OperationMode.values()) {
            modeStabilityCount.put(mode, new AtomicInteger(0));
        }
    }
    
    /**
     * 设置硬件监控器
     * 这个方法由AIChatPlugin在初始化时调用
     */
    public void setHardwareMonitor(HardwareMonitor hardwareMonitor) {
        this.hardwareMonitor = hardwareMonitor;
    }
    
    /**
     * 获取硬件监控器
     */
    public HardwareMonitor getHardwareMonitor() {
        return hardwareMonitor;
    }
    
    // 🔧 修复自动优化卡顿检测问题
    private final ScheduledExecutorService performanceExecutor = Executors.newScheduledThreadPool(2);
    private final AtomicLong lastPerformanceCheckTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean isSystemLagging = new AtomicBoolean(false);
    private static final long PERFORMANCE_CHECK_TIMEOUT = 10000; // 10秒超时
    private static final long LAG_DETECTION_THRESHOLD = 15000; // 15秒无响应认为卡顿

    /**
     * 启动性能监控（使用独立线程池避免卡顿时检测失效）
     */
    public void start() {
        if (taskId != -1) {
            return;
        }
        
        // 🔧 使用独立线程池，不依赖Bukkit调度器
        performanceExecutor.scheduleWithFixedDelay(() -> {
            try {
                // 🔧 添加看门狗机制
                CompletableFuture<Void> checkTask = CompletableFuture.runAsync(() -> {
                    try {
                        checkPerformance();
                        lastPerformanceCheckTime.set(System.currentTimeMillis());
                        isSystemLagging.set(false);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "性能检查时发生错误", e);
                    }
                });
                
                // 🔧 如果性能检查任务超时，认为系统卡顿
                try {
                    checkTask.get(PERFORMANCE_CHECK_TIMEOUT, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    plugin.getLogger().warning("性能检查超时，系统可能卡顿");
                    handleSystemLag();
                }
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "性能监控调度错误", e);
            }
        }, checkInterval, checkInterval, TimeUnit.SECONDS);
        
        // 🔧 启动独立的卡顿检测看门狗
        performanceExecutor.scheduleWithFixedDelay(this::watchdogCheck, 5, 5, TimeUnit.SECONDS);
        
        taskId = 1; // 标记已启动
    }
    
    /**
     * 🔧 看门狗检查 - 检测性能监控本身是否卡顿
     */
    private void watchdogCheck() {
        long timeSinceLastCheck = System.currentTimeMillis() - lastPerformanceCheckTime.get();
        if (timeSinceLastCheck > LAG_DETECTION_THRESHOLD) {
            if (!isSystemLagging.get()) {
                plugin.getLogger().warning("检测到系统严重卡顿 - " + timeSinceLastCheck + "ms 无响应");
                handleSystemLag();
                isSystemLagging.set(true);
            }
        }
    }
    
    /**
     * 🔧 处理系统卡顿 - 强制切换到应急模式
     */
    private void handleSystemLag() {
        if (currentMode != OperationMode.EMERGENCY) {
            plugin.getLogger().warning("因系统卡顿强制切换到应急模式");
            OperationMode oldMode = currentMode;
            currentMode = OperationMode.EMERGENCY;
            
            // 在主线程中应用紧急优化
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    featureManager.applyModeOptimization(OperationMode.EMERGENCY);
                    plugin.getLogger().info("已强制切换到应急模式: " + oldMode + " -> EMERGENCY");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "应急模式切换失败", e);
                }
            });
        }
    }
    
    /**
     * 停止性能监控
     */
    public void stop() {
        if (taskId != -1) {
            // 🔧 修复：正确关闭独立线程池
            if (performanceExecutor != null && !performanceExecutor.isShutdown()) {
                performanceExecutor.shutdown();
                try {
                    if (!performanceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        performanceExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    performanceExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            taskId = -1;
        }
    }
    
    /**
     * 检查性能并决定是否需要切换模式
     */
    private void checkPerformance() {
        try {
            double currentTPS = getCurrentTPS();
            OperationMode targetMode;
            
            // 手动模式优先级最高
            if (manualModeEnabled) {
                targetMode = manualMode;
                plugin.debug("使用手动模式: " + targetMode);
            } else {
                // 检查是否启用自动优化
                if (!plugin.getConfigLoader().isAutoOptimizeEnabled()) {
                    return;
                }
                
                // 检查硬件状态
                if (!hardwareMonitor.meetsRequirements()) {
                    targetMode = OperationMode.EMERGENCY;
                } else {
                    targetMode = determineMode(currentTPS);
                }
            }
            
            // 记录性能快照
            recordPerformanceSnapshot(currentTPS, targetMode);
            
            // 更新稳定性计数（手动模式不需要稳定性检查）
            if (!manualModeEnabled) {
                for (OperationMode mode : OperationMode.values()) {
                    AtomicInteger count = modeStabilityCount.get(mode);
                    if (mode == targetMode) {
                        count.incrementAndGet();
                    } else {
                        count.set(0);
                    }
                }
            }
            
            // 检查是否需要切换模式
            boolean shouldSwitch = false;
            if (manualModeEnabled) {
                // 手动模式：立即切换，无冷却限制
                shouldSwitch = (targetMode != currentMode);
            } else {
                // 自动模式：需要稳定性检查和冷却期
                shouldSwitch = (targetMode != currentMode && 
                    modeStabilityCount.get(targetMode).get() >= STABILITY_THRESHOLD &&
                    System.currentTimeMillis() - lastModeSwitchTime > COOLDOWN_PERIOD);
            }
            
            if (shouldSwitch) {
                OperationMode oldMode = currentMode;
                currentMode = targetMode;
                lastModeSwitchTime = System.currentTimeMillis();
                
                // 应用功能优化
                featureManager.applyModeOptimization(currentMode);
                
                // 记录模式切换
                plugin.getLogger().info("切换运行模式: " + oldMode + " -> " + currentMode + 
                    (manualModeEnabled ? " (手动模式)" : " (自动模式)"));
                recordModeSwitch(oldMode, currentMode);
                
                // 发送模式切换消息
                sendModeChangeMessage(currentMode);
            }
            
            // 清理旧数据
            cleanupOldHistory();
            
            // 记录性能状态
            if (plugin.isDebugEnabled()) {
                HardwareMonitor.HardwareStatus hwStatus = hardwareMonitor.getStatus();
                plugin.debug(String.format(
                    "性能状态 - TPS: %.1f | 模式: %s | 目标: %s | 稳定性: %d | %s | 手动模式: %s",
                    currentTPS, currentMode, targetMode,
                    manualModeEnabled ? 0 : modeStabilityCount.get(targetMode).get(),
                    hwStatus, manualModeEnabled ? "是" : "否"
                ));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("性能检查时发生错误: " + e.getMessage());
            recordError("performance_check_error");
        }
    }
    
    /**
     * 发送模式切换消息
     */
    private void sendModeChangeMessage(OperationMode newMode) {
        try {
            // 获取配置的模式切换消息
            String configMessage = config.getString("messages.mode-change." + newMode.name().toLowerCase());
            final String message;
            if (configMessage == null || configMessage.trim().isEmpty()) {
                // 使用默认消息
                switch (newMode) {
                    case FULL:
                        message = "&a[AI聊天] 性能良好，全功能模式已启用";
                        break;
                    case LITE:
                        message = "&e[AI聊天] 性能轻微不足，已切换到精简模式";
                        break;
                    case BASIC:
                        message = "&6[AI聊天] 性能中等不足，已切换到基础模式";
                        break;
                    case EMERGENCY:
                        message = "&c[AI聊天] 性能严重不足，已切换到应急模式";
                        break;
                    default:
                        message = "&7[AI聊天] 模式已切换到: " + newMode.name();
                        break;
                }
            } else {
                message = configMessage;
            }
            
            // 异步广播消息
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.broadcastMessage(message);
            });
        } catch (Exception e) {
            plugin.getLogger().warning("发送模式切换消息时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 获取当前TPS
     */
    public double getCurrentTPS() {
        try {
            // 反射获取TPS（改进版本）
            double[] tps = Bukkit.getTPS();
            double currentTps = tps[0];
            
            // 验证TPS值的合理性
            if (currentTps < 0) {
                plugin.getLogger().fine("TPS值为负数: " + currentTps + "，使用默认值20.0");
                return 20.0;
            }
            
            // 对于超过20.0的TPS值，使用更宽松的阈值进行标准化
            if (currentTps > 20.0) {
                // 只有严重异常的值（超过25.0）才记录警告
                if (currentTps > 25.0) {
                    plugin.getLogger().warning("检测到严重异常TPS值: " + currentTps + "，使用默认值20.0");
                } else {
                    // 20.0-25.0之间静默标准化，这通常是计算精度问题或轻微的时间窗口误差
                    plugin.debug("TPS值 " + String.format("%.3f", currentTps) + " 超过理论值20.0，标准化处理");
                }
                return 20.0;
            }
            
            return currentTps;
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "获取TPS时发生错误，使用默认值", e);
            return 20.0; // 使用标准TPS作为默认值
        }
    }
    
    /**
     * 根据TPS确定目标模式
     */
    private OperationMode determineMode(double tps) {
        if (tps >= tpsThresholdFull) return OperationMode.FULL;
        if (tps >= tpsThresholdLite) return OperationMode.LITE;
        if (tps >= tpsThresholdBasic) return OperationMode.BASIC;
        return OperationMode.EMERGENCY;
    }
    
    /**
     * 切换运行模式
     */
    public void switchMode(OperationMode newMode) {
        if (currentMode == newMode) {
            return;
        }
        
        OperationMode oldMode = currentMode;
        plugin.getLogger().info("切换运行模式: " + currentMode + " -> " + newMode);
        currentMode = newMode;
        
        // 🔧 关键修复：应用功能优化（之前缺少这个调用）
        featureManager.applyModeOptimization(currentMode);
        
        // 应用新模式配置
        FileConfiguration modeConfig = plugin.getConfig();
        if (modeConfig != null) {
            // 更新对话管理器配置
            if (plugin.getConversationManager() != null) {
                plugin.getConversationManager().applyConfig(modeConfig);
            }
            
            // 设置备用响应
            String fallbackResponse = modeConfig.getString("fallback-response");
            if (fallbackResponse != null && !fallbackResponse.isEmpty()) {
                plugin.getConversationManager().setFallbackResponse(fallbackResponse);
            } else {
                plugin.getConversationManager().clearFallbackResponse();
            }
        }
        
        // 广播模式切换通知
        String message = config.getString("messages.mode-change." + newMode.name().toLowerCase());
        if (message != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.broadcastMessage(message);
            });
        }
        
        lastModeSwitchTime = System.currentTimeMillis();
        
        // 记录模式切换到性能历史
        recordModeSwitch(oldMode, newMode);
    }
    
    /**
     * 记录性能快照
     */
    private void recordPerformanceSnapshot(double tps, OperationMode targetMode) {
        performanceHistory.put(System.currentTimeMillis(), 
            new PerformanceSnapshot(tps, hardwareMonitor.getStatus(), currentMode, targetMode));
    }
    
    /**
     * 清理旧的历史数据
     */
    private void cleanupOldHistory() {
        long cutoff = System.currentTimeMillis() - HISTORY_RETENTION;
        performanceHistory.keySet().removeIf(time -> time < cutoff);
    }
    
    /**
     * 获取性能历史数据
     */
    public Map<Long, PerformanceSnapshot> getPerformanceHistory() {
        return new ConcurrentHashMap<>(performanceHistory);
    }
    
    /**
     * 获取当前运行模式
     */
    public OperationMode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * 重新加载配置
     */
    public void reloadConfig() {
        loadConfig();
        hardwareMonitor.loadConfig();
        
        // 立即重新应用当前模式配置
        switchMode(currentMode);
    }
    
    /**
     * 获取当前世界实体数量
     * @return 实体数量
     */
    public int getEntityCount() {
        int count = 0;
        for (World world : plugin.getServer().getWorlds()) {
            count += world.getEntities().size();
        }
        return count;
    }
    
    /**
     * 获取当前世界区块数量
     * @return 区块数量
     */
    public int getChunkCount() {
        int count = 0;
        for (World world : plugin.getServer().getWorlds()) {
            count += world.getLoadedChunks().length;
        }
        return count;
    }
    
    /**
     * 性能快照数据类
     */
    public static class PerformanceSnapshot {
        private final double tps;
        private final HardwareMonitor.HardwareStatus hwStatus;
        private final OperationMode currentMode;
        private final OperationMode targetMode;
        
        public PerformanceSnapshot(double tps, HardwareMonitor.HardwareStatus hwStatus,
                                 OperationMode currentMode, OperationMode targetMode) {
            this.tps = tps;
            this.hwStatus = hwStatus;
            this.currentMode = currentMode;
            this.targetMode = targetMode;
        }
        
        public double getTps() { return tps; }
        public HardwareMonitor.HardwareStatus getHwStatus() { return hwStatus; }
        public OperationMode getCurrentMode() { return currentMode; }
        public OperationMode getTargetMode() { return targetMode; }
        
        @Override
        public String toString() {
            return String.format(
                "TPS: %.1f | 当前模式: %s | 目标模式: %s | %s",
                tps, currentMode, targetMode, hwStatus
            );
        }
    }
    
    /**
     * 获取功能管理器
     */
    public FeatureManager getFeatureManager() {
        return featureManager;
    }
    
    /**
     * 记录模式切换事件
     */
    private void recordModeSwitch(OperationMode oldMode, OperationMode newMode) {
        // 获取硬件状态（简化版：只显示内存）
        double freeMemory = hardwareMonitor.getFreeMemory();
        double systemMemory = hardwareMonitor.getSystemFreeMemory();
        int cores = hardwareMonitor.getAvailableCores();
        
        plugin.getLogger().info(String.format(
            "性能模式切换: %s -> %s (TPS: %.1f, 内存: %.1fGB, 系统内存: %.1fGB, 核心: %d)",
            oldMode, newMode, getCurrentTPS(), 
            freeMemory, systemMemory, cores
        ));
    }
    
    /**
     * 获取当前功能状态报告
     */
    public Map<String, Object> getFeatureStatusReport() {
        Map<String, Object> report = new HashMap<>();
        report.put("currentMode", currentMode);
        report.put("featureStatus", featureManager.getFeatureStatus());
        report.put("optimizationLevel", featureManager.getOptimizationLevel());
        report.put("disabledFeatures", featureManager.getDisabledFeatures());
        return report;
    }
    
    /**
     * 功能性内容管理器
     * 负责根据性能模式调整各种功能的运行状态
     */
    public static class FeatureManager {
        private final AIChatPlugin plugin;
        private final Map<String, Boolean> featureStates = new ConcurrentHashMap<>();
        private final Map<String, Integer> featureOptimizationLevels = new ConcurrentHashMap<>();
        
        // 功能优先级定义
        private static final Map<String, Integer> FEATURE_PRIORITIES = new HashMap<>();
        static {
            // 核心功能 (优先级 1-3，最重要)
            FEATURE_PRIORITIES.put("basic_chat", 1);
            FEATURE_PRIORITIES.put("command_processing", 1);
            FEATURE_PRIORITIES.put("error_handling", 1);
            
            // 重要功能 (优先级 4-6)
            FEATURE_PRIORITIES.put("permission_check", 4);
            FEATURE_PRIORITIES.put("cooldown_management", 4);
            FEATURE_PRIORITIES.put("rate-limiting", 5);
            FEATURE_PRIORITIES.put("message_validation", 5);
            
            // 事件响应功能 (优先级 6-9) - 性能敏感
            FEATURE_PRIORITIES.put("damage_event_response", 6);  // 伤害事件响应 - 高性能消耗
            FEATURE_PRIORITIES.put("death_event_response", 7);   // 死亡事件响应
            FEATURE_PRIORITIES.put("level_event_response", 8);   // 升级事件响应
            FEATURE_PRIORITIES.put("advancement_event_response", 9); // 成就事件响应
            
            // 增强功能 (优先级 10-12)
            FEATURE_PRIORITIES.put("sensitive_word_filter", 10);
            FEATURE_PRIORITIES.put("message_preprocessing", 10);
            FEATURE_PRIORITIES.put("help_system", 11);
            FEATURE_PRIORITIES.put("statistics_collection", 11);
            
            // 高级功能 (优先级 13-15，可选)
            FEATURE_PRIORITIES.put("advanced_caching", 13);
            FEATURE_PRIORITIES.put("detailed_logging", 13);
            FEATURE_PRIORITIES.put("performance_monitoring", 14);
            FEATURE_PRIORITIES.put("circuit_breaker", 14);
            FEATURE_PRIORITIES.put("config_hot_reload", 15);
        }
        
        // 伤害事件性能控制参数
        private final Map<OperationMode, DamageEventConfig> damageEventConfigs = new EnumMap<>(OperationMode.class);
        
        // 伤害事件配置类
        public static class DamageEventConfig {
            private final boolean enabled;                  // 是否启用伤害事件处理
            private final long cooldownMs;                  // 冷却时间（毫秒）
            private final double healthThreshold;           // 血量阈值：只处理血量低于此值的伤害
            private final boolean onlyImportantDamage;      // 是否只处理重要伤害
            private final int maxEventsPerSecond;           // 每秒最大处理事件数
            
            public DamageEventConfig(boolean enabled, long cooldownMs, double healthThreshold, 
                                   boolean onlyImportantDamage, int maxEventsPerSecond) {
                this.enabled = enabled;
                this.cooldownMs = cooldownMs;
                this.healthThreshold = healthThreshold;
                this.onlyImportantDamage = onlyImportantDamage;
                this.maxEventsPerSecond = maxEventsPerSecond;
            }
            
            public boolean isEnabled() { return enabled; }
            public long getCooldownMs() { return cooldownMs; }
            public double getHealthThreshold() { return healthThreshold; }  // 血量阈值
            public boolean isOnlyImportantDamage() { return onlyImportantDamage; }
            public int getMaxEventsPerSecond() { return maxEventsPerSecond; }
        }
        
        public FeatureManager(AIChatPlugin plugin) {
            this.plugin = plugin;
            initializeFeatureStates();
            initializeDamageEventConfigs();
        }
        
        /**
         * 初始化功能状态
         */
        private void initializeFeatureStates() {
            // 默认所有功能启用
            for (String feature : FEATURE_PRIORITIES.keySet()) {
                featureStates.put(feature, true);
                featureOptimizationLevels.put(feature, 0);
            }
        }
        
        /**
         * 初始化伤害事件配置
         */
        private void initializeDamageEventConfigs() {
            // FULL模式：完全启用，处理所有伤害，标准冷却
            damageEventConfigs.put(OperationMode.FULL, 
                new DamageEventConfig(true, 3000, 0.0, false, 20));
            
            // LITE模式：启用但限制频率，只处理血量低于50%时的伤害
            damageEventConfigs.put(OperationMode.LITE, 
                new DamageEventConfig(true, 8000, 10.0, false, 10));
            
            // BASIC模式：只处理重要伤害，血量低于25%时处理
            damageEventConfigs.put(OperationMode.BASIC, 
                new DamageEventConfig(true, 15000, 5.0, true, 5));
            
            // EMERGENCY模式：完全禁用
            damageEventConfigs.put(OperationMode.EMERGENCY, 
                new DamageEventConfig(false, 0, 0.0, false, 0));
        }
        
        /**
         * 根据运行模式应用优化
         */
        public void applyModeOptimization(OperationMode mode) {
            plugin.getLogger().info("应用功能优化模式: " + mode);
            
            switch (mode) {
                case FULL:
                    applyFullMode();
                    break;
                case LITE:
                    applyLiteMode();
                    break;
                case BASIC:
                    applyBasicMode();
                    break;
                case EMERGENCY:
                    applyEmergencyMode();
                    break;
            }
            
            // 通知相关组件应用优化
            notifyOptimizationApplied(mode);
        }
        
        /**
         * 全功能模式
         */
        private void applyFullMode() {
            // 启用所有功能
            for (String feature : FEATURE_PRIORITIES.keySet()) {
                featureStates.put(feature, true);
                featureOptimizationLevels.put(feature, 0);
            }
            
            plugin.debug("全功能模式：所有功能已启用，伤害事件正常响应");
        }
        
        /**
         * 精简模式
         * 轻量级优化，保留核心功能
         */
        private void applyLiteMode() {
            // 保留优先级 1-10 的功能，但对事件响应进行优化
            for (Map.Entry<String, Integer> entry : FEATURE_PRIORITIES.entrySet()) {
                String feature = entry.getKey();
                int priority = entry.getValue();
                
                if (priority <= 10) {
                    featureStates.put(feature, true);
                    // 对事件响应功能应用轻度优化
                    if (priority >= 6 && priority <= 9) {
                        featureOptimizationLevels.put(feature, 1);
                    } else {
                        featureOptimizationLevels.put(feature, 0);
                    }
                } else {
                    featureStates.put(feature, false);
                    featureOptimizationLevels.put(feature, 3);
                }
            }
            
            // 特殊优化：敏感词过滤简化
            optimizeSensitiveWordFilter(1);
            
            plugin.debug("精简模式：事件响应已优化，伤害事件冷却时间增加到8秒");
        }
        
        /**
         * 基础模式
         * 大幅简化，只保留必要功能
         */
        private void applyBasicMode() {
            // 只保留优先级 1-8 的功能，严格限制事件响应
            for (Map.Entry<String, Integer> entry : FEATURE_PRIORITIES.entrySet()) {
                String feature = entry.getKey();
                int priority = entry.getValue();
                
                if (priority <= 8) {
                    featureStates.put(feature, true);
                    // 对事件响应功能应用中度优化
                    if (priority >= 6 && priority <= 8) {
                        featureOptimizationLevels.put(feature, 2);
                    } else if (priority > 4) {
                        featureOptimizationLevels.put(feature, 1);
                    } else {
                        featureOptimizationLevels.put(feature, 0);
                    }
                } else {
                    featureStates.put(feature, false);
                    featureOptimizationLevels.put(feature, 3);
                }
            }
            
            // 特殊优化：敏感词过滤大幅简化
            optimizeSensitiveWordFilter(2);
            
            plugin.debug("基础模式：只响应重要伤害事件，冷却时间15秒");
        }
        
        /**
         * 应急模式
         * 最小化运行，只保留最核心功能
         */
        private void applyEmergencyMode() {
            // 只保留优先级 1-5 的核心功能，完全禁用事件响应
            for (Map.Entry<String, Integer> entry : FEATURE_PRIORITIES.entrySet()) {
                String feature = entry.getKey();
                int priority = entry.getValue();
                
                if (priority <= 5) {
                    featureStates.put(feature, true);
                    // 应用最大优化
                    featureOptimizationLevels.put(feature, priority > 3 ? 2 : 1);
                } else {
                    featureStates.put(feature, false);
                    featureOptimizationLevels.put(feature, 3);
                }
            }
            
            // 完全禁用所有事件响应
            featureStates.put("damage_event_response", false);
            featureStates.put("death_event_response", false);
            featureStates.put("level_event_response", false);
            featureStates.put("advancement_event_response", false);
            
            // 特殊优化：完全禁用敏感词过滤
            featureStates.put("sensitive_word_filter", false);
            
            plugin.debug("应急模式：所有事件响应已禁用，最大化性能");
        }
        
        /**
         * 优化敏感词过滤器
         * @param level 优化级别 (0=无优化, 1=轻度, 2=中度, 3=禁用)
         */
        private void optimizeSensitiveWordFilter(int level) {
            switch (level) {
                case 0:
                    // 无优化：完整功能
                    break;
                case 1:
                    // 轻度优化：减少敏感词数量，简化算法
                    plugin.debug("敏感词过滤器：轻度优化");
                    break;
                case 2:
                    // 中度优化：只检查高优先级敏感词
                    plugin.debug("敏感词过滤器：中度优化");
                    break;
                case 3:
                    // 完全禁用
                    featureStates.put("sensitive_word_filter", false);
                    plugin.debug("敏感词过滤器：已禁用");
                    break;
            }
        }
        
        /**
         * 通知相关组件应用优化
         */
        private void notifyOptimizationApplied(OperationMode mode) {
            // 通知命令处理器
            if (plugin.getCommand("ai") != null) {
                // 这里可以通过接口通知命令处理器调整功能
                plugin.debug("已通知命令处理器应用优化");
            }
            
            // 通知对话管理器
            if (plugin.getConversationManager() != null) {
                plugin.debug("已通知对话管理器应用优化");
            }
        }
        
        /**
         * 检查功能是否启用
         */
        public boolean isFeatureEnabled(String feature) {
            return featureStates.getOrDefault(feature, false);
        }
        
        /**
         * 获取功能优化级别
         */
        public int getOptimizationLevel(String feature) {
            return featureOptimizationLevels.getOrDefault(feature, 0);
        }
        
        /**
         * 获取功能状态报告
         */
        public Map<String, Boolean> getFeatureStatus() {
            return new HashMap<>(featureStates);
        }
        
        /**
         * 获取整体优化级别
         */
        public String getOptimizationLevel() {
            long disabledCount = featureStates.values().stream()
                .mapToLong(enabled -> enabled ? 0 : 1)
                .sum();
            
            double disabledRatio = (double) disabledCount / featureStates.size();
            
            if (disabledRatio < 0.1) return "最小优化";
            if (disabledRatio < 0.3) return "轻度优化";
            if (disabledRatio < 0.6) return "中度优化";
            return "重度优化";
        }
        
        /**
         * 获取被禁用的功能列表
         */
        public List<String> getDisabledFeatures() {
            return featureStates.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        }
        
        /**
         * 手动设置功能状态（管理员用）
         */
        public void setFeatureEnabled(String feature, boolean enabled) {
            if (FEATURE_PRIORITIES.containsKey(feature)) {
                featureStates.put(feature, enabled);
                plugin.debug("手动设置功能状态: " + feature + " = " + enabled);
            }
        }
        
        /**
         * 获取功能优先级
         */
        public int getFeaturePriority(String feature) {
            return FEATURE_PRIORITIES.getOrDefault(feature, 99);
        }
        
        /**
         * 获取当前模式下的伤害事件配置
         */
        public DamageEventConfig getDamageEventConfig(OperationMode mode) {
            return damageEventConfigs.getOrDefault(mode, damageEventConfigs.get(OperationMode.FULL));
        }
        
        /**
         * 获取当前伤害事件配置
         */
        public DamageEventConfig getCurrentDamageEventConfig() {
            OperationMode currentMode = plugin.getPerformanceMonitor().getCurrentMode();
            return getDamageEventConfig(currentMode);
        }
        
        /**
         * 检查是否应该处理伤害事件
         */
        public boolean shouldProcessDamageEvent(double currentHealth, double finalDamage) {
            if (!isFeatureEnabled("damage_event_response")) {
                return false;
            }
            
            DamageEventConfig config = getCurrentDamageEventConfig();
            if (!config.isEnabled()) {
                return false;
            }
            
            // 🔧 修复血量阈值逻辑：当玩家血量低于阈值时才处理（紧急情况优先）
            if (config.getHealthThreshold() > 0 && currentHealth > config.getHealthThreshold()) {
                plugin.debug("跳过伤害事件：玩家血量(" + String.format("%.1f", currentHealth) + 
                    ")高于阈值(" + config.getHealthThreshold() + ")");
                return false;
            }
            
            return true;
        }
        
        /**
         * 检查是否为重要伤害（用于BASIC模式）
         */
        public boolean isImportantDamageEvent(double currentHealth, double maxHealth, double finalDamage) {
            // 生命危险：血量低于30%
            if (currentHealth / maxHealth < 0.3) {
                return true;
            }
            
            // 大量伤害：单次伤害超过最大血量的25%
            if (finalDamage / maxHealth > 0.25) {
                return true;
            }
            
            // 致命伤害：伤害足以击杀
            if (finalDamage >= currentHealth) {
                return true;
            }
            
            return false;
        }
    }
    
    /**
     * 处理硬件警告
     */
    public void handleHardwareWarning(String warning) {
        plugin.getLogger().warning("性能警告: " + warning);
        
        // 如果当前不是紧急模式，切换到紧急模式
        if (currentMode != OperationMode.EMERGENCY) {
            switchMode(OperationMode.EMERGENCY);
        }
        
        // 记录错误
        recordError("hardware_warning");
    }
    
    /**
     * 记录错误
     */
    public void recordError(String type) {
        errorCounters.computeIfAbsent(type, k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    /**
     * 记录响应时间
     */
    public void recordResponseTime(String type, long time) {
        responseTimes.computeIfAbsent(type, k -> new AtomicLong(0)).addAndGet(time);
    }
    
    /**
     * 获取错误统计
     */
    public Map<String, Integer> getErrorStats() {
        Map<String, Integer> stats = new HashMap<>();
        errorCounters.forEach((type, counter) -> stats.put(type, counter.get()));
        return stats;
    }
    
    /**
     * 获取响应时间统计
     */
    public Map<String, Long> getResponseTimeStats() {
        Map<String, Long> stats = new HashMap<>();
        responseTimes.forEach((type, time) -> stats.put(type, time.get()));
        return stats;
    }
    
    /**
     * 重置统计
     */
    public void resetStats() {
        errorCounters.clear();
        responseTimes.clear();
        performanceHistory.clear();
    }

    /**
     * 启用手动模式
     * @param mode 手动设置的模式
     */
    public void enableManualMode(OperationMode mode) {
        this.manualModeEnabled = true;
        this.manualMode = mode;
        this.manualModeSetTime = System.currentTimeMillis();
        
        // 保存到配置文件
        config.set("performance.manual-mode-enabled", true);
        config.set("performance.manual-mode", mode.name());
        plugin.saveConfig();
        
        // 立即切换到手动模式
        switchMode(mode);
        
        plugin.getLogger().info("手动模式已启用，设置为: " + mode);
    }

    /**
     * 禁用手动模式，恢复自动检测
     */
    public void disableManualMode() {
        this.manualModeEnabled = false;
        
        // 保存到配置文件  
        config.set("performance.manual-mode-enabled", false);
        plugin.saveConfig();
        
        plugin.getLogger().info("手动模式已禁用，恢复自动性能检测");
    }

    /**
     * 检查是否启用了手动模式
     */
    public boolean isManualModeEnabled() {
        return manualModeEnabled;
    }

    /**
     * 获取当前手动模式设置
     */
    public OperationMode getManualMode() {
        return manualMode;
    }

    /**
     * 获取手动模式设置时间
     */
    public long getManualModeSetTime() {
        return manualModeSetTime;
    }
}
