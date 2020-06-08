package in.xnnyygn.xraft.core.node.role;

import in.xnnyygn.xraft.core.node.NodeId;

/**
 * 采用表格的形式来进行数据建模
 */
public abstract class AbstractNodeRole {

    private final RoleName name;
    protected final int term;

    AbstractNodeRole(RoleName name, int term) {
        this.name = name;
        this.term = term;
    }

    /**
     * 部分消息的处理只针对某个特定的角色, 在检查时通过 getName 方法可以快速排除非预期的角色
     *
     * @return
     */
    public RoleName getName() {
        return name;
    }

    public int getTerm() {
        return term;
    }

    public RoleNameAndLeaderId getNameAndLeaderId(NodeId selfId) {
        return new RoleNameAndLeaderId(name, getLeaderId(selfId));
    }

    public abstract NodeId getLeaderId(NodeId selfId);

    /**
     * 用于取消每个角色对应的选举超时或者日志复制定时任务(每个角色至多对应一个超时或者定时任务)
     * 实际代码中, 从一个角色转换到另外一个角色时, 必须取消当前的超时或者定时任务, 然后创建新的超时或者定时任务
     */
    public abstract void cancelTimeoutOrTask();

    public abstract RoleState getState();

    public boolean stateEquals(AbstractNodeRole that) {
        if (this.name != that.name || this.term != that.term) {
            return false;
        }
        return doStateEquals(that);
    }

    protected abstract boolean doStateEquals(AbstractNodeRole role);

}
