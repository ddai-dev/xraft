package in.xnnyygn.xraft.core.rpc;

import in.xnnyygn.xraft.core.node.NodeEndpoint;
import in.xnnyygn.xraft.core.node.NodeId;
import in.xnnyygn.xraft.core.rpc.message.*;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * sendRequestVote 是群发
 * sendAppendEntries 是单发(必须根据每个节点的 复制进度 单独设置 AppendEntries 消息
 * Connector.
 */
public interface Connector {

    /**
     * Initialize connector. 初始化
     * <p>
     * SHOULD NOT call more than one.
     * </p>
     */
    void initialize();

    /**
     * Send request vote rpc. 发送 RequestVote 消息给多个节点
     * <p>
     * Remember to exclude self node before sending.
     * </p>
     * <p>
     * Do nothing if destination endpoints is empty.
     * </p>
     *
     * @param rpc                  rpc
     * @param destinationEndpoints destination endpoints
     */
    void sendRequestVote(@Nonnull RequestVoteRpc rpc, @Nonnull Collection<NodeEndpoint> destinationEndpoints);

    /**
     * Reply request vote result. 回复 RequestVote 结果给单个节点
     *
     * @param result     result
     * @param rpcMessage rpc message
     */
    void replyRequestVote(@Nonnull RequestVoteResult result, @Nonnull RequestVoteRpcMessage rpcMessage);

    /**
     * Send append entries rpc. 发送 AppendEntries 消息给单个节点
     *
     * @param rpc                 rpc
     * @param destinationEndpoint destination endpoint
     */
    void sendAppendEntries(@Nonnull AppendEntriesRpc rpc, @Nonnull NodeEndpoint destinationEndpoint);

    /**
     * Reply append entries result. 回 AppendEntries 消息给单个节点
     *
     * @param result result
     * @param rpcMessage rpc message
     */
    void replyAppendEntries(@Nonnull AppendEntriesResult result, @Nonnull AppendEntriesRpcMessage rpcMessage);

    /**
     * Send install snapshot rpc.
     *
     * @param rpc rpc
     * @param destinationEndpoint destination endpoint
     */
    void sendInstallSnapshot(@Nonnull InstallSnapshotRpc rpc, @Nonnull NodeEndpoint destinationEndpoint);

    /**
     * Reply install snapshot result.
     *
     * @param result result
     * @param rpcMessage rpc message
     */
    void replyInstallSnapshot(@Nonnull InstallSnapshotResult result, @Nonnull InstallSnapshotRpcMessage rpcMessage);

    /**
     * Called when node becomes leader.
     * <p>
     * Connector may use this chance to close inbound channels.
     * </p>
     */
    void resetChannels();

    /**
     * Close connector.
     */
    void close();

}
