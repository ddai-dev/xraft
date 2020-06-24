package in.xnnyygn.xraft.kvstore.message;

import in.xnnyygn.xraft.core.node.NodeId;

/**
 * 要求客户端重定向时, 可提供 Leader 节点的 NodeId 或者网络地址
 */
public class Redirect {

    // leaderId 可能为空。 在刚启动的情况下, leaderId 尚未确定, 非 leader 节点只能返回 null
    // 客户端在 leaderId 为 null 时, 可以随机选择剩下节点中的一个或者重试
    private final String leaderId;

    public Redirect(NodeId leaderId) {
        this(leaderId != null ? leaderId.getValue() : null);
    }

    public Redirect(String leaderId) {
        this.leaderId = leaderId;
    }

    // 获取 leader 的id
    public String getLeaderId() {
        return leaderId;
    }

    @Override
    public String toString() {
        return "Redirect{" + "leaderId=" + leaderId + '}';
    }

}
