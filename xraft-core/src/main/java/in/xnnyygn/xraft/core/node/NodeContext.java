package in.xnnyygn.xraft.core.node;

import com.google.common.eventbus.EventBus;
import in.xnnyygn.xraft.core.log.Log;
import in.xnnyygn.xraft.core.node.config.NodeConfig;
import in.xnnyygn.xraft.core.node.store.NodeStore;
import in.xnnyygn.xraft.core.rpc.Connector;
import in.xnnyygn.xraft.core.schedule.Scheduler;
import in.xnnyygn.xraft.core.support.TaskExecutor;

/**
 * Node context.
 * AbstractNodeRole 当前节点自己的信息
 * 定时器组件
 * 成员表组件
 * 日志组件
 * RPC 组件
 *
 * 该类的 getter 方法命名和一般的 getXXX 不同, 省略了 get。 这里单纯是为了让方法名更短, 不这么做也没有问题
 * <p>
 * Node context should not change after initialization. e.g {@link NodeBuilder}.
 * </p>
 */
public class NodeContext {

    private NodeId selfId; // 当前节点id
    private NodeGroup group; // 成员列表
    private Log log; // 日志
    private Connector connector; // RPC 组件接口
    private NodeStore store; // 部分角色状态数据存储
    private Scheduler scheduler; // 定时器组件
    private NodeMode mode;
    private NodeConfig config;
    private EventBus eventBus;
    private TaskExecutor taskExecutor; // 主线程执行器
    private TaskExecutor groupConfigChangeTaskExecutor;

    // 获取自己的节点 ID
    public NodeId selfId() {
        return selfId;
    }

    // 设置自己的节点 ID
    public void setSelfId(NodeId selfId) {
        this.selfId = selfId;
    }

    public NodeGroup group() {
        return group;
    }

    public void setGroup(NodeGroup group) {
        this.group = group;
    }

    public Log log() {
        return log;
    }

    public void setLog(Log log) {
        this.log = log;
    }

    public Connector connector() {
        return connector;
    }

    public void setConnector(Connector connector) {
        this.connector = connector;
    }

    public NodeStore store() {
        return store;
    }

    public void setStore(NodeStore store) {
        this.store = store;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public NodeMode mode() {
        return mode;
    }

    public void setMode(NodeMode mode) {
        this.mode = mode;
    }

    public NodeConfig config() {
        return config;
    }

    public void setConfig(NodeConfig config) {
        this.config = config;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public void setEventBus(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public TaskExecutor taskExecutor() {
        return taskExecutor;
    }

    public void setTaskExecutor(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public TaskExecutor groupConfigChangeTaskExecutor() {
        return groupConfigChangeTaskExecutor;
    }

    public void setGroupConfigChangeTaskExecutor(TaskExecutor groupConfigChangeTaskExecutor) {
        this.groupConfigChangeTaskExecutor = groupConfigChangeTaskExecutor;
    }

}
