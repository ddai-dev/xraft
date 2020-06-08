package in.xnnyygn.xraft.core.node.role;

import in.xnnyygn.xraft.core.node.NodeId;
import in.xnnyygn.xraft.core.schedule.LogReplicationTask;

import javax.annotation.concurrent.Immutable;

/**
 * 没有选举超时, 但是需要定时给 Follower 节点发送心跳, 所以有一个日志复制定时器
 */
@Immutable
public class LeaderNodeRole extends AbstractNodeRole {

    private final LogReplicationTask logReplicationTask;

    public LeaderNodeRole(int term, LogReplicationTask logReplicationTask) {
        super(RoleName.LEADER, term);
        this.logReplicationTask = logReplicationTask;
    }

    @Override
    public NodeId getLeaderId(NodeId selfId) {
        return selfId;
    }

    @Override
    public void cancelTimeoutOrTask() {
        logReplicationTask.cancel();
    }

    @Override
    public RoleState getState() {
        return new DefaultRoleState(RoleName.LEADER, term);
    }

    @Override
    protected boolean doStateEquals(AbstractNodeRole role) {
        return true;
    }

    @Override
    public String toString() {
        return "LeaderNodeRole{term=" + term + ", logReplicationTask=" + logReplicationTask + '}';
    }
}
