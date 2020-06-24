package in.xnnyygn.xraft.core.log.sequence;

import in.xnnyygn.xraft.core.log.entry.Entry;
import in.xnnyygn.xraft.core.log.entry.EntryMeta;

import java.util.List;

public interface EntrySequence {

    // 判断是否为空
    boolean isEmpty();

    // 获取第一条日志的索引
    int getFirstLogIndex();

    // 获取最后一条日志索引
    int getLastLogIndex();

    // 获取下一条日志索引
    int getNextLogIndex();

    // 获取序列的子视图, 到最后一条日志
    List<Entry> subView(int fromIndex);

    // [fromIndex, toIndex)  获取序列的子视图, 指定范围, 不包括 toIndex 所指向的日志
    List<Entry> subList(int fromIndex, int toIndex);

    GroupConfigEntryList buildGroupConfigEntryList();

    // 检查某个日志条目是否存在
    boolean isEntryPresent(int index);

    // 获取某个日志条目的元信息
    EntryMeta getEntryMeta(int index);

    // 获取某个日志条目
    Entry getEntry(int index);

    // 获取最后一个日志条目
    Entry getLastEntry();

    void append(Entry entry);

    void append(List<Entry> entries);

    void commit(int index);

    int getCommitIndex();

    void removeAfter(int index);

    void close();

}
