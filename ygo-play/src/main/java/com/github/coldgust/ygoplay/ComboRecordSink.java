package com.github.coldgust.ygoplay;

/**
 * combo 录入落库回调：对局结束（自动认输完成录制）后由 runner 在对局线程上同步调用。
 *
 * <p>实现方负责提取步骤、生成摘要、写库与事件通知；必须自吞异常，
 * 录入失败绝不能影响对局结果的上报。
 */
public interface ComboRecordSink {

    void accept(ComboRecording recording);
}
