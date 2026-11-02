package com.example.aichatplugin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.bukkit.Material;

/**
 * 配置加载器
 * 
 * 职责：
 * 1. 集中管理所有插件配置
 * 2. 提供类型安全的配置访问
 * 3. 处理配置文件的加载和保存
 * 4. 维护配置的一致性
 * 
 * 配置分类：
 * 1. settings: API和模型相关配置
 * 2. chat: 聊天功能配置
 * 3. messages: 消息格式配置
 * 4. environment: 环境信息配置
 * 5. player-status: 玩家状态响应配置
 * 6. debug: 调试相关配置
 * 7. conversation: 对话管理配置
 * 8. player-profile: 玩家档案配置
 */
public class ConfigLoader {
    private final File configFile;
    private final File promptsFile;
    private FileConfiguration config;
    private FileConfiguration promptsConfig;
    private final AIChatPlugin plugin;
    private ScheduledExecutorService scheduler;
    private long lastModified;
    private long promptsLastModified;
    private final Object configLock = new Object();
    private final Set<ConfigChangeListener> listeners = new HashSet<>();
    private volatile boolean isShuttingDown = false;
    
    // 配置分类
    private static final String SETTINGS = "settings";
    private static final String CHAT = "chat";
    private static final String MESSAGES = "messages";
    private static final String ENVIRONMENT = "environment";
    private static final String PLAYER_STATUS = "player-status";
    private static final String DEBUG = "debug";
    private static final String CONVERSATION = "conversation";
    private static final String PLAYER_PROFILE = "player-profile";
    private static final String PERFORMANCE = "performance";
    private static final String TESTING = "testing";
    private static final String DEVELOPMENT = "development";
    
    // 默认值
    private static final String DEFAULT_API_URL = "https://api.deepseek.com/chat/completions/";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final int DEFAULT_TIMEOUT = 10;
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final int DEFAULT_MAX_TOKENS = 150;
    private static final String DEFAULT_CHAT_PREFIX = "!";
    private static final int DEFAULT_DETECTION_RANGE = 10;
    private static final double DEFAULT_DAMAGE_THRESHOLD = 0.3;
    private static final long DEFAULT_DAMAGE_COOLDOWN = 1000;
    private static final int DEFAULT_MAX_HISTORY = 5;
    private static final int DEFAULT_MAX_CONTEXT = 1000;
    private static final int DEFAULT_SAVE_INTERVAL = 300;
    
    // 性能优化默认值
    private static final boolean DEFAULT_AUTO_OPTIMIZE = false;
    private static final int DEFAULT_CHECK_INTERVAL = 30;
    private static final double DEFAULT_CPU_THRESHOLD = 80.0;
    private static final double DEFAULT_MEMORY_THRESHOLD = 80.0;
    private static final double DEFAULT_TPS_THRESHOLD = 18.0;
    private static final int DEFAULT_ENTITY_THRESHOLD = 100;
    private static final int DEFAULT_CHUNK_THRESHOLD = 100;
    
    // 调试默认值
    private static final boolean DEFAULT_DEBUG_ENABLED = false;
    private static final boolean DEFAULT_VERBOSE_LOGGING = false;
    private static final boolean DEFAULT_SHOW_PERFORMANCE = true;
    private static final boolean DEFAULT_MOCK_API = false;
    private static final int DEFAULT_MOCK_DELAY = 200;
    private static final boolean DEFAULT_LOG_ENVIRONMENT = false;
    private static final boolean DEFAULT_LOG_PLAYER_STATUS = false;
    private static final boolean DEFAULT_ENABLE_PROFILING = false;
    private static final int DEFAULT_PROFILING_INTERVAL = 500;
    private static final boolean DEFAULT_LOG_MEMORY = false;
    private static final int DEFAULT_MEMORY_LOG_INTERVAL = 60;
    
    // 测试默认值
    private static final boolean DEFAULT_TESTING_ENABLED = false;
    private static final boolean DEFAULT_ALLOW_NO_API_KEY = false;
    private static final boolean DEFAULT_STRESS_TEST = false;
    private static final int DEFAULT_STRESS_TEST_CONCURRENCY = 10;
    private static final int DEFAULT_STRESS_TEST_DURATION = 300;
    private static final boolean DEFAULT_ERROR_INJECTION = false;
    private static final int DEFAULT_ERROR_INJECTION_RATE = 5;
    private static final boolean DEFAULT_NETWORK_LATENCY = false;
    private static final int DEFAULT_MIN_LATENCY = 50;
    private static final int DEFAULT_MAX_LATENCY = 200;
    private static final boolean DEFAULT_PERSISTENCE_TEST = false;
    private static final int DEFAULT_PERSISTENCE_TEST_INTERVAL = 5;
    
    // 开发默认值
    private static final boolean DEFAULT_DEV_ENABLED = false;
    private static final boolean DEFAULT_HOT_RELOAD = false;
    private static final int DEFAULT_HOT_RELOAD_INTERVAL = 5;
    private static final boolean DEFAULT_VALIDATE_CONFIG = true;
    private static final boolean DEFAULT_CACHE_API_RESPONSES = false;
    private static final int DEFAULT_API_CACHE_TTL = 300;
    private static final boolean DEFAULT_DETAILED_ERROR_STACK = true;
    private static final boolean DEFAULT_PERFORMANCE_MONITORING = true;
    private static final int DEFAULT_MONITORING_INTERVAL = 500;
    
    // 🔧 改进：线程安全的缓存状态管理
    private static class CacheState {
        final boolean valid;
        final long updateTime;
        final String apiKeyCache;
        final String chatPrefixCache;
        final boolean chatEnabledCache;
        final boolean debugEnabledCache;
        
        CacheState(boolean valid, long updateTime, String apiKey, String chatPrefix, 
                  boolean chatEnabled, boolean debugEnabled) {
            this.valid = valid;
            this.updateTime = updateTime;
            this.apiKeyCache = apiKey;
            this.chatPrefixCache = chatPrefix;
            this.chatEnabledCache = chatEnabled;
            this.debugEnabledCache = debugEnabled;
        }
        
        boolean isExpired(long ttl) {
            return System.currentTimeMillis() - updateTime > ttl;
        }
    }
    
    private final AtomicReference<CacheState> cacheState = 
        new AtomicReference<>(new CacheState(false, 0, "", "", false, false));
    private static final long CACHE_TTL = 5000; // 缓存有效期5秒
    
    // 🔧 常用配置的缓存
    private volatile boolean debugEnabledCache;
    private volatile boolean chatEnabledCache;
    private volatile String chatPrefixCache;
    private volatile boolean broadcastEnabledCache;
    private volatile boolean filterEnabledCache;
    private volatile int maxMessagesPerMinuteCache;
    private volatile long normalUserCooldownCache;
    private volatile long vipUserCooldownCache;
    private volatile String apiKeyCache;
    private volatile String apiUrlCache;
    private volatile String modelCache;
    private volatile String roleSystemCache;
    private volatile double temperatureCache;
    private volatile int maxTokensCache;
    
    public ConfigLoader(AIChatPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        this.promptsFile = new File(plugin.getDataFolder(), "prompts.yml");
        loadConfig();
        loadPrompts();
        if (isHotReloadEnabled()) {
            startHotReload();
        }
    }
    
    /**
     * 加载配置
     */
    private void loadConfig() {
        synchronized (configLock) {
            if (!configFile.exists()) plugin.saveDefaultConfig();
            try {
                YamlConfiguration candidate = new YamlConfiguration();
                candidate.load(configFile);
                if (!candidate.isConfigurationSection("settings")) {
                    throw new IllegalArgumentException("缺少 settings 配置节");
                }
                // Publish only after successful parsing. Never overwrite an invalid user file.
                config = candidate;
                lastModified = configFile.lastModified();
                invalidateCache();
                updateCache();
            } catch (Exception e) {
                if (config == null) throw new IllegalStateException("config.yml 无效；原文件已保留，请修正后重启", e);
                throw new IllegalArgumentException("config.yml 无效；继续使用上次有效配置，原文件已保留", e);
            }
        }
    }
    
    /**
     * 🔧 从损坏的配置文件中尝试提取API密钥
     */
    private String extractApiKeyFromCorruptedConfig() {
        try {
            if (!configFile.exists()) {
                return null;
            }
            
            // 逐行读取文件，寻找API密钥
            List<String> lines = java.nio.file.Files.readAllLines(configFile.toPath(), 
                                                                  java.nio.charset.StandardCharsets.UTF_8);
            
            for (String line : lines) {
                String trimmed = line.trim();
                
                // 查找 api-key 行
                if (trimmed.startsWith("api-key:")) {
                    String value = trimmed.substring(8).trim();
                    
                    // 移除引号
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    } else if (value.startsWith("'") && value.endsWith("'")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    
                    // 验证是否是有效的API密钥
                    if (value.length() > 10 && !value.equals("your-deepseek-api-key")) {
                        plugin.getLogger().info("从损坏配置中提取到API密钥: " + maskApiKeyForLog(value));
                        return value;
                    }
                }
            }
            
            plugin.getLogger().info("未在损坏的配置中找到有效的API密钥");
            return null;
            
        } catch (Exception e) {
            plugin.getLogger().warning("提取API密钥时出错: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 🔧 为日志掩码API密钥
     */
    private String maskApiKeyForLog(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
    
    /**
     * 加载prompts配置
     */
    private void loadPrompts() {
        synchronized(configLock) {
            if (!promptsFile.exists()) {
                // 保存默认的prompts.yml
                plugin.saveResource("prompts.yml", false);
            }
            promptsConfig = YamlConfiguration.loadConfiguration(promptsFile);
            promptsLastModified = promptsFile.lastModified();
            plugin.getLogger().info("提示词配置已加载");
        }
    }
    
    /**
     * 保存配置
     */
    public void saveConfig() {
        synchronized(configLock) {
            try {
                // 🔧 增强的安全保存机制
                saveConfigSafely();
                
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "保存配置文件时发生错误", e);
                
                throw new IllegalStateException("配置保存失败；原文件已保留", e);
            }
        }
    }
    
    /**
     * 🔧 安全的配置保存方法
     */
    private void saveConfigSafely() throws IOException {
        // 创建临时文件
        File tempFile = new File(configFile.getParent(), "config.yml.tmp");
        
        try {
            // 保存到临时文件
            config.save(tempFile);
            
            // 🔧 验证临时文件的YAML语法
            if (!validateYamlSyntax(tempFile)) {
                throw new IOException("生成的配置文件YAML语法无效");
            }
            
            // 🔧 原子性替换
            if (configFile.exists()) {
                // 创建备份
                File backupFile = new File(configFile.getParent(), 
                    "config.yml.backup." + System.currentTimeMillis());
                java.nio.file.Files.copy(configFile.toPath(), backupFile.toPath(), 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            
            // 原子性移动临时文件到目标位置
            java.nio.file.Files.move(tempFile.toPath(), configFile.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, 
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            
            plugin.getLogger().info("配置文件已安全保存");
            
        } catch (Exception e) {
            // 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw new IOException("配置保存失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 🔧 验证YAML语法
     */
    private boolean validateYamlSyntax(File yamlFile) {
        try {
            YamlConfiguration testConfig = YamlConfiguration.loadConfiguration(yamlFile);
            
            // 检查关键配置项是否存在
            if (!testConfig.contains("settings")) {
                plugin.getLogger().warning("配置验证失败：缺少settings节点");
                return false;
            }
            
            // 检查API密钥是否被正确保存
            String apiKey = testConfig.getString("settings.api-key", "");
            if (apiKey.contains("\"") || apiKey.contains("'")) {
                plugin.getLogger().warning("配置验证失败：API密钥包含引号字符");
                return false;
            }
            
            plugin.getLogger().info("配置文件YAML语法验证通过");
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().warning("配置文件YAML语法验证失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 🔧 尝试从备份恢复配置
     */
    private void tryRestoreFromBackup() {
        try {
            File dataFolder = plugin.getDataFolder();
            File[] backupFiles = dataFolder.listFiles((dir, name) -> 
                name.startsWith("config.yml.backup."));
            
            if (backupFiles != null && backupFiles.length > 0) {
                // 找到最新的备份
                java.util.Arrays.sort(backupFiles, 
                    java.util.Comparator.comparingLong(File::lastModified).reversed());
                
                File latestBackup = backupFiles[0];
                
                // 验证备份文件
                if (validateYamlSyntax(latestBackup)) {
                    java.nio.file.Files.copy(latestBackup.toPath(), configFile.toPath(), 
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    
                    plugin.getLogger().info("已从备份恢复配置: " + latestBackup.getName());
                    
                    // 重新加载配置
                    loadConfig();
                } else {
                    plugin.getLogger().severe("备份文件也已损坏，请手动检查配置");
                }
            } else {
                plugin.getLogger().severe("没有可用的配置备份文件");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("从备份恢复配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 设置配置值
     */
    public void set(String path, Object value) {
        synchronized(configLock) {
            config.set(path, value);
        }
    }
    
    /**
     * 获取配置值
     */
    public Object get(String path) {
        synchronized(configLock) {
            return config.get(path);
        }
    }
    
    /**
     * 获取配置值（带默认值）
     */
    public Object get(String path, Object def) {
        synchronized(configLock) {
            return config.get(path, def);
        }
    }
    
    /**
     * 获取布尔值
     */
    public boolean getBoolean(String path) {
        synchronized(configLock) {
            return config.getBoolean(path);
        }
    }
    
    /**
     * 获取布尔值（带默认值）
     */
    public boolean getBoolean(String path, boolean def) {
        synchronized(configLock) {
            return config.getBoolean(path, def);
        }
    }
    
    /**
     * 获取整数值
     */
    public int getInt(String path) {
        synchronized(configLock) {
            return config.getInt(path);
        }
    }
    
    /**
     * 获取整数值（带默认值）
     */
    public int getInt(String path, int def) {
        synchronized(configLock) {
            return config.getInt(path, def);
        }
    }
    
    /**
     * 获取字符串
     */
    public String getString(String path) {
        synchronized(configLock) {
            return config.getString(path);
        }
    }
    
    /**
     * 获取字符串（带默认值）
     */
    public String getString(String path, String def) {
        synchronized(configLock) {
            return config.getString(path, def);
        }
    }
    
    /**
     * 获取双精度浮点数
     */
    public double getDouble(String path) {
        synchronized(configLock) {
            return config.getDouble(path);
        }
    }
    
    /**
     * 获取双精度浮点数（带默认值）
     */
    public double getDouble(String path, double def) {
        synchronized(configLock) {
            return config.getDouble(path, def);
        }
    }
    
    /**
     * 获取长整数值
     */
    public long getLong(String path) {
        synchronized(configLock) {
            return config.getLong(path);
        }
    }
    
    /**
     * 获取长整数值（带默认值）
     */
    public long getLong(String path, long def) {
        synchronized(configLock) {
            return config.getLong(path, def);
        }
    }
    
    /**
     * 获取配置对象
     */
    public FileConfiguration getConfig() {
        synchronized(configLock) {
            return config;
        }
    }
    
    /**
     * 重新加载配置
     */
    public void reloadConfig() {
        synchronized(configLock) {
            loadConfig();
            plugin.reloadConfig();
            
            // 重新加载prompts
            loadPrompts();
            
            invalidateCache(); // 🔧 重载时清除缓存
            updateCache(); // 🔧 立即更新缓存
            notifyListeners();
            
            if (isDevelopmentEnabled() && isConfigValidationEnabled()) {
                validateConfiguration();
            }
        }
    }
    
    // 🔧 优化：常用配置使用缓存
    public boolean isDebugEnabled() {
        if (!isCacheValid()) {
            updateCache();
        }
        return debugEnabledCache;
    }
    
    public boolean isChatEnabled() {
        if (!isCacheValid()) {
            updateCache();
        }
        return chatEnabledCache;
    }
    
    public String getChatPrefix() {
        if (!isCacheValid()) {
            updateCache();
        }
        return chatPrefixCache;
    }
    
    public boolean isBroadcastEnabled() {
        if (!isCacheValid()) {
            updateCache();
        }
        return broadcastEnabledCache;
    }
    
    public String getApiKey() {
        if (!isCacheValid()) {
            updateCache();
        }
        return apiKeyCache;
    }
    
    public String getApiUrl() {
        if (!isCacheValid()) {
            updateCache();
        }
        return apiUrlCache;
    }
    
    public String getModel() {
        if (!isCacheValid()) {
            updateCache();
        }
        return modelCache;
    }
    
    public String getRoleSystem() {
        if (!isCacheValid()) {
            updateCache();
        }
        return roleSystemCache;
    }
    
    public double getTemperature() {
        if (!isCacheValid()) {
            updateCache();
        }
        return temperatureCache;
    }
    
    public int getMaxTokens() {
        if (!isCacheValid()) {
            updateCache();
        }
        return maxTokensCache;
    }
    
    public long getNormalUserCooldown() {
        synchronized(configLock) {
            return config.getLong("performance.rate-limit.normal-user", 3000);
        }
    }
    
    public long getVipUserCooldown() {
        synchronized(configLock) {
            return config.getLong("performance.rate-limit.vip-user", 1000);
        }
    }
    
    public int getMaxMessagesPerMinute() {
        synchronized(configLock) {
            return config.getInt("performance.rate-limit.max-messages-per-minute", 10);
        }
    }
    
    public boolean isFilterEnabled() {
        if (!isCacheValid()) {
            updateCache();
        }
        return filterEnabledCache;
    }
    
    // 消息格式
    public String getMessageFormat(String type) {
        synchronized(configLock) {
            // 首先尝试从messages节点获取
            String message = config.getString("messages." + type, null);
            if (message != null && !message.trim().isEmpty()) {
                return message;
            }
            
            // 如果messages节点没有，尝试从help节点获取
            message = config.getString("help." + type, null);
            if (message != null && !message.trim().isEmpty()) {
                return message;
            }
            
            // 返回默认的帮助消息
            return getDefaultHelpMessage(type);
        }
    }
    
    /**
     * 获取默认帮助消息
     */
    private String getDefaultHelpMessage(String type) {
        switch (type) {
            case "help.page-header":
            case "page-header":
                return "&6=== 帮助页面 {page}/{total} ===";
            case "help.header":
            case "header":
                return "&6=== AI聊天帮助 ===";
            case "help.basic":
            case "basic":
                return "&e/ai <消息> &7- 与AI对话\n&e/aichat help &7- 显示帮助";
            case "help.admin":
            case "admin":
                return "&c/aichat reload &7- 重载配置\n&c/aichat status &7- 查看状态\n&c/aichat debug &7- 切换调试";
            case "help.vip":
            case "vip":
                return "&b/aichat clear &7- 清除对话历史";
            case "help.empty":
            case "empty":
                return "&c暂无帮助信息可显示";
            case "help.page-footer":
            case "page-footer":
                return "&7输入 /aichat help 查看更多...";
            case "no-permission":
                return "&c你没有权限使用此功能";
            case "error":
                return "&c发生错误：{error}";
            case "cooldown":
                return "&e请等待 {time} 秒后再试";
            case "rate-limit":
                return "&c消息发送过于频繁，请等待 {time} 秒后再试";
            case "filtered":
                return "&e您的消息包含不当内容，请重新组织语言";
            default:
                plugin.getLogger().warning("未找到消息格式: " + type);
                return "&7[AI] 消息格式缺失: " + type;
        }
    }
    
    // 环境设置
    public int getEntityDetectionRange() {
        synchronized(configLock) {
            return config.getInt("environment.entity-range", DEFAULT_DETECTION_RANGE);
        }
    }
    
    public int getBlockDetectionRange() {
        synchronized(configLock) {
            return config.getInt("environment.block-scan-range", DEFAULT_DETECTION_RANGE);
        }
    }
    
    public boolean isShowDetailedLocation() {
        synchronized(configLock) {
            return config.getBoolean("environment.show-detailed-location", true);
        }
    }
    
    public boolean isShowWeather() {
        synchronized(configLock) {
            return config.getBoolean("environment.show-weather", true);
        }
    }
    
    public boolean isShowTime() {
        synchronized(configLock) {
            return config.getBoolean("environment.show-time", true);
        }
    }
    
    // 玩家状态设置
    public double getDamageThreshold() {
        synchronized(configLock) {
            return config.getDouble("events.damage.threshold", DEFAULT_DAMAGE_THRESHOLD);
        }
    }
    
    public long getDamageCooldown() {
        synchronized(configLock) {
            return config.getLong("events.damage.cooldown", DEFAULT_DAMAGE_COOLDOWN);
        }
    }
    
    // 对话设置
    public int getConversationMaxHistory() {
        synchronized(configLock) {
            return config.getInt("history.max-history", DEFAULT_MAX_HISTORY);
        }
    }
    
    public int getConversationMaxContext() {
        synchronized(configLock) {
            return config.getInt("history.max-context-length", DEFAULT_MAX_CONTEXT);
        }
    }
    
    public boolean isConversationPersistenceEnabled() {
        synchronized(configLock) {
            return config.getBoolean("history.save-enabled", false);
        }
    }
    
    public int getConversationSaveInterval() {
        synchronized(configLock) {
            return config.getInt("history.save-interval", DEFAULT_SAVE_INTERVAL);
        }
    }
    
    public boolean isConversationSaveOnShutdown() {
        synchronized(configLock) {
            return config.getBoolean("history.save-on-shutdown", true);
        }
    }
    
    // 🔧 角色一致性保护配置
    public boolean isRoleProtectionEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.role-protection-enabled", true);
        }
    }
    
    public int getMaxHistoryInfluence() {
        synchronized(configLock) {
            return config.getInt("advanced.max-history-influence", 2);
        }
    }
    
    public int getAiResponseSummaryLength() {
        synchronized(configLock) {
            return config.getInt("advanced.ai-response-summary-length", 30);
        }
    }
    
    // 玩家档案设置
    public boolean isPlayerProfilePersistenceEnabled() {
        synchronized(configLock) {
            return config.getBoolean("player-profile.enable-persistence", false);
        }
    }
    
    public int getPlayerProfileSaveInterval() {
        synchronized(configLock) {
            return config.getInt("player-profile.save-interval", DEFAULT_SAVE_INTERVAL);
        }
    }
    
    public boolean isPlayerProfileSaveOnShutdown() {
        synchronized(configLock) {
            return config.getBoolean("player-profile.save-on-shutdown", false);
        }
    }
    
    // 配置修改
    public void setApiKey(String apiKey) {
        synchronized (configLock) {
            String value = apiKey == null ? "" : apiKey.trim();
            if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("API key contains control characters");
            config.set("settings.api-key", value);
            saveConfig();
            invalidateCache();
            updateCache();
        }
    }
    
    public void setDebug(boolean debug) {
        synchronized(configLock) {
            config.set("settings.debug", debug);
            saveConfig();
            invalidateCache();
        }
    }
    
    // 环境收集器配置
    public boolean isShowEntities() {
        synchronized(configLock) {
            return config.getBoolean("environment.show-entities", true);
        }
    }
    
    public double getEntityRange() {
        synchronized(configLock) {
            return config.getDouble("environment.entity-range", 10.0);
        }
    }
    
    public int getMaxEntities() {
        synchronized(configLock) {
            return config.getInt("environment.max-entities", 5);
        }
    }
    
    public boolean isShowBlocks() {
        synchronized(configLock) {
            return config.getBoolean("environment.show-blocks", true);
        }
    }
    
    public int getBlockScanRange() {
        synchronized(configLock) {
            return config.getInt("environment.block-scan-range", 10);
        }
    }
    
    public Set<Material> getExcludedBlocks() {
        synchronized(configLock) {
            List<String> excluded = config.getStringList("environment.excluded-blocks");
            Set<Material> materials = new HashSet<>();
            for (String name : excluded) {
                try {
                    materials.add(Material.valueOf(name));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("无效的方块类型: " + name);
                }
            }
            return materials;
        }
    }
    
    public long getCacheTTL() {
        synchronized(configLock) {
            return config.getLong("environment.cache-ttl", 30) * 1000L; // 转换为毫秒
        }
    }
    
    // 🔧 智能环境收集配置
    public long getSmartCollectionInterval() {
        synchronized(configLock) {
            // 配置文件中以分钟为单位，转换为毫秒
            int minutes = config.getInt("environment.smart-collection-interval", 2);
            return minutes * 60 * 1000L; // 转换为毫秒
        }
    }
    
    public double getLocationChangeThreshold() {
        synchronized(configLock) {
            return config.getDouble("environment.location-change-threshold", 20.0);
        }
    }
    
    public long getEnvironmentCacheTTL() {
        synchronized(configLock) {
            // 配置文件中以秒为单位，转换为毫秒
            int seconds = config.getInt("environment.cache-ttl", 30);
            return seconds * 1000L; // 转换为毫秒
        }
    }
    
    // 性能优化配置（统一版本）
    public boolean isAutoOptimizeEnabled() {
        synchronized(configLock) {
            return config.getBoolean("performance.auto-optimize-enabled", DEFAULT_AUTO_OPTIMIZE);
        }
    }
    
    public int getPerformanceCheckInterval() {
        synchronized(configLock) {
            return config.getInt("performance.check-interval", DEFAULT_CHECK_INTERVAL);
        }
    }
    
    // 新版TPS阈值（性能驱动优化）
    public double getTpsThresholdFull() {
        synchronized(configLock) {
            return config.getDouble("performance.tps-threshold-full", 18.0);
        }
    }
    
    public double getTpsThresholdLite() {
        synchronized(configLock) {
            return config.getDouble("performance.tps-threshold-lite", 15.0);
        }
    }
    
    public double getTpsThresholdBasic() {
        synchronized(configLock) {
            return config.getDouble("performance.tps-threshold-basic", 10.0);
        }
    }
    
    // 硬件监控阈值
    public int getMinCpuCores() {
        synchronized(configLock) {
            return config.getInt("advanced.min-cpu-cores", 2);
        }
    }
    
    public double getMinFreeMemory() {
        synchronized(configLock) {
            return config.getDouble("advanced.min-memory-mb", 512.0) / 1024.0; // 转换为GB
        }
    }
    
    public double getMinFreeDisk() {
        synchronized(configLock) {
            return config.getDouble("advanced.min-free-disk", 5.0);
        }
    }
    
    public boolean isDynamicThresholdEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.dynamic-threshold", true);
        }
    }
    
    // 传统阈值（向后兼容）
    public double getCpuThreshold() {
        synchronized(configLock) {
            return config.getDouble("advanced.max-cpu-percent", DEFAULT_CPU_THRESHOLD);
        }
    }
    
    public double getMemoryThreshold() {
        synchronized(configLock) {
            return config.getDouble("advanced.memory-threshold", DEFAULT_MEMORY_THRESHOLD);
        }
    }
    
    public double getTpsThreshold() {
        synchronized(configLock) {
            return config.getDouble("performance.tps-threshold-full", DEFAULT_TPS_THRESHOLD);
        }
    }
    
    public int getEntityCountThreshold() {
        synchronized(configLock) {
            return config.getInt("advanced.entity-threshold", DEFAULT_ENTITY_THRESHOLD);
        }
    }
    
    public int getChunkCountThreshold() {
        synchronized(configLock) {
            return config.getInt("advanced.chunk-threshold", DEFAULT_CHUNK_THRESHOLD);
        }
    }
    
    public boolean isAdjustDetectionRangeEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.adjust-detection-range", true);
        }
    }
    
    public boolean isAdjustCacheTTLEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.adjust-cache-ttl", true);
        }
    }
    
    public boolean isAdjustQueueSizeEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.adjust-queue-size", true);
        }
    }
    
    public boolean isAdjustAsyncTasksEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.adjust-async-tasks", true);
        }
    }
    
    public int getMinDetectionRange() {
        synchronized(configLock) {
            return config.getInt("advanced.min-detection-range", 5);
        }
    }
    
    public int getMaxDetectionRange() {
        synchronized(configLock) {
            return config.getInt("advanced.max-detection-range", 20);
        }
    }
    
    public long getMinCacheTTL() {
        synchronized(configLock) {
            return config.getLong("advanced.min-cache-ttl", 500);
        }
    }
    
    public long getMaxCacheTTL() {
        synchronized(configLock) {
            return config.getLong("advanced.max-cache-ttl", 5000);
        }
    }
    
    public int getMinQueueSize() {
        synchronized(configLock) {
            return config.getInt("advanced.min-queue-size", 10);
        }
    }
    
    public int getMaxQueueSize() {
        synchronized(configLock) {
            return config.getInt("advanced.max-queue-size", 100);
        }
    }
    
    public int getMinAsyncTasks() {
        synchronized(configLock) {
            return config.getInt("advanced.min-async-tasks", 2);
        }
    }
    
    public int getMaxAsyncTasks() {
        synchronized(configLock) {
            return config.getInt("advanced.max-async-tasks", 8);
        }
    }
    
    // 性能优化配置修改方法
    public void setAutoOptimize(boolean enabled) {
        synchronized(configLock) {
            config.set("performance.auto-optimize-enabled", enabled);
            saveConfig();
        }
    }
    
    public void setPerformanceCheckInterval(int minutes) {
        synchronized(configLock) {
            config.set("performance.check-interval", minutes);
            saveConfig();
        }
    }
    
    public void setThresholds(double cpuUsage, double memoryUsage, double tps, 
                            int entityCount, int chunkCount) {
        synchronized(configLock) {
            config.set("advanced.max-cpu-percent", cpuUsage);
            config.set("advanced.memory-threshold", memoryUsage);
            config.set("performance.tps-threshold-full", tps);
            config.set("advanced.entity-threshold", entityCount);
            config.set("advanced.chunk-threshold", chunkCount);
            saveConfig();
        }
    }
    
    public void setOptimizationStrategies(boolean adjustDetectionRange, boolean adjustCacheTTL,
                                        boolean adjustQueueSize, boolean adjustAsyncTasks) {
        synchronized(configLock) {
            config.set("advanced.adjust-detection-range", adjustDetectionRange);
            config.set("advanced.adjust-cache-ttl", adjustCacheTTL);
            config.set("advanced.adjust-queue-size", adjustQueueSize);
            config.set("advanced.adjust-async-tasks", adjustAsyncTasks);
            saveConfig();
        }
    }
    
    public void setOptimizationLimits(int minDetectionRange, int maxDetectionRange,
                                    long minCacheTTL, long maxCacheTTL,
                                    int minQueueSize, int maxQueueSize,
                                    int minAsyncTasks, int maxAsyncTasks) {
        synchronized(configLock) {
            config.set("advanced.min-detection-range", minDetectionRange);
            config.set("advanced.max-detection-range", maxDetectionRange);
            config.set("advanced.min-cache-ttl", minCacheTTL);
            config.set("advanced.max-cache-ttl", maxCacheTTL);
            config.set("advanced.min-queue-size", minQueueSize);
            config.set("advanced.max-queue-size", maxQueueSize);
            config.set("advanced.min-async-tasks", minAsyncTasks);
            config.set("advanced.max-async-tasks", maxAsyncTasks);
            saveConfig();
        }
    }
    
    /**
     * 热重载支持
     */
    private void startHotReload() {
        if (scheduler != null) {
            stopHotReload();
        }
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ConfigHotReload");
            thread.setDaemon(true);
            return thread;
        });
        
        long checkInterval = isDevelopmentEnabled() ? 5 : 30; // 开发环境5秒，生产环境30秒
        
        scheduler.scheduleAtFixedRate(() -> {
            if (isShuttingDown) {
                return;
            }
            
            try {
                boolean configChanged = false;
                boolean promptsChanged = false;
                
                // 检查config.yml是否修改
                if (configFile.lastModified() > lastModified) {
                    loadConfig();
                    lastModified = configFile.lastModified();
                    configChanged = true;
                }
                
                // 检查prompts.yml是否修改
                if (promptsFile.exists() && promptsFile.lastModified() > promptsLastModified) {
                    loadPrompts();
                    promptsLastModified = promptsFile.lastModified();
                    promptsChanged = true;
                }
                
                // 如果有任何配置变化，通知监听器
                if (configChanged || promptsChanged) {
                    notifyListeners();
                    if (promptsChanged) {
                        plugin.getLogger().info("提示词配置已热重载");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "热重载配置时发生错误", e);
            }
        }, 0, checkInterval, TimeUnit.SECONDS);
    }
    
    /**
     * 停止热重载
     */
    public void stopHotReload() {
        isShuttingDown = true;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }
    
    /**
     * 添加配置变更监听器
     */
    public void addConfigChangeListener(ConfigChangeListener listener) {
        synchronized(listeners) {
            listeners.add(listener);
        }
    }
    
    /**
     * 移除配置变更监听器
     */
    public void removeConfigChangeListener(ConfigChangeListener listener) {
        synchronized(listeners) {
            listeners.remove(listener);
        }
    }
    
    /**
     * 通知所有监听器配置已重载
     */
    private void notifyListeners() {
        synchronized(listeners) {
            for (ConfigChangeListener listener : listeners) {
                try {
                    listener.onConfigReload();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "通知配置变更监听器时发生错误", e);
                }
            }
        }
    }
    
    // 配置验证
    private void validateConfiguration() {
        // 验证API密钥
        if (getApiKey().isEmpty() && !isAllowNoApiKey()) {
            plugin.getLogger().warning("API密钥未设置！");
        }
        
        // 验证超时设置
        if (getConnectTimeout() < 1 || getConnectTimeout() > 30) {
            plugin.getLogger().warning("连接超时时间超出建议范围(1-30秒)");
        }
        
        // 验证检测范围
        if (getEntityDetectionRange() < 5 || getEntityDetectionRange() > 30) {
            plugin.getLogger().warning("实体检测范围超出建议范围(5-30格)");
        }
        if (getBlockDetectionRange() < 5 || getBlockDetectionRange() > 30) {
            plugin.getLogger().warning("方块检测范围超出建议范围(5-30格)");
        }
        
        // 验证性能阈值
        if (getCpuThreshold() < 50 || getCpuThreshold() > 95) {
            plugin.getLogger().warning("CPU使用率阈值超出建议范围(50-95%)");
        }
        
        // 验证测试设置
        if (isStressTestEnabled() && getStressTestConcurrency() > 100) {
            plugin.getLogger().warning("压力测试并发数超出建议范围(1-100)");
        }
    }
    
    // 测试配置访问方法
    public boolean isTestingEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.testing-enabled", DEFAULT_TESTING_ENABLED);
        }
    }
    
    public boolean isAllowNoApiKey() {
        synchronized(configLock) {
            return config.getBoolean("advanced.allow-no-api-key", DEFAULT_ALLOW_NO_API_KEY);
        }
    }
    
    public boolean isStressTestEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.stress-test", DEFAULT_STRESS_TEST);
        }
    }
    
    public int getStressTestConcurrency() {
        synchronized(configLock) {
            return config.getInt("advanced.stress-test-concurrency", DEFAULT_STRESS_TEST_CONCURRENCY);
        }
    }
    
    public int getStressTestDuration() {
        synchronized(configLock) {
            return config.getInt("advanced.stress-test-duration", DEFAULT_STRESS_TEST_DURATION);
        }
    }
    
    public boolean isErrorInjectionEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.error-injection", DEFAULT_ERROR_INJECTION);
        }
    }
    
    public int getErrorInjectionRate() {
        synchronized(configLock) {
            return config.getInt("advanced.error-injection-rate", DEFAULT_ERROR_INJECTION_RATE);
        }
    }
    
    public boolean isNetworkLatencySimulationEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.network-latency-simulation", DEFAULT_NETWORK_LATENCY);
        }
    }
    
    public int getMinLatency() {
        synchronized(configLock) {
            return config.getInt("advanced.min-latency", DEFAULT_MIN_LATENCY);
        }
    }
    
    public int getMaxLatency() {
        synchronized(configLock) {
            return config.getInt("advanced.max-latency", DEFAULT_MAX_LATENCY);
        }
    }
    
    public boolean isPersistenceTestEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.persistence-test", DEFAULT_PERSISTENCE_TEST);
        }
    }
    
    public int getPersistenceTestInterval() {
        synchronized(configLock) {
            return config.getInt("advanced.persistence-test-interval", DEFAULT_PERSISTENCE_TEST_INTERVAL);
        }
    }
    
    // 开发配置访问方法
    public boolean isDevelopmentEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.development-enabled", DEFAULT_DEV_ENABLED);
        }
    }
    
    public boolean isHotReloadEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.hot-reload", DEFAULT_HOT_RELOAD);
        }
    }
    
    public int getHotReloadInterval() {
        synchronized(configLock) {
            return config.getInt("advanced.hot-reload-interval", DEFAULT_HOT_RELOAD_INTERVAL);
        }
    }
    
    public boolean isConfigValidationEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.validate-config", DEFAULT_VALIDATE_CONFIG);
        }
    }
    
    public boolean isApiResponseCachingEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.cache-api-responses", DEFAULT_CACHE_API_RESPONSES);
        }
    }
    
    public int getApiCacheTtl() {
        synchronized(configLock) {
            return config.getInt("advanced.api-cache-ttl", DEFAULT_API_CACHE_TTL);
        }
    }
    
    public boolean isDetailedErrorStackEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.detailed-error-stack", DEFAULT_DETAILED_ERROR_STACK);
        }
    }
    
    public boolean isPerformanceMonitoringEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.performance-monitoring", DEFAULT_PERFORMANCE_MONITORING);
        }
    }
    
    public int getMonitoringInterval() {
        synchronized(configLock) {
            return config.getInt("advanced.monitoring-interval", DEFAULT_MONITORING_INTERVAL);
        }
    }
    
    /**
     * 检查玩家加入事件响应是否启用
     */
    public boolean isJoinEnabled() {
        synchronized(configLock) {
            return config.getBoolean("events.join.enabled", true);
        }
    }
    
    /**
     * 检查玩家退出事件响应是否启用
     */
    public boolean isQuitEnabled() {
        synchronized(configLock) {
            return config.getBoolean("events.quit.enabled", true);
        }
    }
    
    /**
     * 获取玩家加入事件冷却时间
     */
    public long getJoinCooldown() {
        synchronized(configLock) {
            return config.getLong("events.join.cooldown", 30000L);
        }
    }

    /**
     * 获取玩家退出事件冷却时间
     */
    public long getQuitCooldown() {
        synchronized(configLock) {
            return config.getLong("events.quit.cooldown", 30000L);
        }
    }
    
    /**
     * 检查玩家重生事件响应是否启用
     */
    public boolean isRespawnEnabled() {
        synchronized(configLock) {
            return config.getBoolean("events.respawn.enabled", true);
        }
    }
    
    /**
     * 检查玩家升级事件响应是否启用
     */
    public boolean isLevelUpEnabled() {
        synchronized(configLock) {
            return config.getBoolean("events.level-up.enabled", true);
        }
    }
    
    /**
     * 检查玩家受伤事件响应是否启用
     */
    public boolean isDamageEnabled() {
        synchronized(configLock) {
            return config.getBoolean("events.damage.enabled", true);
        }
    }
    
    /**
     * 检查玩家死亡事件响应是否启用
     */
    public boolean isDeathEnabled() {
        synchronized(configLock) {
            return config.getBoolean("events.death.enabled", true);
        }
    }
    
    /**
     * 检查成就事件响应是否启用
     */
    public boolean isAdvancementEnabled() {
        synchronized(configLock) {
            return config.getBoolean("events.advancement.enabled", true);
        }
    }
    
    /**
     * 检查药水效果变更响应是否启用
     */
    public boolean isPotionEffectEnabled() {
        synchronized(configLock) {
            return config.getBoolean("events.potion-effect.enabled", true);
        }
    }

    /**
     * 获取全局事件冷却时间
     */
    public long getGlobalCooldown() {
        synchronized(configLock) {
            return config.getLong("events.global-cooldown", 1000L);
        }
    }

    /**
     * 获取药水效果事件冷却时间
     * @return 冷却时间(毫秒)
     */
    public long getPotionCooldown() {
        synchronized(configLock) {
            return config.getLong("events.potion-cooldown", 200L);
        }
    }
    
    // 帮助系统配置
    /**
     * 获取帮助页面大小
     * @return 每页显示行数
     */
    public int getHelpPageSize() {
        synchronized(configLock) {
            return config.getInt("help.page-size", 8);
        }
    }
    
    /**
     * 获取帮助消息格式
     * @param type 消息类型
     * @return 格式化字符串
     */
    public String getHelpMessageFormat(String type) {
        synchronized(configLock) {
            return config.getString("help." + type, "");
        }
    }
    
    /**
     * 检查缓存是否有效
     */
    private boolean isCacheValid() {
        CacheState current = cacheState.get();
        return current.valid && !current.isExpired(CACHE_TTL);
    }
    
    /**
     * 更新缓存
     */
    private void updateCache() {
        synchronized(configLock) {
            // 更新常用配置的缓存
            debugEnabledCache = config.getBoolean("settings.debug", DEFAULT_DEBUG_ENABLED);
            chatEnabledCache = config.getBoolean("settings.chat-enabled", true);
            chatPrefixCache = config.getString("settings.chat-prefix", DEFAULT_CHAT_PREFIX);
            broadcastEnabledCache = config.getBoolean("settings.broadcast-enabled", false);
            filterEnabledCache = config.getBoolean("advanced.filter-enabled", false);
            maxMessagesPerMinuteCache = config.getInt("performance.rate-limit.max-messages-per-minute", 10);
            normalUserCooldownCache = config.getLong("performance.rate-limit.normal-user", 3000);
            vipUserCooldownCache = config.getLong("performance.rate-limit.vip-user", 1000);
            apiKeyCache = config.getString("settings.api-key", "");
            apiUrlCache = config.getString("settings.api-base-url", DEFAULT_API_URL);
            modelCache = config.getString("settings.model", DEFAULT_MODEL);
            roleSystemCache = config.getString("ai.role-system", "你是一个有帮助的AI助手。");
            temperatureCache = config.getDouble("ai.temperature", DEFAULT_TEMPERATURE);
            maxTokensCache = config.getInt("ai.max-tokens", DEFAULT_MAX_TOKENS);
            
            // 创建新的缓存状态
            long now = System.currentTimeMillis();
            CacheState newState = new CacheState(true, now, apiKeyCache, chatPrefixCache, 
                                               chatEnabledCache, debugEnabledCache);
            cacheState.set(newState);
        }
    }
    
    /**
     * 使缓存失效
     */
    private void invalidateCache() {
        CacheState current = cacheState.get();
        CacheState invalidState = new CacheState(false, current.updateTime, 
                                                current.apiKeyCache, current.chatPrefixCache,
                                                current.chatEnabledCache, current.debugEnabledCache);
        cacheState.set(invalidState);
    }
    
    // 配置修改方法需要使缓存失效
    public void setChatEnabled(boolean enabled) {
        synchronized(configLock) {
            config.set("settings.chat-enabled", enabled);
            invalidateCache();
        }
    }
    
    public void setChatPrefix(String prefix) {
        synchronized(configLock) {
            config.set("settings.chat-prefix", prefix);
            invalidateCache();
        }
    }
    
    public void setBroadcastEnabled(boolean enabled) {
        synchronized(configLock) {
            config.set("settings.broadcast-enabled", enabled);
            invalidateCache();
        }
    }
    
    public void setFilterEnabled(boolean enabled) {
        synchronized(configLock) {
            config.set("advanced.filter-enabled", enabled);
            invalidateCache();
        }
    }
    
    public void setMaxMessagesPerMinute(int max) {
        synchronized(configLock) {
            config.set("performance.rate-limit.max-messages-per-minute", max);
            invalidateCache();
        }
    }
    
    public void setNormalUserCooldown(long cooldown) {
        synchronized(configLock) {
            config.set("performance.rate-limit.normal-user", cooldown);
            invalidateCache();
        }
    }
    
    public void setVipUserCooldown(long cooldown) {
        synchronized(configLock) {
            config.set("performance.rate-limit.vip-user", cooldown);
            invalidateCache();
        }
    }

    /**
     * 设置调试模式
     */
    public void setDebugEnabled(boolean enabled) {
        synchronized(configLock) {
            config.set("settings.debug", enabled);
            invalidateCache();
        }
    }
    
    /**
     * 检查是否只处理重要伤害事件
     * @return 是否只处理重要伤害
     */
    public boolean isDamageOnlyImportant() {
        synchronized(configLock) {
            return config.getBoolean("events.damage.only-important", false);
        }
    }
    
    /**
     * 获取伤害事件最大频率（每秒）
     * @return 每秒最大事件数
     */
    public int getDamageMaxEventsPerSecond() {
        synchronized(configLock) {
            return config.getInt("events.damage.max-events-per-second", 10);
        }
    }
    
    /**
     * 检查是否启用伤害事件性能优化
     * @return 是否启用性能优化
     */
    public boolean isDamagePerformanceOptimizationEnabled() {
        synchronized(configLock) {
            return config.getBoolean("events.damage.performance-optimization", true);
        }
    }
    
    // 🔧 保留：较少访问的配置仍使用同步方式
    public int getConnectTimeout() {
        synchronized(configLock) {
            return config.getInt("advanced.connection-timeout", DEFAULT_TIMEOUT);
        }
    }
    
    public int getReadTimeout() {
        synchronized(configLock) {
            return config.getInt("advanced.read-timeout", DEFAULT_TIMEOUT);
        }
    }
    
    public int getWriteTimeout() {
        synchronized(configLock) {
            return config.getInt("advanced.write-timeout", DEFAULT_TIMEOUT);
        }
    }
    
    public String getBroadcastFormat() {
        synchronized(configLock) {
            return config.getString("messages.ai-response-format", 
                "&7[AI对话] &f{player}: {message}");
        }
    }
    
    public boolean isVerboseLoggingEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.verbose-logging", DEFAULT_VERBOSE_LOGGING);
        }
    }
    
    public boolean isShowPerformanceEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.show-performance", DEFAULT_SHOW_PERFORMANCE);
        }
    }
    
    public boolean isMockApiEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.mock-api", DEFAULT_MOCK_API);
        }
    }
    
    public int getMockDelay() {
        synchronized(configLock) {
            return config.getInt("advanced.mock-delay", DEFAULT_MOCK_DELAY);
        }
    }
    
    public boolean isEnvironmentDataLoggingEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.log-environment", DEFAULT_LOG_ENVIRONMENT);
        }
    }
    
    public boolean isPlayerStatusLoggingEnabled() {
        synchronized(configLock) {
            return config.getBoolean("advanced.log-player-status", DEFAULT_LOG_PLAYER_STATUS);
        }
    }
    
    public String getFilterWords() {
        synchronized(configLock) {
            return config.getString("advanced.filter-words", "");
        }
    }
    
    // ==================== Web配置方法 ====================
    
    public boolean isWebEnabled() {
        synchronized(configLock) {
            return config.getBoolean("web.enabled", true);
        }
    }
    
    public int getWebPort() {
        synchronized(configLock) {
            return config.getInt("web.port", 28080);
        }
    }
    
    public String getWebHost() {
        synchronized(configLock) {
            return config.getString("web.host", "localhost");
        }
    }
    
    public boolean isWebAuthEnabled() {
        synchronized(configLock) {
            return config.getBoolean("web.require-auth", true);
        }
    }
    
    public String getWebAuthMethod() {
        synchronized(configLock) {
            return config.getString("web.auth-method", "token");
        }
    }
    
    public int getWebSessionTimeout() {
        synchronized(configLock) {
            return config.getInt("web.session-timeout", 3600);
        }
    }
    
    public boolean isWebCorsEnabled() {
        synchronized(configLock) {
            return config.getBoolean("web.cors-enabled", true);
        }
    }
    
    public int getWebRateLimit() {
        synchronized(configLock) {
            return config.getInt("web.rate-limit", 100);
        }
    }
    
    public boolean isWebConfigManagement() {
        synchronized(configLock) {
            return config.getBoolean("web.config-management", true);
        }
    }
    
    public boolean isWebPerformanceMonitoring() {
        synchronized(configLock) {
            return config.getBoolean("web.performance-monitoring", true);
        }
    }
    
    public boolean isWebBenchmarkTesting() {
        synchronized(configLock) {
            return config.getBoolean("web.benchmark-testing", true);
        }
    }
    
    public boolean isWebRealTimeUpdates() {
        synchronized(configLock) {
            return config.getBoolean("web.real-time-updates", true);
        }
    }
    
    // 添加获取prompts配置的方法
    
    /**
     * 获取系统角色提示词
     */
    public String getSystemRole() {
        synchronized(configLock) {
            return promptsConfig.getString("system.base-role", getRoleSystem());
        }
    }
    
    /**
     * 获取角色保护提示词
     */
    public String getRoleProtectionPrompt() {
        synchronized(configLock) {
            return promptsConfig.getString("system.role-protection", "");
        }
    }
    
    /**
     * 获取环境决策提示词
     */
    public String getEnvironmentDecisionPrompt() {
        synchronized(configLock) {
            return promptsConfig.getString("decision.need-environment", "");
        }
    }
    
    /**
     * 获取事件提示词
     */
    public String getEventPrompt(String eventType) {
        synchronized(configLock) {
            return promptsConfig.getString("events." + eventType, "");
        }
    }
    
    /**
     * 获取对话增强提示词
     */
    public String getConversationPrompt(String type) {
        synchronized(configLock) {
            return promptsConfig.getString("conversation." + type, "");
        }
    }
    
    /**
     * 获取场景提示词
     */
    public String getScenarioPrompt(String scenario) {
        synchronized(configLock) {
            return promptsConfig.getString("scenarios." + scenario, "");
        }
    }
    
    /**
     * 获取错误处理提示词
     */
    public String getErrorPrompt(String errorType) {
        synchronized(configLock) {
            return promptsConfig.getString("errors." + errorType, "抱歉，我暂时无法回应。");
        }
    }
    
    /**
     * 获取提示词版本
     */
    public int getPromptsVersion() {
        synchronized(configLock) {
            return promptsConfig.getInt("version", 1);
        }
    }
}

/**
 * 配置变更监听器接口
 */
interface ConfigChangeListener {
    /**
     * 当配置重载时调用
     */
    void onConfigReload();
}
