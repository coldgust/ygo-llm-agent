package com.github.coldgust.ygoagent.support;

/**
 * 服务端已知怪癖的**唯一**落点。新增 quirk 只能加在这里，避免多处补丁漂移。
 */
public final class RequestQuirks {

    /** 自由宣言卡名时使用的安全卡号（主卡组效果怪兽，灰流丽）。 */
    public static final long DEFAULT_DECLARE_CARD = 14558127L;

    /**
     * <p>服务端 quirk：count=0 但存在候选区时，必须答 1 个区域，否则服务端不接受/卡死。
     */
    public static int placeZoneCount(int requestedCount, int availableZones) {
        if (requestedCount > 0) {
            return Math.min(requestedCount, availableZones);
        }
        return availableZones > 0 ? 1 : 0;
    }

    private RequestQuirks() {
    }
}
