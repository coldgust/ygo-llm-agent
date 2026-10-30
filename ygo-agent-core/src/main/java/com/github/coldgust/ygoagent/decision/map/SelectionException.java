package com.github.coldgust.ygoagent.decision.map;

/** 意图无法映射为合法选择（越界/类型不符/数量不符）。 */
public class SelectionException extends RuntimeException {

    public SelectionException(String message) {
        super(message);
    }
}
