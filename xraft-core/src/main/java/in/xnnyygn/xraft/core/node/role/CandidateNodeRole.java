package in.xnnyygn.xraft.core.node.role;

import in.xnnyygn.xraft.core.node.NodeId;
import in.xnnyygn.xraft.core.schedule.ElectionTimeout;

import javax.annotation.concurrent.Immutable;

@Immutable
public class CandidateNodeRole extends AbstractNodeRole {

    private final int votesCount;
    private final ElectionTimeout electionTimeout;

    // 票数为1, 自己投递自己, 节点发起选举并变成 Candidate 角色时调用
    public CandidateNodeRole(int term, ElectionTimeout electionTimeout) {
        this(term, 1, electionTimeout);
    }

    // 指定票数, 收到其它节点的投票时使用
    public CandidateNodeRole(int term, int votesCount, ElectionTimeout electionTimeout) {
        super(RoleName.CANDIDATE, term);
        this.votesCount = votesCount;
        this.electionTimeout = electionTimeout;
    }

    public int getVotesCount() {
        return votesCount;
    }

    @Override
    public NodeId getLeaderId(NodeId selfId) {
        return null;
    }

    @Override
    public void cancelTimeoutOrTask() {
        electionTimeout.cancel();
    }

    @Override
    public RoleState getState() {
        DefaultRoleState state = new DefaultRoleState(RoleName.CANDIDATE, term);
        state.setVotesCount(votesCount);
        return state;
    }

    @Override
    protected boolean doStateEquals(AbstractNodeRole role) {
        CandidateNodeRole that = (CandidateNodeRole) role;
        return this.votesCount == that.votesCount;
    }

    @Override
    public String toString() {
        return "CandidateNodeRole{" +
                "term=" + term +
                ", votesCount=" + votesCount +
                ", electionTimeout=" + electionTimeout +
                '}';
    }
}
