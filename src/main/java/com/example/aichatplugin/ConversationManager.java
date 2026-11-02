package com.example.aichatplugin;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import com.example.aichatplugin.util.HistoryCompressor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.nio.file.Path;
import org.bukkit.Location;
import com.example.aichatplugin.util.PromptBuilder;

/**
 * 对话管理器
 * 
 * 职责：
 * 1. 管理玩家对话历史
 * 2. 处理AI响应生成
 * 3. 控制消息广播
 * 4. 维护对话状态
 * 
 * 主要功能：
 * 1. 存储和更新对话历史
 * 2. 生成AI响应
 * 3. 格式化消息显示
 * 4. 管理对话超时
 * 
 * 配置项：
 * 1. 最大历史记录数
 * 2. 最大上下文长度
 * 3. 消息格式
 * 4. 超时设置
 */
public class ConversationManager {
    private final AIChatPlugin plugin;
    private final ConfigLoader config;
    private final DeepSeekAIService aiService;
    private final EnvironmentCollector environmentCollector;
    private final PlayerProfileManager profileManager;
    private final ExecutorService executor;
    
    // 使用写时复制集合
    private final Set<UUID> dirtyPlayers = Collections.newSetFromMap(
        new ConcurrentHashMap<UUID, Boolean>()
    );
    
    // 使用复合版本标识
    private final Map<UUID, String> historyVersions = new ConcurrentHashMap<>();
    
    // 消息缓存
    private ConcurrentHashMap<UUID, JsonArray> messageCache = new ConcurrentHashMap<>();
    private ConcurrentHashMap<UUID, Boolean> historyChanged = new ConcurrentHashMap<>();
    
    // 环境缓存
    private final Map<UUID, CachedEnvironment> envCache = new ConcurrentHashMap<>();
    
    // 对话历史记录
    private final Map<UUID, List<com.example.aichatplugin.Message>> conversationHistory = new ConcurrentHashMap<>();
    
    // 配置常量（只保留一组）
    private static final int BATCH_SAVE_SIZE = 50;
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY = 5000L; // 5秒
    private static final long ENV_CACHE_TTL = 30000; // 30秒
    private static final long SAVE_INTERVAL = 600L; // 30秒 (600 ticks = 30秒)
    private static final long CACHE_JITTER = 5000; // 5秒随机范围
    
    // 备用响应
    private String fallbackResponse = "抱歉，我现在无法回应。请稍后再试。";
    
    private static final long MESSAGE_TIMEOUT = 7200000L; // 2小时(毫秒)，而不是2秒
    private static final long RESPONSE_COOLDOWN = 3000; // 3秒冷却
    private static final int MAX_RETRIES = 3;
    private static final int MAX_DECISION_TOKENS = 2000;
    private static final long ENV_TIMEOUT = 1000; // 1秒超时
    private static final int BASE_MAX_TASKS = 10; // 基础最大任务数
    private static final int MAX_TASKS_PER_CORE = 2; // 每核心最大任务数
    
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final ConcurrentMap<UUID, BlockingQueue<Runnable>> playerQueues = new ConcurrentHashMap<>();
    
    private final Map<UUID, Long> lastResponseTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastResponse = new ConcurrentHashMap<>();
    private final Queue<ResponseTask> responseQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService responseScheduler = Executors.newSingleThreadScheduledExecutor();
    
    @SuppressWarnings("unused")
    private static final int MAX_PENDING_TASKS = 10;
    
    // 消息处理管道
    private final ExecutorService[] processingStages;
    private static final int STAGE_RECEIVE = 0;
    private static final int STAGE_PROCESS = 1;
    private static final int STAGE_OUTPUT = 2;
    
    // 环境缓存类
    private static class CachedEnvironment {
        final String environment;
        final long timestamp;
        final long effectiveTTL;
        
        CachedEnvironment(String environment) {
            this.environment = environment;
            this.timestamp = System.currentTimeMillis();
            // 添加随机过期偏移量，防止缓存雪崩
            this.effectiveTTL = ENV_CACHE_TTL + 
                ThreadLocalRandom.current().nextLong(-CACHE_JITTER, CACHE_JITTER);
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > effectiveTTL;
        }
    }
    
    public ConversationManager(AIChatPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigLoader();
        this.aiService = plugin.getAIService();
        this.environmentCollector = plugin.getEnvironmentCollector();
        this.profileManager = plugin.getProfileManager();
        
        // 初始化处理阶段
        this.processingStages = new ExecutorService[3];
        for (int i = 0; i < 3; i++) {
            final int stageIndex = i;
            this.processingStages[i] = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("ConversationManager-Stage" + stageIndex);
                    return t;
                }
            );
        }
        
        // 初始化executor
        this.executor = Executors.newSingleThreadExecutor();
        
        // 加载历史记录
        loadHistory();
        
        // 启动保存任务
        startSaveTask();
        
        // 启动清理任务
        startCleanupTasks();
        
        // 启动响应队列处理
        startResponseQueue();
    }
    
    /**
     * 启动清理任务
     */
    private void startCleanupTasks() {
        // 清理过期对话和环境缓存
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            cleanup();
            cleanupOldConversations();
            cleanupEnvCache();
        }, 20L * 60, 20L * 60); // 每分钟执行一次
    }
    
    /**
     * 启动响应队列处理
     */
    private void startResponseQueue() {
        // 每50ms处理一次响应队列
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            processResponseQueue();
        }, 1L, 1L);
        
        plugin.debug("响应队列处理器已启动");
    }
    
    /**
     * 加载历史记录
     */
    private void loadHistory() {
        File dataFolder = new File(plugin.getDataFolder(), "history");
        if (!dataFolder.exists()) {
            return;
        }
        
        File[] historyFiles = dataFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (historyFiles == null) {
            return;
        }
        
        for (File file : historyFiles) {
            try {
                String fileName = file.getName();
                UUID playerId = UUID.fromString(fileName.substring(0, fileName.length() - 5));
                
                // 读取压缩的历史记录
                byte[] compressed = Files.readAllBytes(file.toPath());
                List<com.example.aichatplugin.Message> history = HistoryCompressor.decompress(compressed);
                
                // 初始化历史记录
                conversationHistory.put(playerId, history);
                historyVersions.put(playerId, generateVersionId(playerId));
                
                plugin.getLogger().info("已加载玩家 " + playerId + " 的历史记录，消息数: " + history.size());
            } catch (Exception e) {
                plugin.getLogger().warning("加载历史记录失败: " + file.getName());
            }
        }
    }
    
    /**
     * 启动定期保存任务
     */
    private void startSaveTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            incrementalSave();
        }, SAVE_INTERVAL, SAVE_INTERVAL);
    }
    
    /**
     * 增量保存历史记录
     */
    private void incrementalSave() {
        if (dirtyPlayers.isEmpty()) return;
        
        // 创建当前脏数据集的快照
        Set<UUID> snapshot = new HashSet<>(dirtyPlayers);
        
        // 分批处理
        List<UUID> batch = new ArrayList<>(BATCH_SAVE_SIZE);
        for (UUID playerId : snapshot) {
            batch.add(playerId);
            if (batch.size() >= BATCH_SAVE_SIZE) {
                saveBatch(new ArrayList<>(batch));
                batch.clear();
            }
        }
        
        // 处理剩余数据
        if (!batch.isEmpty()) {
            saveBatch(batch);
        }
    }
    
    /**
     * 批量保存历史记录
     */
    private void saveBatch(List<UUID> batch) {
        for (UUID playerId : batch) {
            executor.submit(() -> savePlayerHistoryWithRetry(playerId, 0));
        }
    }
    
    /**
     * 带重试机制的历史记录保存
     */
    private void savePlayerHistoryWithRetry(UUID playerId, int retryCount) {
        try {
            savePlayerHistory(playerId);
            if (retryCount > 0) {
                plugin.getLogger().info(String.format(
                    "历史记录保存成功 (玩家: %s, 重试次数: %d)",
                    playerId, retryCount
                ));
            }
        } catch (Exception e) {
            if (retryCount < MAX_RETRY_COUNT) {
                plugin.getLogger().warning(String.format(
                    "保存历史记录失败，将在5秒后重试 (玩家: %s, 重试次数: %d, 错误: %s)",
                    playerId, retryCount + 1, e.getMessage()
                ));
                
                plugin.getServer().getScheduler().runTaskLater(plugin, 
                    () -> savePlayerHistoryWithRetry(playerId, retryCount + 1), 
                    RETRY_DELAY / 50 // 转换为ticks (1秒 = 20 ticks)
                );
            } else {
                plugin.getLogger().severe(String.format(
                    "历史记录保存最终失败 (玩家: %s, 错误: %s)",
                    playerId, e.getMessage()
                ));
                // 记录完整的堆栈跟踪以便调试
                plugin.getLogger().log(Level.SEVERE, "详细错误信息:", e);
            }
        }
    }
    
    /**
     * 保存玩家历史记录
     */
    private synchronized void savePlayerHistory(UUID playerId) throws Exception {
        // 获取历史记录，如果不存在则跳过
        List<com.example.aichatplugin.Message> historyList = conversationHistory.get(playerId);
        if (historyList == null || historyList.isEmpty()) {
            // 没有历史记录需要保存
            dirtyPlayers.remove(playerId);
            return;
        }
        
        // 获取当前历史快照
        List<com.example.aichatplugin.Message> snapshot;
        synchronized (historyList) {
            snapshot = new ArrayList<>(historyList);
        }
        
        // 获取当前文件
        File playerFile = getPlayerHistoryFile(playerId);
        File tempFile = new File(playerFile.getParent(), playerFile.getName() + ".tmp");
        
        try {
            byte[] compressed;
            if (playerFile.exists()) {
                // 读取现有压缩数据
                byte[] existingData = Files.readAllBytes(playerFile.toPath());
                // 使用增量压缩
                compressed = HistoryCompressor.compressDelta(snapshot, existingData);
            } else {
                // 首次压缩
                compressed = HistoryCompressor.compress(snapshot);
            }
            
            // 写入临时文件
            Files.write(tempFile.toPath(), compressed);
            
            // 使用更健壮的原子重命名操作
            safeReplaceFile(tempFile, playerFile);
            
            // 更新版本号
            updateHistoryVersion(playerId);
            
            // 保存成功后移除脏标记
            dirtyPlayers.remove(playerId);
            
            // 清理过期备份
            cleanupOldBackups(playerId);
            
        } catch (Exception e) {
            // 清理临时文件
            if (tempFile.exists()) {
                try {
                    tempFile.delete();
                } catch (Exception deleteEx) {
                    plugin.getLogger().warning("清理临时文件失败: " + deleteEx.getMessage());
                }
            }
            throw e;
        }
    }
    
    /**
     * 安全地替换文件，支持Windows和跨平台操作
     * 
     * @param tempFile 临时文件
     * @param targetFile 目标文件
     * @throws IOException 如果替换失败
     */
    private void safeReplaceFile(File tempFile, File targetFile) throws IOException {
        Path tempPath = tempFile.toPath();
        Path targetPath = targetFile.toPath();
        
        try {
            // 方案1：使用Files.move()进行原子替换
            if (targetFile.exists()) {
                // 在Windows下，需要先备份原文件
                File backupFile = new File(targetFile.getParent(), targetFile.getName() + ".backup");
                try {
                    Files.move(targetPath, backupFile.toPath(), 
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tempPath, targetPath, 
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    // 删除备份文件
                    backupFile.delete();
                } catch (Exception e) {
                    // 如果失败，尝试恢复备份
                    if (backupFile.exists() && !targetFile.exists()) {
                        try {
                            Files.move(backupFile.toPath(), targetPath);
                        } catch (Exception restoreEx) {
                            plugin.getLogger().severe("文件恢复失败: " + restoreEx.getMessage());
                        }
                    }
                    throw e;
                }
            } else {
                // 目标文件不存在，直接移动
                Files.move(tempPath, targetPath, 
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 方案2：如果不支持原子移动，使用复制+删除
            plugin.getLogger().warning("文件系统不支持原子移动，使用备用方案");
            safeCopyAndReplace(tempFile, targetFile);
        } catch (Exception e) {
            // 方案3：最后的备用方案，使用传统的重命名
            plugin.getLogger().warning("Files.move()失败，尝试传统重命名: " + e.getMessage());
            safeTraditionalReplace(tempFile, targetFile);
        }
    }
    
    /**
     * 安全的复制替换方案（备用方案2）
     */
    private void safeCopyAndReplace(File tempFile, File targetFile) throws IOException {
        File backupFile = null;
        try {
            // 如果目标文件存在，先备份
            if (targetFile.exists()) {
                backupFile = new File(targetFile.getParent(), targetFile.getName() + ".backup");
                Files.copy(targetFile.toPath(), backupFile.toPath(), 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            
            // 复制临时文件到目标位置
            Files.copy(tempFile.toPath(), targetFile.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            // 删除临时文件
            tempFile.delete();
            
            // 删除备份文件
            if (backupFile != null) {
                backupFile.delete();
            }
        } catch (Exception e) {
            // 如果失败，尝试恢复备份
            if (backupFile != null && backupFile.exists()) {
                try {
                    Files.copy(backupFile.toPath(), targetFile.toPath(), 
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    backupFile.delete();
                } catch (Exception restoreEx) {
                    plugin.getLogger().severe("文件恢复失败: " + restoreEx.getMessage());
                }
            }
            throw new IOException("复制替换文件失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 传统的安全替换方案（最后备用方案）
     */
    private void safeTraditionalReplace(File tempFile, File targetFile) throws IOException {
        File backupFile = null;
        boolean success = false;
        
        try {
            // 如果目标文件存在，先重命名为备份
            if (targetFile.exists()) {
                backupFile = new File(targetFile.getParent(), targetFile.getName() + ".backup");
                // 确保备份文件不存在
                if (backupFile.exists()) {
                    backupFile.delete();
                }
                
                // 重命名目标文件为备份
                if (!targetFile.renameTo(backupFile)) {
                    throw new IOException("无法创建备份文件");
                }
            }
            
            // 重命名临时文件为目标文件
            success = tempFile.renameTo(targetFile);
            if (!success) {
                throw new IOException("无法重命名临时文件到目标位置");
            }
            
            // 删除备份文件
            if (backupFile != null) {
                backupFile.delete();
            }
            
        } catch (Exception e) {
            // 如果失败，尝试恢复备份
            if (backupFile != null && backupFile.exists()) {
                try {
                    if (!success && !targetFile.exists()) {
                        backupFile.renameTo(targetFile);
                    }
                } catch (Exception restoreEx) {
                    plugin.getLogger().severe("文件恢复失败: " + restoreEx.getMessage());
                }
            }
            throw new IOException("传统文件替换失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取玩家历史记录文件
     */
    private File getPlayerHistoryFile(UUID playerId) {
        File dataFolder = new File(plugin.getDataFolder(), "history");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        return new File(dataFolder, playerId.toString() + ".json");
    }
    
    /**
     * 清理过期数据
     */
    private void cleanup() {
        long currentTime = System.currentTimeMillis();
        boolean hasChanges = false;
        
        for (Map.Entry<UUID, List<com.example.aichatplugin.Message>> entry : conversationHistory.entrySet()) {
            List<com.example.aichatplugin.Message> history = entry.getValue();
            synchronized (history) {
                int originalSize = history.size();
                history.removeIf(msg -> currentTime - msg.getTimestamp() > MESSAGE_TIMEOUT);
                if (history.size() != originalSize) {
                    hasChanges = true;
                }
            }
        }
        
        lastResponseTime.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > MESSAGE_TIMEOUT
        );
        
        if (hasChanges) {
            saveHistory();
        }
    }

    /**
     * 保存对话历史
     */
    private void saveHistory() {
        if (!plugin.getConfigLoader().isConversationPersistenceEnabled()) {
            return;
        }
        
        plugin.getLogger().info("正在保存对话历史...");
        FileConfiguration config = new YamlConfiguration();
        int savedCount = 0;
        
        try {
            // 写入新数据
            for (Map.Entry<UUID, List<com.example.aichatplugin.Message>> entry : conversationHistory.entrySet()) {
                String key = entry.getKey().toString();
                List<com.example.aichatplugin.Message> history = entry.getValue();
                
                if (!history.isEmpty()) {
                    String section = key + ".";
                    List<Map<String, Object>> messages = new ArrayList<>();
                    for (com.example.aichatplugin.Message msg : history) {
                        Map<String, Object> messageMap = new HashMap<>();
                        messageMap.put("sender", msg.getSender());
                        messageMap.put("content", msg.getContent());
                        messageMap.put("isAI", msg.isAI());
                        messageMap.put("timestamp", msg.getTimestamp());
                        messages.add(messageMap);
                    }
                    
                    config.set(section + "messages", messages);
                    savedCount++;
                    plugin.getLogger().info("保存玩家 " + key + " 的对话历史，消息数: " + messages.size());
                }
            }
            
            plugin.getLogger().info("已保存 " + savedCount + " 个玩家的对话历史");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "保存对话历史时发生错误", e);
        }
    }
    
    private void processResponseQueue() {
        if (responseQueue.isEmpty()) {
            return;
        }
        
        plugin.debug("开始处理响应队列，当前队列大小: " + responseQueue.size());
        
        // 每次处理固定数量的任务
        for (int i = 0; i < 5 && !responseQueue.isEmpty(); i++) {
            ResponseTask task = responseQueue.poll();
            if (task != null) {
                try {
                    task.execute();
                    plugin.debug("已执行响应任务");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "处理响应任务时发生错误", e);
                }
            }
        }
    }
    
    /**
     * 处理消息
     */
    public void processMessage(Player player, String message, String type, String... args) {
        if (plugin.isShuttingDown()) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> processMessage(player, message, type, args));
            return;
        }
        if (!player.isOnline() || !config.isChatEnabled() || !player.hasPermission("aichat.use")) return;
        if (isOnCooldown(player.getUniqueId())) return;
        stage1Preprocess(player, message, type, args);
    }
    
    /**
     * 第一阶段：预处理
     */
    private void stage1Preprocess(Player player, String message, String type, String... args) {
        try {
            UUID playerId = player.getUniqueId();
            PlayerProfileManager.PlayerProfile profile = profileManager.getProfile(playerId);
            List<com.example.aichatplugin.Message> history = getConversationHistory(playerId);
            String currentMessage = buildCurrentMessage(message, type, args);
            String sender = "chat".equals(type) ? player.getName() : "SYSTEM";
            
            plugin.debug("预处理完成 - 玩家: " + player.getName() + ", 消息: " + currentMessage);
            
            // 提交到处理阶段
            stage2Process(player, playerId, profile, history, currentMessage, sender);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "预处理消息时发生错误", e);
        }
    }
    
    /**
     * 第二阶段：处理
     */
    private void stage2Process(Player player, UUID playerId, 
                             PlayerProfileManager.PlayerProfile profile,
                             List<com.example.aichatplugin.Message> history, String currentMessage, String sender) {
        try {
            // 🔧 智能环境收集策略：根据消息内容和频率决定是否收集环境信息
            boolean needsEnv = shouldCollectEnvironment(player, currentMessage, history);
            plugin.debug("智能环境决策 - 玩家: " + player.getName() + ", 需要环境: " + needsEnv + ", 原因: " + getDecisionReason(player, currentMessage, history));
            
            if (needsEnv) {
                plugin.debug("需要环境信息 - 玩家: " + player.getName());
                // 记录环境收集时间和位置
                lastEnvironmentCollection.put(playerId, System.currentTimeMillis());
                lastKnownLocation.put(playerId, player.getLocation().clone());
                
                // 使用角色保护的prompt构建机制
                getEnvironmentInfo(player).thenAccept(envInfo -> {
                    String roleProtectedPrompt = buildRoleProtectedPrompt(history, currentMessage, sender, envInfo);
                    stage3GenerateResponse(roleProtectedPrompt, playerId, sender, currentMessage, player);
                }).exceptionally(envError -> {
                    // 🔧 改进：具体的环境信息收集异常处理
                    plugin.getLogger().log(Level.WARNING, "收集环境信息失败，使用无环境信息模式: " + envError.getMessage());
                    String roleProtectedPrompt = buildRoleProtectedPrompt(history, currentMessage, sender, null);
                    stage3GenerateResponse(roleProtectedPrompt, playerId, sender, currentMessage, player);
                    return null;
                });
            } else {
                String roleProtectedPrompt = buildRoleProtectedPrompt(history, currentMessage, sender, null);
                stage3GenerateResponse(roleProtectedPrompt, playerId, sender, currentMessage, player);
            }
        } catch (IllegalArgumentException e) {
            // 🔧 改进：参数验证异常
            plugin.getLogger().warning("消息处理参数错误 - 玩家: " + player.getName() + ", 错误: " + e.getMessage());
        } catch (SecurityException e) {
            // 🔧 改进：安全相关异常
            plugin.getLogger().warning("消息处理安全异常 - 玩家: " + player.getName() + ", 错误: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            // 🔧 改进：内存不足异常
            plugin.getLogger().severe("内存不足，无法处理消息 - 玩家: " + player.getName());
            recordError("out_of_memory");
        } catch (Exception e) {
            // 🔧 改进：其他未预期异常的详细记录
            plugin.getLogger().log(Level.SEVERE, "消息处理发生未预期错误 - 玩家: " + player.getName() + 
                ", 消息: " + currentMessage + ", 类型: " + e.getClass().getSimpleName(), e);
            recordError("message_processing_error");
        }
    }
    
    /**
     * 🔧 构建角色保护的提示词（核心身份保护机制）
     * 确保AI始终保持一致的角色和行为模式，不被历史记录影响
     */
    private String buildRoleProtectedPrompt(List<com.example.aichatplugin.Message> history, 
                                          String currentMessage, String sender, String envInfo) {
        String environmentInfo = envInfo != null ? envInfo : "";
        
        // 使用外部化的提示词构建器
        return new PromptBuilder(config)
            .withHistory(history)
            .withEnvironmentContext(environmentInfo)
            .withEventContext(currentMessage)
            .build();
    }
    
    /**
     * 第三阶段：生成响应
     */
    private void stage3GenerateResponse(String prompt, UUID playerId, String sender, 
                                      String currentMessage, Player player) {
        if (plugin.isShuttingDown()) return;
        try {
            processingStages[STAGE_PROCESS].submit(() -> generateAndQueueResponse(prompt, playerId, sender, currentMessage, player));
        } catch (RejectedExecutionException e) {
            plugin.debug("AI request queue is full or stopping");
        }
    }

    private void generateAndQueueResponse(String prompt, UUID playerId, String sender,
                                      String currentMessage, Player player) {
        try {
            String response = generateResponseWithRetry(prompt, player);
            plugin.debug("生成响应 - 玩家: " + player.getName() + ", 响应: " + response);
            
            if (response != null && !response.isEmpty()) {
                // 🔧 简化：保持AI回复的自然性，仅做基本清理
                final String finalResponse = cleanResponse(response);
                
                // 提交到输出阶段
                if (plugin.isShuttingDown()) return;
                processingStages[STAGE_OUTPUT].submit(() -> {
                    try {
                        addMessage(playerId, sender, currentMessage, false);
                        addMessage(playerId, "AI", finalResponse, true);
                        lastResponseTime.put(playerId, System.currentTimeMillis());
                        lastResponse.put(playerId, finalResponse);
                        responseQueue.offer(new ResponseTask(playerId, finalResponse));
                        plugin.debug("响应已加入队列 - 玩家: " + player.getName());
                    } catch (IllegalStateException e) {
                        // 🔧 改进：队列状态异常
                        plugin.getLogger().warning("响应队列状态异常 - 玩家: " + player.getName() + ", 错误: " + e.getMessage());
                    } catch (OutOfMemoryError e) {
                        // 🔧 改进：内存不足异常
                        plugin.getLogger().severe("内存不足，无法添加响应到队列 - 玩家: " + player.getName());
                        recordError("response_queue_oom");
                    } catch (Exception e) {
                        // 🔧 改进：输出阶段异常的详细记录
                        plugin.getLogger().log(Level.WARNING, "输出阶段处理失败 - 玩家: " + player.getName() + 
                            ", 响应长度: " + finalResponse.length(), e);
                        recordError("output_stage_error");
                    }
                });
            } else {
                plugin.getLogger().warning("AI响应生成失败或为空 - 玩家: " + player.getName());
                recordError("empty_ai_response");
            }
        } catch (IllegalArgumentException e) {
            // 🔧 改进：提示词参数异常
            plugin.getLogger().warning("AI响应生成参数错误 - 玩家: " + player.getName() + ", 错误: " + e.getMessage());
            recordError("invalid_prompt_params");
        } catch (SecurityException e) {
            // 🔧 改进：安全相关异常
            plugin.getLogger().warning("AI响应生成安全异常 - 玩家: " + player.getName() + ", 错误: " + e.getMessage());
            recordError("ai_security_error");
        } catch (OutOfMemoryError e) {
            // 🔧 改进：内存不足异常
            plugin.getLogger().severe("内存不足，无法生成AI响应 - 玩家: " + player.getName());
            recordError("ai_response_oom");
        } catch (Exception e) {
            // 🔧 改进：AI响应生成的详细异常记录
            plugin.getLogger().log(Level.SEVERE, "AI响应生成发生未预期错误 - 玩家: " + player.getName() + 
                ", 提示词长度: " + prompt.length() + ", 类型: " + e.getClass().getSimpleName(), e);
            recordError("ai_response_generation_error");
        }
    }
    
    private String buildCurrentMessage(String message, String type, String... args) {
        switch (type) {
            case "chat":
                return message;
            case "join":
                return "加入了游戏";
            case "quit":
                return "离开了游戏";
            case "death":
                return "死亡了 - " + message;
            case "level_up":
                if (args != null && args.length > 0) {
                    String[] levelInfo = args[0].split(",");
                    return String.format("从 %s 级升到了 %s 级", levelInfo[0], levelInfo[1]);
                }
                return "升级了";
            case "advancement":
                return "获得了成就: " + message;
            case "damage":
                return "受到伤害 - " + message;
            case "potion_add":
                if (message.contains(",")) {
                    String[] parts = message.split(",");
                    String effectName = parts[0];
                    String level = parts.length > 1 ? parts[1] : "0";
                    int levelInt = Integer.parseInt(level) + 1; // Amplifier是从0开始的，所以+1显示等级
                    
                    // 提供更多上下文信息
                    if (effectName.equals("饥饿")) {
                        return String.format("获得了药水效果: %s %d级（会加速消耗饱食度，不是饱食度为0）", effectName, levelInt);
                    } else if (effectName.equals("中毒")) {
                        return String.format("获得了药水效果: %s %d级（会持续扣血）", effectName, levelInt);
                    } else if (effectName.equals("凋零")) {
                        return String.format("获得了药水效果: %s %d级（会持续扣血直到1点）", effectName, levelInt);
                    } else {
                        return String.format("获得了药水效果: %s %d级", effectName, levelInt);
                    }
                }
                return "获得了药水效果: " + message;
            case "potion_remove":
                return "药水效果消失: " + message;
            default:
                return message;
        }
    }
    
    /**
     * 估算文本的token数量
     * 使用基于字符遍历的快速算法，避免正则表达式开销
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        int tokens = 0;
        int length = text.length();
        
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            
            // 中文字符：2 tokens
            if (c >= '\u4e00' && c <= '\u9fa5') {
                tokens += 2;
                continue;
            }
            
            // 英文字母：0.8 tokens
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                tokens += 0.8;
                continue;
            }
            
            // 数字：0.5 tokens
            if (c >= '0' && c <= '9') {
                tokens += 0.5;
                continue;
            }
            
            // 标点符号：0.3 tokens
            if (isPunctuation(c)) {
                tokens += 0.3;
                continue;
            }
            
            // 其他字符：1 token
            tokens += 1;
        }
        
        return (int) Math.ceil(tokens);
    }
    
    /**
     * 判断字符是否为标点符号
     */
    private boolean isPunctuation(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' ||
               c == '"' || c == '\'' || c == '(' || c == ')' || c == '[' || c == ']' ||
               c == '{' || c == '}' || c == '<' || c == '>' || c == '/' || c == '\\' ||
               c == '|' || c == '`' || c == '~' || c == '@' || c == '#' || c == '$' ||
               c == '%' || c == '^' || c == '&' || c == '*' || c == '-' || c == '_' ||
               c == '+' || c == '=' || c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
    
    /**
     * 🔧 构建简化的决策提示（让AI自主判断）
     */
    private String buildSimpleDecisionPrompt(List<com.example.aichatplugin.Message> history, String currentMessage) {
        // 使用外部化的决策提示词模板
        String template = config.getEnvironmentDecisionPrompt();
        
        // 如果模板不存在，使用默认值
        if (template == null || template.isEmpty()) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是Minecraft助手。判断回答这个问题是否需要了解玩家周围的环境信息（如位置、方块、实体等）。\n\n");
        
        // 1. 添加历史消息（控制token数量）
        int startIndex = Math.max(0, history.size() - 2); // 减少到2条历史消息，节省token
        if (history.isEmpty()) {
            prompt.append("== 对话历史 ==\n新对话开始\n\n");
        } else {
            prompt.append("== 对话历史 ==\n");
            while (startIndex < history.size()) {
                StringBuilder tempPrompt = new StringBuilder(prompt);
                for (int i = startIndex; i < history.size(); i++) {
                    com.example.aichatplugin.Message msg = history.get(i);
                    if (msg.isAI()) {
                        tempPrompt.append("AI: ").append(msg.getContent().substring(0, Math.min(msg.getContent().length(), 50))).append("...\n");
                    } else if (msg.getSender().equals("SYSTEM")) {
                        tempPrompt.append("系统: ").append(msg.getContent()).append("\n");
                    } else {
                        tempPrompt.append("玩家: ").append(msg.getContent()).append("\n");
                    }
                }
                
                // 检查token数量
                if (estimateTokenCount(tempPrompt.toString()) <= MAX_DECISION_TOKENS) {
                    prompt = tempPrompt;
                    break;
                }
                startIndex++;
            }
            prompt.append("\n");
        }
        
        // 2. 添加当前消息
        prompt.append("玩家消息：").append(currentMessage).append("\n\n");
        
        // 3. 简单指令
        prompt.append("如果需要环境信息回复YES，否则回复NO。");
              
        return prompt.toString();
        }
        
        // 使用模板并替换变量
        return template.replace("{message}", currentMessage);
    }
    
    /**
     * 🔧 温和回复清理（保持AI自然表达）
     */
    private String cleanResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return response;
        }
        
        // 🔧 只做基本清理，保持AI的自然表达能力
        String cleaned = response
            // 只移除换行和多余空格，保持内容完整性
            .replaceAll("\\s*[\\n\\r]\\s*", " ")
            .replaceAll("\\s{2,}", " ")
            .trim();
        
        return cleaned;
    }
    

    
    /**
     * 🔧 优化：判断AI是否需要环境信息
     * 支持多种表达方式，提高准确性
     */
    private boolean needsEnvironment(String aiDecision) {
        if (aiDecision == null || aiDecision.trim().isEmpty()) {
            // 🔧 修复：AI决策为空时，默认获取环境信息
            plugin.debug("AI决策为空，默认获取环境信息");
            return true;
        }
        
        String clean = aiDecision.toLowerCase().trim();
        plugin.debug("AI决策原文: '" + aiDecision + "' -> 清理后: '" + clean + "'");
        
        // 🔧 优化1：支持简洁回复
        if (clean.equals("yes") || clean.equals("no")) {
            boolean result = clean.equals("yes");
            plugin.debug("简洁回复判断: " + result);
            return result;
        }
        
        // 🔧 优化2：支持中英文多种表达
        String[] positiveKeywords = {
            "yes", "需要", "要", "获取", "收集", "查看", "环境", "needen", "need",
            "是的", "对", "确实", "应该", "可以", "true", "1"
        };
        
        String[] negativeKeywords = {
            "no", "不需要", "不用", "无需", "不", "false", "0",
            "没必要", "不必", "跳过", "忽略"
        };
        
        // 先检查消极关键词（明确不需要的情况）
        for (String keyword : negativeKeywords) {
            if (clean.contains(keyword)) {
                plugin.debug("匹配消极关键词: " + keyword);
                return false;
            }
        }
        
        // 检查积极关键词
        for (String keyword : positiveKeywords) {
            if (clean.contains(keyword)) {
                plugin.debug("匹配积极关键词: " + keyword);
                return true;
            }
        }
        
        // 🔧 优化3：如果包含问号，可能是反问，偏向于需要环境信息
        if (clean.contains("?") || clean.contains("？")) {
            plugin.debug("包含问号，偏向需要环境信息");
            return true;
        }
        
        // 🔧 修复：默认策略改为获取环境信息（提供更好的用户体验）
        plugin.debug("无法确定意图，默认获取环境信息以提供更好的回复");
        return true;
    }
    
    /**
     * 带重试的响应生成
     */
    private String generateResponseWithRetry(String prompt, Player player) {
        // The HTTP service owns retries. Nested retries multiplied cost and shutdown time.
        if (plugin.isShuttingDown() || Thread.currentThread().isInterrupted()) return null;
        try {
            return aiService.generateResponse(prompt, player);
        } catch (Exception e) {
            plugin.getLogger().warning("AI请求失败: " + e.getClass().getSimpleName());
            return null;
        }
    }
    
    /**
     * 添加消息到历史记录
     */
    public void addMessage(UUID playerId, String sender, String content, boolean isAI) {
        List<com.example.aichatplugin.Message> history = conversationHistory.computeIfAbsent(playerId, k -> new ArrayList<>());
        synchronized (history) {
            history.add(new com.example.aichatplugin.Message(sender, content, isAI));
            dirtyPlayers.add(playerId);
            updateHistoryVersion(playerId);
            historyChanged.put(playerId, true);
        }
        
        plugin.debug("添加消息到历史记录 - 玩家: " + playerId + ", 发送者: " + sender + ", 内容: " + content);
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        plugin.getLogger().info("开始关闭ConversationManager...");
        
        // 🔧 改进：标记正在关闭状态，防止新任务提交
        try {
            // 首先停止接收新任务
            for (ExecutorService stage : processingStages) {
                if (stage != null && !stage.isShutdown()) {
                    stage.shutdown(); // 优雅关闭，不接受新任务但完成现有任务
                }
            }
            
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
            }
            
            if (responseScheduler != null && !responseScheduler.isShutdown()) {
                responseScheduler.shutdown();
            }
            
            plugin.getLogger().info("已停止接收新任务，等待现有任务完成...");
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "停止任务接收时发生错误", e);
        }
        
        // 🔧 改进：等待处理阶段线程池关闭
        boolean allStagesClosed = true;
        for (int i = 0; i < processingStages.length; i++) {
            ExecutorService stage = processingStages[i];
            if (stage != null) {
                try {
                    if (!stage.awaitTermination(3, TimeUnit.SECONDS)) {
                        plugin.getLogger().warning("处理阶段 " + i + " 未能在3秒内关闭，强制关闭");
                        stage.shutdownNow();
                        if (!stage.awaitTermination(2, TimeUnit.SECONDS)) {
                            plugin.getLogger().warning("处理阶段 " + i + " 强制关闭失败");
                            allStagesClosed = false;
                        }
                    }
                } catch (InterruptedException e) {
                    plugin.getLogger().warning("等待处理阶段 " + i + " 关闭时被中断");
                    stage.shutdownNow();
                    Thread.currentThread().interrupt();
                    allStagesClosed = false;
                }
            }
        }
        
        // 🔧 改进：关闭主线程池
        if (executor != null) {
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("主线程池未能在3秒内关闭，强制关闭");
                    executor.shutdownNow();
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        plugin.getLogger().warning("主线程池强制关闭失败");
                    }
                }
            } catch (InterruptedException e) {
                plugin.getLogger().warning("等待主线程池关闭时被中断");
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // 🔧 改进：关闭响应调度器
        if (responseScheduler != null) {
            try {
                if (!responseScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("响应调度器未能在3秒内关闭，强制关闭");
                    responseScheduler.shutdownNow();
                    if (!responseScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                        plugin.getLogger().warning("响应调度器强制关闭失败");
                    }
                }
            } catch (InterruptedException e) {
                plugin.getLogger().warning("等待响应调度器关闭时被中断");
                responseScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Workers have stopped; persist the final consistent history directly.
        forceSaveAll();
        
        // 🔧 改进：清理所有数据结构
        try {
            playerQueues.clear();
            responseQueue.clear();
            envCache.clear();
            messageCache.clear();
            historyChanged.clear();
            lastEnvironmentCollection.clear();
            lastKnownLocation.clear();
            lastResponseTime.clear();
            lastResponse.clear();
            historyVersions.clear();
            
            // 清理对话历史（注意并发安全）
            conversationHistory.clear();
            dirtyPlayers.clear();
            
            plugin.getLogger().info("数据结构清理完成");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "清理数据结构时发生错误", e);
        }
        
        // 🔧 报告关闭状态
        if (allStagesClosed) {
            plugin.getLogger().info("ConversationManager 已安全关闭");
        } else {
            plugin.getLogger().warning("ConversationManager 关闭完成，但部分线程池可能未完全关闭");
        }
    }

    /**
     * 检查是否在冷却中
     */
    public boolean isOnCooldown(UUID playerId) {
        Long lastTime = lastResponseTime.get(playerId);
        if (lastTime == null) return false;
        return System.currentTimeMillis() - lastTime < RESPONSE_COOLDOWN;
    }
    
    /**
     * 获取最后响应
     */
    public String getLastResponse(UUID playerId) {
        return lastResponse.get(playerId);
    }
    
    /**
     * 获取玩家的对话历史
     */
    public List<com.example.aichatplugin.Message> getConversationHistory(UUID playerId) {
        List<com.example.aichatplugin.Message> history = conversationHistory.get(playerId);
        if (history == null) return new ArrayList<>();
        synchronized (history) { return new ArrayList<>(history); }
    }
    
    /**
     * 清空玩家的历史记录
     */
    public void clearPlayerHistory(UUID playerId) {
        List<com.example.aichatplugin.Message> history = conversationHistory.get(playerId);
        if (history != null) {
            synchronized (history) {
                history.clear();
                dirtyPlayers.add(playerId);
                updateHistoryVersion(playerId);
                historyChanged.put(playerId, true);
                messageCache.remove(playerId);
            }
        }
        
        // 删除历史记录文件
        File historyFile = getPlayerHistoryFile(playerId);
        if (historyFile.exists()) {
            try {
                historyFile.delete();
                plugin.getLogger().info("已删除玩家 " + playerId + " 的历史记录文件");
            } catch (Exception e) {
                plugin.getLogger().warning("删除历史记录文件失败: " + e.getMessage());
            }
        }
        
        plugin.debug("已清空玩家 " + playerId + " 的历史记录");
    }
    
    /**
     * 获取玩家的最后一条消息
     */
    public String getLastMessage(UUID playerId) {
        List<com.example.aichatplugin.Message> history = getConversationHistory(playerId);
        if (history.isEmpty()) {
            return null;
        }
        
        // 从后往前找玩家的最后一条消息
        for (int i = history.size() - 1; i >= 0; i--) {
            com.example.aichatplugin.Message msg = history.get(i);
            if (!msg.isAI()) {
                return msg.getContent();
            }
        }
        return null;
    }
    
    /**
     * 获取缓存的消息JSON
     */
    public JsonArray getCachedMessages(UUID playerId) {
        String version = historyVersions.get(playerId);
        if (version == null || historyChanged.getOrDefault(playerId, true)) {
            List<com.example.aichatplugin.Message> history = getConversationHistory(playerId);
            JsonArray messages = buildMessageJson(history);
            messageCache.put(playerId, messages);
            historyChanged.put(playerId, false);
            return messages;
        }
        return messageCache.get(playerId);
    }

    /**
     * 构建消息JSON
     */
    private JsonArray buildMessageJson(List<com.example.aichatplugin.Message> history) {
        JsonArray messages = new JsonArray();
        for (com.example.aichatplugin.Message msg : history) {
            JsonObject message = new JsonObject();
            message.addProperty("sender", msg.getSender());
            message.addProperty("content", msg.getContent());
            message.addProperty("isAI", msg.isAI());
            message.addProperty("timestamp", msg.getTimestamp());
            messages.add(message);
        }
        return messages;
    }

    /**
     * 清理过期对话
     */
    private void cleanupOldConversations() {
        try {
            conversationHistory.entrySet().removeIf(entry -> {
                if (entry.getValue().isEmpty()) {
                    messageCache.remove(entry.getKey());
                    historyChanged.remove(entry.getKey());
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "清理对话历史时发生错误", e);
        }
    }

    private class ResponseTask {
        private final UUID recipient;
        private final String response;
        
        ResponseTask(UUID recipient, String response) {
            this.recipient = recipient;
            this.response = response;
        }
        
        void execute() {
            try {
                // 构建带前缀的安全消息
                String messageToSend = ChatColor.translateAlternateColorCodes('&', "&b[AI] &f" + response);
                
                plugin.debug("准备向所有玩家发送安全消息: " + messageToSend);
                
                // 安全的消息广播方式：遍历所有玩家并单独发送
                Runnable broadcastTask = () -> {
                    if (config.isBroadcastEnabled()) {
                        for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(messageToSend);
                    } else {
                        Player player = Bukkit.getPlayer(recipient);
                        if (player != null && player.isOnline()) player.sendMessage(messageToSend);
                    }
                    plugin.debug("已向所有在线玩家发送AI响应: " + response);
                };

                // 确保在主线程中执行
                if (Bukkit.isPrimaryThread()) {
                    broadcastTask.run();
                } else {
                    Bukkit.getScheduler().runTask(plugin, broadcastTask);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "广播消息时发生错误", e);
            }
        }
    }

    public void handlePlayerQuit(Player player) {
        // A quit event must never wait for an external AI request.
        UUID playerId = player.getUniqueId();
        playerQueues.remove(playerId);
        lastEnvironmentCollection.remove(playerId);
        lastKnownLocation.remove(playerId);
    }

    /**
     * 应用新的配置
     * 
     * @param config 新的配置
     */
    public void applyConfig(FileConfiguration config) {
        plugin.debug("应用对话管理器新配置");
        
        // 更新对话设置
        int maxHistory = config.getInt("history.max-history", 5);
        int maxContextLength = config.getInt("history.max-context-length", 1000);
        
        // 更新配置
        this.config.set("history.max-history", maxHistory);
        this.config.set("history.max-context-length", maxContextLength);
        
        // 清理历史记录
        conversationHistory.clear();
        
        plugin.debug("对话管理器配置已更新");
    }

    /**
     * 设置应急模式下的固定响应
     */
    public void setFallbackResponse(String response) {
        this.fallbackResponse = response;
    }
    
    /**
     * 清除应急模式响应
     */
    public void clearFallbackResponse() {
        this.fallbackResponse = null;
    }
    
    /**
     * 获取应急模式响应
     */
    public String getFallbackResponse() {
        return fallbackResponse;
    }

    /**
     * 获取动态最大任务数
     */
    private int getDynamicMaxTasks() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(BASE_MAX_TASKS, cores * MAX_TASKS_PER_CORE);
    }
    
    /**
     * 获取环境信息（带缓存和降级）
     */
    private CompletableFuture<String> getEnvironmentInfo(Player player) {
        UUID playerId = player.getUniqueId();
        
        // 1. 检查缓存
        CachedEnvironment cached = envCache.get(playerId);
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.environment);
        }
        
        // 2. 检查任务数限制
        int currentTasks = pendingTasks.get();
        int maxTasks = getDynamicMaxTasks();
        
        if (currentTasks >= maxTasks) {
            // 3. 任务数超限，返回缓存或默认值
            String fallback = cached != null ? cached.environment : "环境信息暂不可用";
            return CompletableFuture.completedFuture("[降级] " + fallback);
        }
        
        // 4. 执行环境收集
        pendingTasks.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        return environmentCollector.collectEnvironmentInfo(player)
            .thenApply(envInfo -> {
                // 5. 检查超时
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > ENV_TIMEOUT) {
                    envInfo = "[延迟] " + envInfo;
                }
                
                // 6. 更新缓存
                envCache.put(playerId, new CachedEnvironment(envInfo));
                return envInfo;
            })
            .exceptionally(e -> {
                plugin.getLogger().log(Level.WARNING, "收集环境信息时发生错误", e);
                return "[错误] 环境信息获取失败";
            })
            .whenComplete((result, error) -> pendingTasks.decrementAndGet());
    }
    
    /**
     * 清理过期缓存
     */
    private void cleanupEnvCache() {
        long cacheTTL = config.getEnvironmentCacheTTL();
        envCache.entrySet().removeIf(entry -> 
            System.currentTimeMillis() - entry.getValue().timestamp > cacheTTL
        );
    }

    /**
     * 生成复合版本标识
     */
    private String generateVersionId(UUID playerId) {
        return playerId.toString() + "-" + System.nanoTime();
    }
    
    /**
     * 更新历史版本
     */
    private void updateHistoryVersion(UUID playerId) {
        historyVersions.put(playerId, generateVersionId(playerId));
    }

    /**
     * 清理过期备份
     */
    private void cleanupOldBackups(UUID playerId) {
        File playerFile = getPlayerHistoryFile(playerId);
        File backupDir = new File(playerFile.getParent(), "backups");
        
        if (backupDir.exists()) {
            File[] backups = backupDir.listFiles((dir, name) -> 
                name.startsWith(playerId.toString() + "_") && name.endsWith(".json")
            );
            
            if (backups != null) {
                long cutoff = System.currentTimeMillis() - 86400000; // 24小时
                for (File backup : backups) {
                    if (backup.lastModified() < cutoff) {
                        backup.delete();
                    }
                }
            }
        }
    }

    /**
     * 手动强制保存所有历史记录
     * 用于立即保存而不等待定时器
     */
    public void forceSaveAll() {
        for (UUID playerId : new HashSet<>(dirtyPlayers)) {
            try {
                savePlayerHistory(playerId);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "无法保存玩家历史: " + playerId, e);
            }
        }
    }
    
    /**
     * 获取当前脏数据统计
     */
    public String getDirtyStatsMessage() {
        int dirtyCount = dirtyPlayers.size();
        int totalPlayers = conversationHistory.size();
        return String.format("当前状态: %d/%d 个玩家有未保存的历史记录", dirtyCount, totalPlayers);
    }
    
    /**
     * 🔧 智能环境收集决策
     * 根据消息内容、时间间隔和上下文决定是否需要收集环境信息
     */
    private boolean shouldCollectEnvironment(Player player, String message, List<com.example.aichatplugin.Message> history) {
        UUID playerId = player.getUniqueId();
        String lowerMessage = message.toLowerCase().trim();
        
        // 1. 环境相关关键词 - 立即收集
        String[] environmentKeywords = {
            "这里", "位置", "在哪", "周围", "附近", "天气", "时间", "几点", "安全", 
            "怪物", "动物", "方块", "建筑", "环境", "看看", "现在", "当前"
        };
        
        for (String keyword : environmentKeywords) {
            if (lowerMessage.contains(keyword)) {
                return true;
            }
        }
        
        // 2. 第一次对话 - 收集环境信息
        if (history.isEmpty()) {
            return true;
        }
        
        // 3. 检查最近的环境收集时间
        long currentTime = System.currentTimeMillis();
        Long lastEnvTime = lastEnvironmentCollection.get(playerId);
        
        // 4. 超过配置的时间间隔没有收集环境信息 - 收集一次
        long collectionInterval = config.getSmartCollectionInterval();
        if (lastEnvTime == null || currentTime - lastEnvTime > collectionInterval) {
            return true;
        }
        
        // 5. 玩家移动较远距离 - 重新收集
        Location currentLoc = player.getLocation();
        Location lastLoc = lastKnownLocation.get(playerId);
        double changeThreshold = config.getLocationChangeThreshold();
        if (lastLoc != null && (!Objects.equals(currentLoc.getWorld(), lastLoc.getWorld()) || currentLoc.distance(lastLoc) > changeThreshold)) {
            return true;
        }
        
        // 6. 问号表示疑问，可能需要环境信息
        if (lowerMessage.contains("?") || lowerMessage.contains("？")) {
            return true;
        }
        
        // 默认不收集（节省性能）
        return false;
    }
    
    /**
     * 获取环境收集决策的原因（用于调试）
     */
    private String getDecisionReason(Player player, String message, List<com.example.aichatplugin.Message> history) {
        UUID playerId = player.getUniqueId();
        String lowerMessage = message.toLowerCase().trim();
        
        // 检查环境关键词
        String[] environmentKeywords = {
            "这里", "位置", "在哪", "周围", "附近", "天气", "时间", "几点", "安全", 
            "怪物", "动物", "方块", "建筑", "环境", "看看", "现在", "当前"
        };
        
        for (String keyword : environmentKeywords) {
            if (lowerMessage.contains(keyword)) {
                return "包含环境关键词: " + keyword;
            }
        }
        
        if (history.isEmpty()) {
            return "首次对话";
        }
        
        // 检查时间间隔
        long currentTime = System.currentTimeMillis();
        Long lastEnvTime = lastEnvironmentCollection.get(playerId);
        long collectionInterval = config.getSmartCollectionInterval();
        if (lastEnvTime == null || currentTime - lastEnvTime > collectionInterval) {
            return "超过" + (collectionInterval/60000) + "分钟未收集环境信息";
        }
        
        // 检查位置变化
        Location currentLoc = player.getLocation();
        Location lastLoc = lastKnownLocation.get(playerId);
        double changeThreshold = config.getLocationChangeThreshold();
        if (lastLoc != null && (!Objects.equals(currentLoc.getWorld(), lastLoc.getWorld()) || currentLoc.distance(lastLoc) > changeThreshold)) {
            return "位置变化超过" + changeThreshold + "格";
        }
        
        if (lowerMessage.contains("?") || lowerMessage.contains("？")) {
            return "包含疑问词";
        }
        
        return "无需收集环境信息";
    }
    
    // 添加新的缓存字段
    private final Map<UUID, Long> lastEnvironmentCollection = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastKnownLocation = new ConcurrentHashMap<>();

    /**
     * 🔧 新增：错误记录方法
     */
    private void recordError(String errorType) {
        try {
            if (plugin.getPerformanceMonitor() != null) {
                plugin.getPerformanceMonitor().recordError(errorType);
            }
        } catch (Exception e) {
            // 避免无限递归
            plugin.getLogger().fine("记录错误统计时失败: " + e.getMessage());
        }
    }
}
