package in.xnnyygn.xraft.core.log.entry;

/**
 * 把日志条目设计为接口而不是数据类的原因:
 *      可以给 Entry#getCommandBytes 方法一定的灵活实现空间, 比如负载均衡的序列化推迟到第一次被调用时才执行
 */
public interface Entry {

    // 日志条目类型
    int KIND_NO_OP = 0;
    int KIND_GENERAL = 1;
    int KIND_ADD_NODE = 3;
    int KIND_REMOVE_NODE = 4;

    // 获取类型
    int getKind();

    // 获取索引
    int getIndex();

    int getTerm();

    // 获取日志元信息
    EntryMeta getMeta();

    // 获取日志负载
    byte[] getCommandBytes();

}
