package in.xnnyygn.xraft.core.node;

import com.google.common.base.Preconditions;
import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.FutureCallback;
import in.xnnyygn.xraft.core.log.InstallSnapshotState;
import in.xnnyygn.xraft.core.log.entry.Entry;
import in.xnnyygn.xraft.core.log.entry.RemoveNodeEntry;
import in.xnnyygn.xraft.core.log.statemachine.StateMachine;
import in.xnnyygn.xraft.core.log.entry.EntryMeta;
import in.xnnyygn.xraft.core.log.entry.GroupConfigEntry;
import in.xnnyygn.xraft.core.log.event.GroupConfigEntryBatchRemovedEvent;
import in.xnnyygn.xraft.core.log.event.GroupConfigEntryCommittedEvent;
import in.xnnyygn.xraft.core.log.event.GroupConfigEntryFromLeaderAppendEvent;
import in.xnnyygn.xraft.core.log.event.SnapshotGenerateEvent;
import in.xnnyygn.xraft.core.log.snapshot.EntryInSnapshotException;
import in.xnnyygn.xraft.core.node.role.*;
import in.xnnyygn.xraft.core.node.store.NodeStore;
import in.xnnyygn.xraft.core.node.task.*;
import in.xnnyygn.xraft.core.rpc.message.*;
import in.xnnyygn.xraft.core.schedule.ElectionTimeout;
import in.xnnyygn.xraft.core.schedule.LogReplicationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Node implementation.
 *
 * @see NodeContext
 */
@ThreadSafe
public class NodeImpl implements Node {

    private static final Logger logger = LoggerFactory.getLogger(NodeImpl.class);

    // callback for async tasks.
    private static final FutureCallback<Object> LOGGING_FUTURE_CALLBACK = new FutureCallback<Object>() {
        @Override
        public void onSuccess(@Nullable Object result) {
        }

        @Override
        public void onFailure(@Nonnull Throwable t) {
            logger.warn("failure", t);
        }
    };

    private final NodeContext context; // 核心组件上下文
    @GuardedBy("this")
    private boolean started; // 是否已启动, 防止重复启动方法, 以及要求系统只能在启动后关闭
    private volatile AbstractNodeRole role; // 当前的角色及信息
    private final List<NodeRoleListener> roleListeners = new CopyOnWriteArrayList<>();

    // NewNodeCatchUpTask and GroupConfigChangeTask related
    private final NewNodeCatchUpTaskContext newNodeCatchUpTaskContext = new NewNodeCatchUpTaskContextImpl();
    private final NewNodeCatchUpTaskGroup newNodeCatchUpTaskGroup = new NewNodeCatchUpTaskGroup();
    private final GroupConfigChangeTaskContext groupConfigChangeTaskContext = new GroupConfigChangeTaskContextImpl();
    private volatile GroupConfigChangeTaskHolder groupConfigChangeTaskHolder = new GroupConfigChangeTaskHolder();

    /**
     * Create with context.
     *
     * @param context context
     */
    NodeImpl(NodeContext context) {
        this.context = context;
    }

    /**
     * Get context.
     *
     * @return context
     */
    NodeContext getContext() {
        return context;
    }

    @Override
    public synchronized void registerStateMachine(@Nonnull StateMachine stateMachine) {
        Preconditions.checkNotNull(stateMachine);
        context.log().setStateMachine(stateMachine);
    }

    @Override
    @Nonnull
    public RoleNameAndLeaderId getRoleNameAndLeaderId() {
        return role.getNameAndLeaderId(context.selfId());
    }

    /**
     * Get role state.
     *
     * @return role state
     */
    @Nonnull
    RoleState getRoleState() {
        return role.getState();
    }

    @Override
    public void addNodeRoleListener(@Nonnull NodeRoleListener listener) {
        Preconditions.checkNotNull(listener);
        roleListeners.add(listener);
    }

    /**
     * 1. start 方法被整个设定为一个同步方法, 防止同时调用, 在已经启动的状态下不做任何事情
     * 2. 在 EventBus 中注册自己感兴趣的消息, 以及初始化 RPC 组件. 切换角色为 Follower, 并且设置选举超时
     *
     * Raft 算法: 启动时系统的角色为 Follower, term 为 0(在有日志的前提下, 需要从最后一条日志条目重新计算最后的 term)
     */
    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        // 注册自己到 EventBus
        context.eventBus().register(this);
        // 初始化链接器
        context.connector().initialize();

        // load term, votedFor from store and become follower 启动时为 Follower
        NodeStore store = context.store();
        changeToRole(new FollowerNodeRole(store.getTerm(), store.getVotedFor(), null, scheduleElectionTimeout()));
        started = true;
    }

    @Override
    public void appendLog(@Nonnull byte[] commandBytes) {
        Preconditions.checkNotNull(commandBytes);
        ensureLeader();
        context.taskExecutor().submit(() -> {
            context.log().appendEntry(role.getTerm(), commandBytes);
            doReplicateLog();
        }, LOGGING_FUTURE_CALLBACK);
    }

    @Override
    @Nonnull
    public GroupConfigChangeTaskReference addNode(@Nonnull NodeEndpoint endpoint) {
        Preconditions.checkNotNull(endpoint);
        ensureLeader();

        // self cannot be added
        if (context.selfId().equals(endpoint.getId())) {
            throw new IllegalArgumentException("new node cannot be self");
        }

        NewNodeCatchUpTask newNodeCatchUpTask = new NewNodeCatchUpTask(newNodeCatchUpTaskContext, endpoint, context.config());

        // task for node exists
        if (!newNodeCatchUpTaskGroup.add(newNodeCatchUpTask)) {
            throw new IllegalArgumentException("node " + endpoint.getId() + " is adding");
        }

        // catch up new server
        // this will be run in caller thread
        NewNodeCatchUpTaskResult newNodeCatchUpTaskResult;
        try {
            newNodeCatchUpTaskResult = newNodeCatchUpTask.call();
            switch (newNodeCatchUpTaskResult.getState()) {
                case REPLICATION_FAILED:
                    return new FixedResultGroupConfigTaskReference(GroupConfigChangeTaskResult.REPLICATION_FAILED);
                case TIMEOUT:
                    return new FixedResultGroupConfigTaskReference(GroupConfigChangeTaskResult.TIMEOUT);
            }
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) {
                logger.warn("failed to catch up new node " + endpoint.getId(), e);
            }
            return new FixedResultGroupConfigTaskReference(GroupConfigChangeTaskResult.ERROR);
        }

        // new server caught up
        // wait for previous group config change
        // it will wait forever by default, but you can change to fixed timeout by setting in NodeConfig
        GroupConfigChangeTaskResult result = awaitPreviousGroupConfigChangeTask();
        if (result != null) {
            return new FixedResultGroupConfigTaskReference(result);
        }

        // submit group config change task
        synchronized (this) {

            // it will happen when try to add two or more nodes at the same time
            if (!groupConfigChangeTaskHolder.isEmpty()) {
                throw new IllegalStateException("group config change concurrently");
            }

            AddNodeTask addNodeTask = new AddNodeTask(groupConfigChangeTaskContext, endpoint, newNodeCatchUpTaskResult);
            Future<GroupConfigChangeTaskResult> future = context.groupConfigChangeTaskExecutor().submit(addNodeTask);
            GroupConfigChangeTaskReference reference = new FutureGroupConfigChangeTaskReference(future);
            groupConfigChangeTaskHolder = new GroupConfigChangeTaskHolder(addNodeTask, reference);
            return reference;
        }
    }

    /**
     * Await previous group config change task.
     *
     * @return {@code null} if previous task done, otherwise error or timeout
     * @see GroupConfigChangeTaskResult#ERROR
     * @see GroupConfigChangeTaskResult#TIMEOUT
     */
    @Nullable
    private GroupConfigChangeTaskResult awaitPreviousGroupConfigChangeTask() {
        try {
            groupConfigChangeTaskHolder.awaitDone(context.config().getPreviousGroupConfigChangeTimeout());
            return null;
        } catch (InterruptedException ignored) {
            return GroupConfigChangeTaskResult.ERROR;
        } catch (TimeoutException ignored) {
            logger.info("previous cannot complete within timeout");
            return GroupConfigChangeTaskResult.TIMEOUT;
        }
    }

    /**
     * Ensure leader status
     *
     * @throws NotLeaderException if not leader
     */
    private void ensureLeader() {
        RoleNameAndLeaderId result = role.getNameAndLeaderId(context.selfId());
        if (result.getRoleName() == RoleName.LEADER) {
            return;
        }
        NodeEndpoint endpoint = result.getLeaderId() != null ? context.group().findMember(result.getLeaderId()).getEndpoint() : null;
        throw new NotLeaderException(result.getRoleName(), endpoint);
    }

    @Override
    @Nonnull
    public GroupConfigChangeTaskReference removeNode(@Nonnull NodeId id) {
        Preconditions.checkNotNull(id);
        ensureLeader();

        // await previous group config change task
        GroupConfigChangeTaskResult result = awaitPreviousGroupConfigChangeTask();
        if (result != null) {
            return new FixedResultGroupConfigTaskReference(result);
        }

        // submit group config change task
        synchronized (this) {

            // it will happen when try to remove two or more nodes at the same time
            if (!groupConfigChangeTaskHolder.isEmpty()) {
                throw new IllegalStateException("group config change concurrently");
            }

            RemoveNodeTask task = new RemoveNodeTask(groupConfigChangeTaskContext, id);
            Future<GroupConfigChangeTaskResult> future = context.groupConfigChangeTaskExecutor().submit(task);
            GroupConfigChangeTaskReference reference = new FutureGroupConfigChangeTaskReference(future);
            groupConfigChangeTaskHolder = new GroupConfigChangeTaskHolder(task, reference);
            return reference;
        }
    }

    /**
     * Cancel current group config change task
     */
    synchronized void cancelGroupConfigChangeTask() {
        if (groupConfigChangeTaskHolder.isEmpty()) {
            return;
        }
        logger.info("cancel group config change task");
        groupConfigChangeTaskHolder.cancel();
        groupConfigChangeTaskHolder = new GroupConfigChangeTaskHolder();
    }

    /**
     * Election timeout 选举超时
     * 1. 变更节点角色
     * 2. 发送请求投票 RequestVote
     * <p>
     * Source: scheduler
     * </p>
     */
    void electionTimeout() {
        context.taskExecutor().submit(this::doProcessElectionTimeout, LOGGING_FUTURE_CALLBACK);
    }

    private void doProcessElectionTimeout() {
        // Leader 角色不可能有选举超时
        if (role.getName() == RoleName.LEADER) { 
            logger.warn("node {}, current role is leader, ignore election timeout", context.selfId());
            return;
        }

        // follower: start election 对于 follower 节点来说是发起选举
        // candidate: restart election 对于 candidate 节点来说是再次发起选举
        int newTerm = role.getTerm() + 1; // 任期 + 1
        role.cancelTimeoutOrTask();

        if (context.group().isStandalone()) {
            if (context.mode() == NodeMode.STANDBY) {
                logger.info("starts with standby mode, skip election");
            } else {

                // become leader
                logger.info("become leader, term {}", newTerm);
                resetReplicatingStates();
                changeToRole(new LeaderNodeRole(newTerm, scheduleLogReplicationTask()));
                context.log().appendEntry(newTerm); // no-op log
            }
        } else {
            // 开始选举
            logger.info("start election");
            // 变成 Candidate 角色
            changeToRole(new CandidateNodeRole(newTerm, scheduleElectionTimeout()));

            // request vote
            EntryMeta lastEntryMeta = context.log().getLastEntryMeta();
            // 发送 RequestVote 消息
            RequestVoteRpc rpc = new RequestVoteRpc();
            rpc.setTerm(newTerm);
            rpc.setCandidateId(context.selfId());
            rpc.setLastLogIndex(lastEntryMeta.getIndex());
            rpc.setLastLogTerm(lastEntryMeta.getTerm());
            // 集群中除了自己的所有节点, sendRequestVote
            context.connector().sendRequestVote(rpc, context.group().listEndpointOfMajorExceptSelf());
        }
    }

    /**
     * Become follower.
     *
     * @param term                    term
     * @param votedFor                voted for
     * @param leaderId                leader id
     * @param scheduleElectionTimeout schedule election timeout or not
     */
    private void becomeFollower(int term, NodeId votedFor, NodeId leaderId, boolean scheduleElectionTimeout) {
        role.cancelTimeoutOrTask();
        if (leaderId != null && !leaderId.equals(role.getLeaderId(context.selfId()))) {
            logger.info("current leader is {}, term {}", leaderId, term);
        }
        // 在之后的服务器成员变更中移除服务器节点时, 会涉及不需要设置选举超时的场景, 在常规情况下都需要设置选举超时
        ElectionTimeout electionTimeout = scheduleElectionTimeout ? scheduleElectionTimeout() : ElectionTimeout.NONE;
        changeToRole(new FollowerNodeRole(term, votedFor, leaderId, electionTimeout));
    }

    /**
     * Change role. 统一角色转换
     *
     * @param newRole new role
     */
    private void changeToRole(AbstractNodeRole newRole) {
        if (!isStableBetween(role, newRole)) {
            logger.debug("node {}, role state changed -> {}", context.selfId(), newRole);
            RoleState state = newRole.getState();

            // update store
            NodeStore store = context.store();
            store.setTerm(state.getTerm());
            store.setVotedFor(state.getVotedFor());

            // notify listeners
            roleListeners.forEach(l -> l.nodeRoleChanged(state));
        }
        role = newRole;
    }

    /**
     * Check if stable between two roles.
     * <p>
     * It is stable when role name not changed and role state except timeout/task not change.
     * </p>
     * <p>
     * If role state except timeout/task not changed, it should not update store or notify listeners.
     * </p>
     *
     * @param before role before
     * @param after  role after
     * @return true if stable, otherwise false
     * @see AbstractNodeRole#stateEquals(AbstractNodeRole)
     */
    private boolean isStableBetween(AbstractNodeRole before, AbstractNodeRole after) {
        assert after != null;
        return before != null && before.stateEquals(after);
    }

    /**
     * Schedule election timeout.
     *
     * @return election timeout
     */
    private ElectionTimeout scheduleElectionTimeout() {
        return context.scheduler().scheduleElectionTimeout(this::electionTimeout);
    }

    /**
     * Reset replicating states.
     */
    private void resetReplicatingStates() {
        context.group().resetReplicatingStates(context.log().getNextIndex());
    }

    /**
     * Schedule log replication task.
     *  Raft 算法要求, 成为 Leader 角色后, 必须立刻发送心跳消息给其它 Follower 节点, 重置这些 Follower 节点的选举超时,
     *  使集群的主从关系稳定下来
     *
     * @return log replication task
     */
    private LogReplicationTask scheduleLogReplicationTask() {
        return context.scheduler().scheduleLogReplicationTask(this::replicateLog);
    }

    /**
     * Replicate log.
     * <p>
     * Source: scheduler.
     * </p>
     */
    void replicateLog() {
        context.taskExecutor().submit(this::doReplicateLog, LOGGING_FUTURE_CALLBACK);
    }

    /**
     * Replicate log to other nodes.
     */
    private void doReplicateLog() {
        // just advance commit index if is unique node
        if (context.group().isStandalone()) {
            context.log().advanceCommitIndex(context.log().getNextIndex() - 1, role.getTerm());
            return;
        }
        logger.debug("replicate log");
        for (GroupMember member : context.group().listReplicationTarget()) {
            if (member.shouldReplicate(context.config().getLogReplicationReadTimeout())) {
                doReplicateLog(member, context.config().getMaxReplicationEntries());
            } else {
                logger.debug("node {} is replicating, skip replication task", member.getId());
            }
        }
    }

    /**
     * Replicate log to specified node. 给日志复制对象节点发送 AppendEntries 消息
     * <p>
     * Normally it will send append entries rpc to node. And change to install snapshot rpc if entry in snapshot.
     * </p>
     *
     * @param member     node
     * @param maxEntries max entries
     * @see EntryInSnapshotException
     */
    private void doReplicateLog(GroupMember member, int maxEntries) {
        member.replicateNow();
        try {
            AppendEntriesRpc rpc = context.log().createAppendEntriesRpc(role.getTerm(), context.selfId(), member.getNextIndex(), maxEntries);
            context.connector().sendAppendEntries(rpc, member.getEndpoint());
        } catch (EntryInSnapshotException ignored) {
            logger.debug("log entry {} in snapshot, replicate with install snapshot RPC", member.getNextIndex());
            InstallSnapshotRpc rpc = context.log().createInstallSnapshotRpc(role.getTerm(), context.selfId(), 0, context.config().getSnapshotDataLength());
            context.connector().sendInstallSnapshot(rpc, member.getEndpoint());
        }
    }

    /**
     * Receive request vote rpc.
     * <p>
     * Source: connector.
     * </p>
     *
     * 方法上标准了 Subscribe, 表示订阅 EventBus 中类型为 RequestVoteRpcMessage 的消息
     *
     * @param rpcMessage rpc message
     */
    @Subscribe
    public void onReceiveRequestVoteRpc(RequestVoteRpcMessage rpcMessage) {
        context.taskExecutor().submit(
                () -> context.connector().replyRequestVote(doProcessRequestVoteRpc(rpcMessage), rpcMessage),
                LOGGING_FUTURE_CALLBACK
        );
    }

    /**
     * 当节点收到 RequestVote 消息 (RequestVoteRpc) 后
     * 1. 核心组件先判断 term,
     *      1. rpcTerm < selfTerm 不投票并返回自己的 term
     *      2. rpcTerm > selfTerm: 马上切换为 Follower, 并根据日志来投票
     *      3. 对于 rpcTerm == selfTerm
     *          3.1 自己是 Candidate 角色时, Candidate 只为自己投票 (不给 remote Node 投票)
     *          3.2 自己是 Leader 角色时, 投票理论上没有意义 (不给 remote Node 投票)
     *          3.3 自己是 Follower 角色
     *              - 自己没有投过票, 并且对方的日志比自己新, 则选择投票
     *              - 自己投过票而且投票的是同一节点的话, 则回复投票
     *              - 其他情况一律不投票
     */
    private RequestVoteResult doProcessRequestVoteRpc(RequestVoteRpcMessage rpcMessage) {

        // skip non-major node, it maybe removed node
        if (!context.group().isMemberOfMajor(rpcMessage.getSourceNodeId())) {
            logger.warn("receive request vote rpc from node {} which is not major node, ignore", rpcMessage.getSourceNodeId());
            return new RequestVoteResult(role.getTerm(), false);
        }

        // reply current term if result's term is smaller than current one
        RequestVoteRpc rpc = rpcMessage.get();
        // 如果对方的 term 比自己小, 则不投票并且返回自己的 term 给对象
        if (rpc.getTerm() < role.getTerm()) {
            logger.debug("term from rpc < current term, don't vote ({} < {})", rpc.getTerm(), role.getTerm());
            return new RequestVoteResult(role.getTerm(), false);
        }

        // step down if result's term is larger than current term
        // 如果对象的 term 比自己大, 则切换为 Follower 角色
        if (rpc.getTerm() > role.getTerm()) {
            boolean voteForCandidate = !context.log().isNewerThan(rpc.getLastLogIndex(), rpc.getLastLogTerm());
            becomeFollower(rpc.getTerm(), (voteForCandidate ? rpc.getCandidateId() : null), null, true);
            return new RequestVoteResult(rpc.getTerm(), voteForCandidate);
        }

        assert rpc.getTerm() == role.getTerm();
        // 本地的 term 与消息的 term 一致
        switch (role.getName()) {
            case FOLLOWER:
                FollowerNodeRole follower = (FollowerNodeRole) role;
                NodeId votedFor = follower.getVotedFor();
                // reply vote granted for 以下两种情况下投票
                // 1. not voted and candidate's log is newer than self 自己尚未投过票, 并且对方的日志比自己新
                // 2. voted for candidate 自己已经给对方投过票
                // 投票后需要切换为 Follower 角色
                if ((votedFor == null && !context.log().isNewerThan(rpc.getLastLogIndex(), rpc.getLastLogTerm())) ||
                        Objects.equals(votedFor, rpc.getCandidateId())) {
                    becomeFollower(role.getTerm(), rpc.getCandidateId(), null, true);
                    return new RequestVoteResult(rpc.getTerm(), true);
                }
                return new RequestVoteResult(role.getTerm(), false);
            case CANDIDATE: // voted for self 已经给自己投过票, 所以不会给其他节点投票
            case LEADER:
                return new RequestVoteResult(role.getTerm(), false);
            default:
                throw new IllegalStateException("unexpected node role [" + role.getName() + "]");
        }
    }

    /**
     * Receive request vote result.
     * 收到 RequestVote 响应后, 节点根据票数决定是变成 Leader, 还是保持 Candidate 角色继续等待其他节点投票
     * <p>
     * Source: connector.
     * </p>
     *
     * @param result result
     */
    @Subscribe
    public void onReceiveRequestVoteResult(RequestVoteResult result) {
        context.taskExecutor().submit(() -> doProcessRequestVoteResult(result), LOGGING_FUTURE_CALLBACK);
    }

    Future<?> processRequestVoteResult(RequestVoteResult result) {
        return context.taskExecutor().submit(() -> doProcessRequestVoteResult(result));
    }

    /**
     * #1 respTerm > selfTerm , 则退化为 Follower
     * #2 如果自己不是 candidate, ignore
     * #3 如果对方的 term 比自己小 或者 对象没有给自己投票, ignore
     * #4 投票成功处理
     *      过半, becomeLeader
     *      重置日志复制进度, 在 Raft 算法中只是简单地把 nextIndex 和 matchIndex 重置为 0, 添加 NO-OP 日志需要在日志部分实现 #5
     * @param result
     */
    private void doProcessRequestVoteResult(RequestVoteResult result) {

        // step down if result's term is larger than current term
        if (result.getTerm() > role.getTerm()) { // #1
            becomeFollower(result.getTerm(), null, null, true);
            return;
        }

        // check role
        if (role.getName() != RoleName.CANDIDATE) { // #2
            logger.debug("receive request vote result and current role is not candidate, ignore");
            return;
        }

        // do nothing if not vote granted
        if (!result.isVoteGranted()) { // #3
            return;
        }

        // #4
        int currentVotesCount = ((CandidateNodeRole) role).getVotesCount() + 1;
        // 节点数
        int countOfMajor = context.group().getCountOfMajor();
        logger.debug("votes count {}, major node count {}", currentVotesCount, countOfMajor);
        role.cancelTimeoutOrTask(); // 取消选举超时定时器
        if (currentVotesCount > countOfMajor / 2) { // 过半

            // become leader
            logger.info("become leader, term {}", role.getTerm());
            resetReplicatingStates();
            changeToRole(new LeaderNodeRole(role.getTerm(), scheduleLogReplicationTask())); // #5
            context.log().appendEntry(role.getTerm()); // no-op log
            context.connector().resetChannels(); // close all inbound channels
        } else {

            // update votes count, 没有过半, 修改投票数目
            changeToRole(new CandidateNodeRole(role.getTerm(), currentVotesCount, scheduleElectionTimeout()));
        }
    }

    /**
     * Receive append entries rpc.
     * 非 Leader 节点收到来自 Leader 节点的心跳信息之后需要重置选举超时, 并记录当前 Leader 节点的 ID
     * <p>
     * Source: connector.
     * </p>
     *
     * @param rpcMessage rpc message
     */
    @Subscribe
    public void onReceiveAppendEntriesRpc(AppendEntriesRpcMessage rpcMessage) {
        context.taskExecutor().submit(() ->
                        context.connector().replyAppendEntries(doProcessAppendEntriesRpc(rpcMessage), rpcMessage),
                LOGGING_FUTURE_CALLBACK
        );
    }

    /**
     * #1 respTerm < selfTerm, 则回复自己的 Term
     * #2 respTerm > selfTerm, becomeFollower
     * #3 设置 leaderId 并重置选举定时器
     * #4 如果有两个 Candidate 角色, 并且另外一个 Candidate 先成为了 Leader, 则当前节点退化为 Follower 角色, 并重置选举定时器
     * #5 Leader 角色受到 AppendEntries 消息, 打印警告日志
     * @param rpcMessage
     * @return
     */
    private AppendEntriesResult doProcessAppendEntriesRpc(AppendEntriesRpcMessage rpcMessage) {
        AppendEntriesRpc rpc = rpcMessage.get();

        // reply current term if term in rpc is smaller than current term
        if (rpc.getTerm() < role.getTerm()) { // #1
            return new AppendEntriesResult(rpc.getMessageId(), role.getTerm(), false);
        }

        // if term in rpc is larger than current term, step down and append entries
        if (rpc.getTerm() > role.getTerm()) { // #2
            becomeFollower(rpc.getTerm(), null, rpc.getLeaderId(), true);
            // 追加日志
            return new AppendEntriesResult(rpc.getMessageId(), rpc.getTerm(), appendEntries(rpc));
        }

        assert rpc.getTerm() == role.getTerm();
        switch (role.getName()) {
            case FOLLOWER: // #3

                // reset election timeout and append entries
                becomeFollower(rpc.getTerm(), ((FollowerNodeRole) role).getVotedFor(), rpc.getLeaderId(), true);
                // 追加日志
                return new AppendEntriesResult(rpc.getMessageId(), rpc.getTerm(), appendEntries(rpc));
            case CANDIDATE: // #4

                // more than one candidate but another node won the election
                becomeFollower(rpc.getTerm(), null, rpc.getLeaderId(), true);
                return new AppendEntriesResult(rpc.getMessageId(), rpc.getTerm(), appendEntries(rpc));
            case LEADER: // #5
                logger.warn("receive append entries rpc from another leader {}, ignore", rpc.getLeaderId());
                return new AppendEntriesResult(rpc.getMessageId(), rpc.getTerm(), false);
            default:
                throw new IllegalStateException("unexpected node role [" + role.getName() + "]");
        }
    }

    /**
     * Append entries and advance commit index if possible.
     *
     * @param rpc rpc
     * @return {@code true} if log appended, {@code false} if previous log check failed, etc
     */
    private boolean appendEntries(AppendEntriesRpc rpc) {
        boolean result = context.log().appendEntriesFromLeader(rpc.getPrevLogIndex(), rpc.getPrevLogTerm(), rpc.getEntries());
        if (result) {
            context.log().advanceCommitIndex(Math.min(rpc.getLeaderCommit(), rpc.getLastEntryIndex()), rpc.getTerm());
        }
        return result;
    }

    /**
     * Receive append entries result.
     * leader 收到其它节点的响应之后, 执行响应的逻辑处理, 等待日志复制定时器下一次触发
     *
     * @param resultMessage result message
     */
    @Subscribe
    public void onReceiveAppendEntriesResult(AppendEntriesResultMessage resultMessage) {
        context.taskExecutor().submit(() -> doProcessAppendEntriesResult(resultMessage), LOGGING_FUTURE_CALLBACK);
    }

    Future<?> processAppendEntriesResult(AppendEntriesResultMessage resultMessage) {
        return context.taskExecutor().submit(() -> doProcessAppendEntriesResult(resultMessage));
    }

    /**
     * #1 如果对方的 term 比自己大, 则退化为 Follower
     * #2 如果自己不是 leader 则忽略
     * @param resultMessage
     */
    private void doProcessAppendEntriesResult(AppendEntriesResultMessage resultMessage) {
        AppendEntriesResult result = resultMessage.get();

        // step down if result's term is larger than current term
        if (result.getTerm() > role.getTerm()) { // #1
            becomeFollower(result.getTerm(), null, null, true);
            return;
        }

        // check role
        if (role.getName() != RoleName.LEADER) {  // #2
            logger.warn("receive append entries result from node {} but current node is not leader, ignore", resultMessage.getSourceNodeId());
            return;
        }

        // dispatch to new node catch up task by node id
        if (newNodeCatchUpTaskGroup.onReceiveAppendEntriesResult(resultMessage, context.log().getNextIndex())) {
            return;
        }

        NodeId sourceNodeId = resultMessage.getSourceNodeId();
        GroupMember member = context.group().getMember(sourceNodeId);
        if (member == null) {
            logger.info("unexpected append entries result from node {}, node maybe removed", sourceNodeId);
            return;
        }

        AppendEntriesRpc rpc = resultMessage.getRpc();
        if (result.isSuccess()) {
            if (!member.isMajor()) {  // removing node
                if (member.isRemoving()) {
                    logger.debug("node {} is removing, skip", sourceNodeId);
                } else {
                    logger.warn("unexpected append entries result from node {}, not major and not removing", sourceNodeId);
                }
                member.stopReplicating();
                return;
            }

            // peer
            // advance commit index if major of match index changed
            if (member.advanceReplicatingState(rpc.getLastEntryIndex())) {
                context.log().advanceCommitIndex(context.group().getMatchIndexOfMajor(), role.getTerm());
            }

            // node caught up
            if (member.getNextIndex() >= context.log().getNextIndex()) {
                member.stopReplicating();
                return;
            }
        } else {

            // backoff next index if failed to append entries
            if (!member.backOffNextIndex()) {
                logger.warn("cannot back off next index more, node {}", sourceNodeId);
                member.stopReplicating();
                return;
            }
        }

        // replicate log to node immediately other than wait for next log replication
        doReplicateLog(member, context.config().getMaxReplicationEntries());
    }

    /**
     * Receive install snapshot rpc.
     *
     * @param rpcMessage rpc message
     */
    @Subscribe
    public void onReceiveInstallSnapshotRpc(InstallSnapshotRpcMessage rpcMessage) {
        context.taskExecutor().submit(
                () -> context.connector().replyInstallSnapshot(doProcessInstallSnapshotRpc(rpcMessage), rpcMessage),
                LOGGING_FUTURE_CALLBACK
        );
    }

    private InstallSnapshotResult doProcessInstallSnapshotRpc(InstallSnapshotRpcMessage rpcMessage) {
        InstallSnapshotRpc rpc = rpcMessage.get();

        // reply current term if term in rpc is smaller than current term
        if (rpc.getTerm() < role.getTerm()) {
            return new InstallSnapshotResult(role.getTerm());
        }

        // step down if term in rpc is larger than current one
        if (rpc.getTerm() > role.getTerm()) {
            becomeFollower(rpc.getTerm(), null, rpc.getLeaderId(), true);
        }
        InstallSnapshotState state = context.log().installSnapshot(rpc);
        if (state.getStateName() == InstallSnapshotState.StateName.INSTALLED) {
            context.group().updateNodes(state.getLastConfig());
        }
        // TODO role check?
        return new InstallSnapshotResult(rpc.getTerm());
    }

    /**
     * Receive install snapshot result.
     *
     * @param resultMessage result message
     */
    @Subscribe
    public void onReceiveInstallSnapshotResult(InstallSnapshotResultMessage resultMessage) {
        context.taskExecutor().submit(
                () -> doProcessInstallSnapshotResult(resultMessage),
                LOGGING_FUTURE_CALLBACK
        );
    }

    private void doProcessInstallSnapshotResult(InstallSnapshotResultMessage resultMessage) {
        InstallSnapshotResult result = resultMessage.get();

        // step down if result's term is larger than current one
        if (result.getTerm() > role.getTerm()) {
            becomeFollower(result.getTerm(), null, null, true);
            return;
        }

        // check role
        if (role.getName() != RoleName.LEADER) {
            logger.warn("receive install snapshot result from node {} but current node is not leader, ignore", resultMessage.getSourceNodeId());
            return;
        }

        // dispatch to new node catch up task by node id
        if (newNodeCatchUpTaskGroup.onReceiveInstallSnapshotResult(resultMessage, context.log().getNextIndex())) {
            return;
        }

        NodeId sourceNodeId = resultMessage.getSourceNodeId();
        GroupMember member = context.group().getMember(sourceNodeId);
        if (member == null) {
            logger.info("unexpected install snapshot result from node {}, node maybe removed", sourceNodeId);
            return;
        }

        InstallSnapshotRpc rpc = resultMessage.getRpc();
        if (rpc.isDone()) {

            // change to append entries rpc
            member.advanceReplicatingState(rpc.getLastIndex());
            int maxEntries = member.isMajor() ? context.config().getMaxReplicationEntries() : context.config().getMaxReplicationEntriesForNewNode();
            doReplicateLog(member, maxEntries);
        } else {

            // transfer data
            InstallSnapshotRpc nextRpc = context.log().createInstallSnapshotRpc(role.getTerm(), context.selfId(),
                    rpc.getOffset() + rpc.getDataLength(), context.config().getSnapshotDataLength());
            context.connector().sendInstallSnapshot(nextRpc, member.getEndpoint());
        }
    }

    /**
     * Group config from leader appended.
     * <p>
     * Source: log.
     * </p>
     *
     * @param event event
     */
    @Subscribe
    public void onGroupConfigEntryFromLeaderAppend(GroupConfigEntryFromLeaderAppendEvent event) {
        context.taskExecutor().submit(() -> {
            GroupConfigEntry entry = event.getEntry();
            if (entry.getKind() == Entry.KIND_REMOVE_NODE &&
                    context.selfId().equals(((RemoveNodeEntry) entry).getNodeToRemove())) {
                logger.info("current node is removed from group, step down and standby");
                becomeFollower(role.getTerm(), null, null, false);
            }
            context.group().updateNodes(entry.getResultNodeEndpoints());
        }, LOGGING_FUTURE_CALLBACK);
    }

    /**
     * Group config entry committed.
     * <p>
     * Source: log.
     * </p>
     *
     * @param event event
     */
    @Subscribe
    public void onGroupConfigEntryCommitted(GroupConfigEntryCommittedEvent event) {
        context.taskExecutor().submit(
                () -> doProcessGroupConfigEntryCommittedEvent(event),
                LOGGING_FUTURE_CALLBACK
        );
    }

    private void doProcessGroupConfigEntryCommittedEvent(GroupConfigEntryCommittedEvent event) {
        GroupConfigEntry entry = event.getEntry();

        // dispatch to group config change task by node id
        groupConfigChangeTaskHolder.onLogCommitted(entry);
    }

    /**
     * Multiple group configs removed.
     * <p>
     * Source: log.
     * </p>
     *
     * @param event event
     */
    @Subscribe
    public void onGroupConfigEntryBatchRemoved(GroupConfigEntryBatchRemovedEvent event) {
        context.taskExecutor().submit(() -> {
            GroupConfigEntry entry = event.getFirstRemovedEntry();
            context.group().updateNodes(entry.getNodeEndpoints());
        }, LOGGING_FUTURE_CALLBACK);
    }

    /**
     * Generate snapshot.
     * <p>
     * Source: log.
     * </p>
     *
     * @param event event
     */
    @Subscribe
    public void onGenerateSnapshot(SnapshotGenerateEvent event) {
        context.taskExecutor().submit(() -> {
            context.log().generateSnapshot(event.getLastIncludedIndex(), context.group().listEndpointOfMajor());
        }, LOGGING_FUTURE_CALLBACK);
    }

    /**
     * Dead event.
     * <p>
     * Source: event-bus.
     * </p>
     *
     * @param deadEvent dead event
     */
    @Subscribe
    public void onReceiveDeadEvent(DeadEvent deadEvent) {
        logger.warn("dead event {}", deadEvent);
    }

    @Override
    public synchronized void stop() throws InterruptedException {
        if (!started) {
            throw new IllegalStateException("node not started");
        }
        context.scheduler().stop();
        context.log().close();
        context.connector().close();
        context.store().close();
        context.taskExecutor().shutdown();
        context.groupConfigChangeTaskExecutor().shutdown();
        started = false;
    }

    private class NewNodeCatchUpTaskContextImpl implements NewNodeCatchUpTaskContext {

        @Override
        public void replicateLog(NodeEndpoint endpoint) {
            context.taskExecutor().submit(
                    () -> doReplicateLog(endpoint, context.log().getNextIndex()),
                    LOGGING_FUTURE_CALLBACK
            );
        }

        @Override
        public void doReplicateLog(NodeEndpoint endpoint, int nextIndex) {
            try {
                AppendEntriesRpc rpc = context.log().createAppendEntriesRpc(role.getTerm(), context.selfId(), nextIndex, context.config().getMaxReplicationEntriesForNewNode());
                context.connector().sendAppendEntries(rpc, endpoint);
            } catch (EntryInSnapshotException ignored) {

                // change to install snapshot rpc if entry in snapshot
                logger.debug("log entry {} in snapshot, replicate with install snapshot RPC", nextIndex);
                InstallSnapshotRpc rpc = context.log().createInstallSnapshotRpc(role.getTerm(), context.selfId(), 0, context.config().getSnapshotDataLength());
                context.connector().sendInstallSnapshot(rpc, endpoint);
            }
        }

        @Override
        public void sendInstallSnapshot(NodeEndpoint endpoint, int offset) {
            InstallSnapshotRpc rpc = context.log().createInstallSnapshotRpc(role.getTerm(), context.selfId(), offset, context.config().getSnapshotDataLength());
            context.connector().sendInstallSnapshot(rpc, endpoint);
        }

        @Override
        public void done(NewNodeCatchUpTask task) {

            // remove task from group
            newNodeCatchUpTaskGroup.remove(task);
        }
    }

    private class GroupConfigChangeTaskContextImpl implements GroupConfigChangeTaskContext {

        @Override
        public void addNode(NodeEndpoint endpoint, int nextIndex, int matchIndex) {
            context.taskExecutor().submit(() -> {
                context.log().appendEntryForAddNode(role.getTerm(), context.group().listEndpointOfMajor(), endpoint);
                assert !context.selfId().equals(endpoint.getId());
                context.group().addNode(endpoint, nextIndex, matchIndex, true);
                NodeImpl.this.doReplicateLog();
            }, LOGGING_FUTURE_CALLBACK);
        }

        @Override
        public void downgradeNode(NodeId nodeId) {
            context.taskExecutor().submit(() -> {
                context.group().downgrade(nodeId);
                Set<NodeEndpoint> nodeEndpoints = context.group().listEndpointOfMajor();
                context.log().appendEntryForRemoveNode(role.getTerm(), nodeEndpoints, nodeId);
                NodeImpl.this.doReplicateLog();
            }, LOGGING_FUTURE_CALLBACK);
        }

        @Override
        public void removeNode(NodeId nodeId) {
            context.taskExecutor().submit(() -> {
                if (nodeId.equals(context.selfId())) {
                    logger.info("remove self from group, step down and standby");
                    becomeFollower(role.getTerm(), null, null, false);
                }
                context.group().removeNode(nodeId);
            }, LOGGING_FUTURE_CALLBACK);
        }

        @Override
        public void done() {

            // clear current group config change
            synchronized (NodeImpl.this) {
                groupConfigChangeTaskHolder = new GroupConfigChangeTaskHolder();
            }
        }

    }

}
