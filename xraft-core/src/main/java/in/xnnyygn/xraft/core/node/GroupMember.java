package in.xnnyygn.xraft.core.node;

/**
 * State of group member.
 *
 * @see ReplicatingState
 */
class GroupMember {

    private final NodeEndpoint endpoint;
    private ReplicatingState replicatingState;
    private boolean major;
    private boolean removing = false;

    // 无日志复制状态的构造函数
    GroupMember(NodeEndpoint endpoint) {
        this(endpoint, null, true);
    }

    // 带日志复制状态的构造函数
    GroupMember(NodeEndpoint endpoint, ReplicatingState replicatingState, boolean major) {
        this.endpoint = endpoint;
        this.replicatingState = replicatingState;
        this.major = major;
    }

    NodeEndpoint getEndpoint() {
        return endpoint;
    }

    NodeId getId() {
        return endpoint.getId();
    }

    boolean idEquals(NodeId id) {
        return endpoint.getId().equals(id);
    }

    void setReplicatingState(ReplicatingState replicatingState) {
        this.replicatingState = replicatingState;
    }

    boolean isReplicationStateSet() {
        return replicatingState != null;
    }

    /**
     * 复制进度默认情况下为 null, 只有当节点成为 Leader 节点后, 才会重置为实际的复制进度
     * @return
     */
    private ReplicatingState ensureReplicatingState() {
        if (replicatingState == null) {
            throw new IllegalStateException("replication state not set");
        }
        return replicatingState;
    }

    boolean isMajor() {
        return major;
    }

    void setMajor(boolean major) {
        this.major = major;
    }

    boolean isRemoving() {
        return removing;
    }

    void setRemoving() {
        removing = true;
    }

    int getNextIndex() {
        return ensureReplicatingState().getNextIndex();
    }

    int getMatchIndex() {
        return ensureReplicatingState().getMatchIndex();
    }

    boolean advanceReplicatingState(int lastEntryIndex) {
        return ensureReplicatingState().advance(lastEntryIndex);
    }

    boolean backOffNextIndex() {
        return ensureReplicatingState().backOffNextIndex();
    }

    void replicateNow() {
        replicateAt(System.currentTimeMillis());
    }

    void replicateAt(long replicatedAt) {
        ReplicatingState replicatingState = ensureReplicatingState();
        replicatingState.setReplicating(true);
        replicatingState.setLastReplicatedAt(replicatedAt);
    }

    boolean isReplicating() {
        return ensureReplicatingState().isReplicating();
    }

    void stopReplicating() {
        ensureReplicatingState().setReplicating(false);
    }

    /**
     * Test if should replicate.
     * <p>
     * Return true if
     * <ol>
     * <li>not replicating</li>
     * <li>replicated but no response in specified timeout</li>
     * </ol>
     * </p>
     *
     * @param readTimeout read timeout
     * @return true if should, otherwise false
     */
    boolean shouldReplicate(long readTimeout) {
        ReplicatingState replicatingState = ensureReplicatingState();
        return !replicatingState.isReplicating() ||
                System.currentTimeMillis() - replicatingState.getLastReplicatedAt() >= readTimeout;
    }

    @Override
    public String toString() {
        return "GroupMember{" +
                "endpoint=" + endpoint +
                ", major=" + major +
                ", removing=" + removing +
                ", replicatingState=" + replicatingState +
                '}';
    }

}
