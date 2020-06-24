package in.xnnyygn.xraft.core.node.store;

import in.xnnyygn.xraft.core.node.NodeId;

import javax.annotation.Nullable;

/**
 * currentTerm 和 votedFor 这两个数据在系统重启后会恢复到之前的状态
 * 不恢复 currentTerm 看起来没有太大的影响, 不管是收到 Leader 节点的心跳信息,
 * 还是发起选举时得到最新的选举 term, 系统都会获得最新的值。
 * votedFor 不同
 *
 * 假如节点 B 在给节点 A 投票后重启, 收到来自候选节点 D 的消息后再次投票, 就违反了一票制
 * 所以节点 B 再次重启后恢复之前的已投票的节点 votedFor , 同时 currentTerm 也必须恢复
 *
 * Node store.
 */
public interface NodeStore {

    /**
     * Get term. 获取 currentTerm
     *
     * @return term
     */
    int getTerm();

    /**
     * Set term. 设置 currentTerm
     *
     * @param term term
     */
    void setTerm(int term);

    /**
     * Get voted for. 获取 votedFor
     *
     * @return voted for
     */
    @Nullable
    NodeId getVotedFor();

    /**
     * Set voted for 设置 votedFor
     *
     * @param votedFor voted for
     */
    void setVotedFor(@Nullable NodeId votedFor);

    /**
     * Close store. 关闭文件
     */
    void close();

}
